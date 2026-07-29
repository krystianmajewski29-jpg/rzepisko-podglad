package pl.rzepisko.pilot.discovery

import android.annotation.SuppressLint
import android.content.Context
import pl.rzepisko.pilot.bluetooth.BluetoothHidController
import pl.rzepisko.pilot.bluetooth.isBluetoothHidSupported
import pl.rzepisko.pilot.core.DiscoveredDevice
import pl.rzepisko.pilot.core.Protocol

/**
 * Lista urządzeń Bluetooth, którym można wysyłać raporty HID.
 *
 * Świadomie **nie** skanujemy otoczenia: żeby telefon mógł udawać klawiaturę, telewizor
 * musi być najpierw sparowany w ustawieniach systemowych Androida (to tam odbywa się
 * wymiana kluczy i potwierdzenie PIN-u). Skanowanie pokazywałoby więc urządzenia,
 * z którymi i tak nic nie da się zrobić. Zamiast tego pokazujemy sparowane
 * i odsyłamy do ustawień systemu, gdy lista jest pusta.
 *
 * Na Androidzie 7 i 8 (API 24-27) `BluetoothHidDevice` nie istnieje, więc każda metoda
 * zwraca wynik pusty, a UI wyłącza przełącznik trybu Bluetooth.
 */
class BluetoothDiscovery(private val context: Context) {

    val isSupported: Boolean get() = isBluetoothHidSupported()

    fun hasPermission(): Boolean =
        isBluetoothHidSupported() && BluetoothHidController.get(context).hasConnectPermission()

    @SuppressLint("MissingPermission") // sprawdzane w BluetoothHidController.hasConnectPermission()
    fun bondedDevices(): List<DiscoveredDevice> =
        if (isBluetoothHidSupported()) {
            BluetoothHidController.get(context).bondedDevices().map { device ->
                DiscoveredDevice(
                    name = device.name ?: device.address,
                    address = device.address,
                    protocol = Protocol.BLUETOOTH_HID,
                    model = null,
                    source = "Bluetooth",
                )
            }
        } else {
            emptyList()
        }
}
