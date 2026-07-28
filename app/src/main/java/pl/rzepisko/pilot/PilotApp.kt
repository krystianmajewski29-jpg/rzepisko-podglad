package pl.rzepisko.pilot

import android.app.Application
import pl.rzepisko.pilot.data.RemoteRepository
import pl.rzepisko.pilot.discovery.BluetoothDiscovery
import pl.rzepisko.pilot.discovery.SsdpDiscovery

/**
 * Ręczny service locator zamiast biblioteki DI.
 *
 * Aplikacja ma trzy zależności i jeden proces — Hilt dołożyłby tu procesor adnotacji
 * i minutę do czasu builda, nie rozwiązując żadnego problemu, którego byśmy mieli.
 */
class PilotApp : Application() {

    val repository: RemoteRepository by lazy { RemoteRepository(this) }
    val ssdpDiscovery: SsdpDiscovery by lazy { SsdpDiscovery(this) }
    val bluetoothDiscovery: BluetoothDiscovery by lazy { BluetoothDiscovery(this) }
}
