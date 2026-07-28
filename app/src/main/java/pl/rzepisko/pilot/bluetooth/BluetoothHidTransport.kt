package pl.rzepisko.pilot.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import pl.rzepisko.pilot.core.AbstractTransport
import pl.rzepisko.pilot.core.ConnectionState
import pl.rzepisko.pilot.core.KeyAction
import pl.rzepisko.pilot.core.RemoteDevice
import pl.rzepisko.pilot.core.RemoteException
import pl.rzepisko.pilot.core.RemoteKey

/**
 * Pilot po Bluetoothcie: telefon zgłasza się jako klawiatura HID, telewizor traktuje go
 * tak jak oryginalny pilot z krzyżakiem.
 *
 * Zaleta wobec trybu Wi-Fi: nie zależy od API producenta ani od tego, czy telewizor
 * jest w tej samej sieci. Działa z Android TV / Google TV, Nvidia Shield i tymi
 * telewizorami, które przyjmują klawiaturę Bluetooth (większość od ok. 2018 r.).
 *
 * Wada: nie wyśle poleceń spoza standardu HID — nie ma tu przycisków kolorowych
 * ani uruchamiania konkretnych aplikacji.
 */
@RequiresApi(Build.VERSION_CODES.P)
class BluetoothHidTransport(
    device: RemoteDevice,
    context: Context,
) : AbstractTransport(device) {

    private val controller = BluetoothHidController.get(context)

    @Volatile
    private var target: BluetoothDevice? = null

    override val supportedKeys: Set<RemoteKey> = HidKeyMap.CODES.keys

    override val isConnected: Boolean
        get() = target != null && controller.connectedDevice.value?.address == currentDevice.address

    override suspend fun doConnect(): RemoteDevice {
        setState(ConnectionState.Pairing("Łączę się z ${currentDevice.name}…"))
        target = controller.connectTo(currentDevice.address)
        return currentDevice
    }

    override suspend fun sendKey(key: RemoteKey, action: KeyAction) {
        val code = HidKeyMap.CODES[key]
            ?: throw RemoteException("Pilot Bluetooth nie obsługuje przycisku ${key.name}")
        ensureConnected()
        val device = target ?: throw RemoteException("Brak połączenia z urządzeniem")

        when (code) {
            is HidCode.Keyboard -> when (action) {
                KeyAction.PRESS -> send(device, HidDescriptor.REPORT_ID_KEYBOARD, HidKeyMap.buildKeyboardReport(code.usage))
                KeyAction.RELEASE -> send(device, HidDescriptor.REPORT_ID_KEYBOARD, HidKeyMap.emptyKeyboardReport())
                KeyAction.CLICK -> {
                    send(device, HidDescriptor.REPORT_ID_KEYBOARD, HidKeyMap.buildKeyboardReport(code.usage))
                    delay(KEY_HOLD_MS)
                    send(device, HidDescriptor.REPORT_ID_KEYBOARD, HidKeyMap.emptyKeyboardReport())
                }
            }

            is HidCode.Consumer -> when (action) {
                KeyAction.PRESS -> send(device, HidDescriptor.REPORT_ID_CONSUMER, HidKeyMap.buildConsumerReport(code.usage))
                KeyAction.RELEASE -> send(device, HidDescriptor.REPORT_ID_CONSUMER, HidKeyMap.emptyConsumerReport())
                KeyAction.CLICK -> {
                    send(device, HidDescriptor.REPORT_ID_CONSUMER, HidKeyMap.buildConsumerReport(code.usage))
                    delay(KEY_HOLD_MS)
                    send(device, HidDescriptor.REPORT_ID_CONSUMER, HidKeyMap.emptyConsumerReport())
                }
            }
        }
    }

    override suspend fun sendText(text: String) {
        ensureConnected()
        val device = target ?: throw RemoteException("Brak połączenia z urządzeniem")
        text.forEach { char ->
            val (usage, shift) = HidKeyMap.keyboardUsageForChar(char) ?: return@forEach
            send(device, HidDescriptor.REPORT_ID_KEYBOARD, HidKeyMap.buildKeyboardReport(usage, shift))
            delay(KEY_HOLD_MS)
            send(device, HidDescriptor.REPORT_ID_KEYBOARD, HidKeyMap.emptyKeyboardReport())
            delay(KEY_GAP_MS)
        }
    }

    private fun send(device: BluetoothDevice, reportId: Int, data: ByteArray) {
        if (!controller.sendReport(device, reportId, data)) {
            setState(ConnectionState.Disconnected)
            target = null
            throw RemoteException("Nie udało się wysłać polecenia — połączenie zostało zerwane")
        }
    }

    override fun close() {
        target?.let { controller.disconnect(it) }
        target = null
        setState(ConnectionState.Disconnected)
    }

    companion object {
        /**
         * Telewizory gubią naciśnięcia krótsze niż ~20 ms, a przy dłuższych niż ~60 ms
         * odpalają autorepeat. 30 ms trafia w środek tego okna.
         */
        private const val KEY_HOLD_MS = 30L
        private const val KEY_GAP_MS = 15L
    }
}
