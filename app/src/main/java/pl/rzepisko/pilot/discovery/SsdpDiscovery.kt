package pl.rzepisko.pilot.discovery

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.rzepisko.pilot.core.DiscoveredDevice
import pl.rzepisko.pilot.core.Protocol

/**
 * Wyszukiwanie telewizorów w sieci lokalnej przez SSDP (część UPnP).
 *
 * Zasada: wysyłamy multicastem `M-SEARCH` na 239.255.255.250:1900 i zbieramy odpowiedzi.
 * Zamiast jednego zapytania `ssdp:all` pytamy o konkretne typy usług — telewizory
 * odpowiadają na nie chętniej i szybciej, a przy okazji sam typ usługi zdradza protokół.
 *
 * Ograniczenie SSDP: pakiety multicast nie przechodzą przez izolację klientów w routerze
 * ani między sieciami 2,4/5 GHz na niektórych sprzętach. Dlatego UI zawsze zostawia
 * możliwość wpisania adresu IP ręcznie.
 */
class SsdpDiscovery(private val context: Context) {

    /**
     * Emituje znalezione urządzenia w miarę napływania odpowiedzi.
     *
     * Flow kończy się po [durationMillis]. Duplikaty (to samo IP + protokół) są odfiltrowane.
     */
    fun discover(durationMillis: Long = DEFAULT_DURATION_MS): Flow<DiscoveredDevice> = callbackFlow {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // Bez blokady multicastu Android odrzuca ramki multicast, żeby oszczędzać baterię —
        // socket wtedy po prostu nic nie odbiera i wyszukiwanie zawsze zwraca pustkę.
        val lock = wifi?.createMulticastLock("pilot-ssdp")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        val socket = DatagramSocket().apply {
            reuseAddress = true
            broadcast = true
            soTimeout = SOCKET_TIMEOUT_MS
        }
        val seen = mutableSetOf<String>()
        val group = InetAddress.getByName(SSDP_ADDRESS)

        val sender = launch(Dispatchers.IO) {
            // Trzy rundy — UDP gubi pakiety, a telewizory w standby bywają leniwe.
            repeat(3) { round ->
                SEARCH_TARGETS.keys.forEach { target ->
                    val message = mSearch(target)
                    runCatching {
                        socket.send(
                            DatagramPacket(
                                message.toByteArray(),
                                message.length,
                                InetSocketAddress(group, SSDP_PORT),
                            ),
                        )
                    }
                }
                if (round < 2) kotlinx.coroutines.delay(800)
            }
        }

        val receiver = launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            val deadline = System.currentTimeMillis() + durationMillis
            while (isActive && System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                val response = String(packet.data, 0, packet.length)
                val address = packet.address?.hostAddress ?: continue
                val device = SsdpResponseParser.parse(response, address) ?: continue
                if (seen.add("${device.address}|${device.protocol}")) {
                    trySend(device)
                }
            }
            close()
        }

        awaitClose {
            sender.cancel()
            receiver.cancel()
            runCatching { socket.close() }
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }.flowOn(Dispatchers.IO)

    private fun mSearch(searchTarget: String): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 2\r\n")
        append("ST: $searchTarget\r\n")
        append("\r\n")
    }

    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SOCKET_TIMEOUT_MS = 500
        private const val DEFAULT_DURATION_MS = 6_000L

        /** Typy usług, o które pytamy, wraz z protokołem, na który wskazują. */
        val SEARCH_TARGETS: Map<String, Protocol?> = mapOf(
            "roku:ecp" to Protocol.ROKU_ECP,
            "urn:samsung.com:device:RemoteControlReceiver:1" to Protocol.SAMSUNG_TIZEN,
            "urn:lge-com:service:webos-second-screen:1" to Protocol.LG_WEBOS,
            "urn:schemas-sony-com:service:IRCC:1" to Protocol.SONY_BRAVIA,
            "urn:schemas-upnp-org:device:MediaRenderer:1" to null,
            "ssdp:all" to null,
        )
    }
}
