package pl.rzepisko.pilot.data

import android.content.Context
import pl.rzepisko.pilot.bluetooth.BluetoothHidTransport
import pl.rzepisko.pilot.bluetooth.isBluetoothHidSupported
import pl.rzepisko.pilot.core.Protocol
import pl.rzepisko.pilot.core.RemoteDevice
import pl.rzepisko.pilot.core.RemoteException
import pl.rzepisko.pilot.core.RemoteTransport
import pl.rzepisko.pilot.wifi.LgWebOsTransport
import pl.rzepisko.pilot.wifi.PhilipsJointSpaceTransport
import pl.rzepisko.pilot.wifi.RokuEcpTransport
import pl.rzepisko.pilot.wifi.SamsungTizenTransport
import pl.rzepisko.pilot.wifi.SonyBraviaTransport

/** Jedyne miejsce, które wie, jaka klasa obsługuje jaki protokół. */
class TransportFactory(private val context: Context) {

    fun create(device: RemoteDevice): RemoteTransport = when (device.protocol) {
        Protocol.SAMSUNG_TIZEN -> SamsungTizenTransport(device)
        Protocol.LG_WEBOS -> LgWebOsTransport(device)
        Protocol.SONY_BRAVIA -> SonyBraviaTransport(device)
        Protocol.ROKU_ECP -> RokuEcpTransport(device)
        Protocol.PHILIPS_JOINTSPACE -> PhilipsJointSpaceTransport(device)
        Protocol.BLUETOOTH_HID ->
            if (isBluetoothHidSupported()) {
                BluetoothHidTransport(device, context.applicationContext)
            } else {
                throw RemoteException(
                    "Tryb Bluetooth wymaga Androida 9 lub nowszego. Na tym telefonie " +
                        "steruj telewizorem przez Wi-Fi.",
                )
            }
    }
}
