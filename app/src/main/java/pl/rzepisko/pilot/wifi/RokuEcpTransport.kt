package pl.rzepisko.pilot.wifi

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.rzepisko.pilot.core.AbstractTransport
import pl.rzepisko.pilot.core.ConnectionState
import pl.rzepisko.pilot.core.KeyAction
import pl.rzepisko.pilot.core.RemoteDevice
import pl.rzepisko.pilot.core.RemoteException
import pl.rzepisko.pilot.core.RemoteKey

/**
 * Roku i telewizory z systemem Roku TV (TCL, Hisense, część Philipsów, Sharp).
 *
 * External Control Protocol: zwykły HTTP na porcie 8060, bez parowania i bez tokenów.
 * Najprostszy z obsługiwanych protokołów — cała komenda to `POST /keypress/<Klawisz>`.
 *
 * Warunek: w ustawieniach urządzenia „Control by mobile apps” musi być ustawione na
 * „Permissive” lub „Default” (fabrycznie jest włączone).
 */
class RokuEcpTransport(device: RemoteDevice) : AbstractTransport(device) {

    override val supportedKeys: Set<RemoteKey> = ECP_KEYS.keys + APP_IDS.keys

    private val baseUrl: String
        get() = "http://" + LocalNetwork.endpoint(currentDevice.address, PORT)

    override suspend fun doConnect(): RemoteDevice = withContext(Dispatchers.IO) {
        LocalNetwork.requirePrivate(currentDevice.address)
        val response = HttpClients.plain.await(
            Request.Builder().url("$baseUrl/query/device-info").get().build(),
        )
        response.use {
            if (!it.isSuccessful) {
                throw RemoteException("Urządzenie Roku nie odpowiada poprawnie (HTTP ${it.code})")
            }
        }
        currentDevice
    }

    override suspend fun sendKey(key: RemoteKey, action: KeyAction) {
        ensureConnected()
        APP_IDS[key]?.let { appId ->
            post("$baseUrl/launch/$appId")
            return
        }
        val ecpKey = ECP_KEYS[key]
            ?: throw RemoteException("Roku nie ma przycisku ${key.name}")
        val verb = when (action) {
            KeyAction.CLICK -> "keypress"
            KeyAction.PRESS -> "keydown"
            KeyAction.RELEASE -> "keyup"
        }
        post("$baseUrl/$verb/$ecpKey")
    }

    /** Roku przyjmuje tekst znak po znaku jako `Lit_<znak>`. */
    override suspend fun sendText(text: String) {
        ensureConnected()
        text.forEach { char ->
            post("$baseUrl/keypress/Lit_${URLEncoder.encode(char.toString(), "UTF-8")}")
        }
    }

    private suspend fun post(url: String) = withContext(Dispatchers.IO) {
        val response = HttpClients.plain.await(
            Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build(),
        )
        response.use {
            if (!it.isSuccessful) {
                throw RemoteException("Roku odrzuciło polecenie (HTTP ${it.code})")
            }
        }
    }

    override fun close() = setState(ConnectionState.Disconnected)

    companion object {
        const val PORT = 8060

        val ECP_KEYS: Map<RemoteKey, String> = mapOf(
            RemoteKey.POWER to "Power",
            RemoteKey.POWER_ON to "PowerOn",
            RemoteKey.POWER_OFF to "PowerOff",
            RemoteKey.UP to "Up",
            RemoteKey.DOWN to "Down",
            RemoteKey.LEFT to "Left",
            RemoteKey.RIGHT to "Right",
            RemoteKey.OK to "Select",
            RemoteKey.BACK to "Back",
            RemoteKey.HOME to "Home",
            RemoteKey.INFO to "Info",
            RemoteKey.EXIT to "Home",
            RemoteKey.SOURCE to "InputHDMI1",
            RemoteKey.VOLUME_UP to "VolumeUp",
            RemoteKey.VOLUME_DOWN to "VolumeDown",
            RemoteKey.MUTE to "VolumeMute",
            RemoteKey.CHANNEL_UP to "ChannelUp",
            RemoteKey.CHANNEL_DOWN to "ChannelDown",
            RemoteKey.PLAY to "Play",
            RemoteKey.PAUSE to "Play", // ECP ma jeden przycisk play/pause
            RemoteKey.PLAY_PAUSE to "Play",
            RemoteKey.REWIND to "Rev",
            RemoteKey.FAST_FORWARD to "Fwd",
            RemoteKey.PREVIOUS to "InstantReplay",
        )

        /** Identyfikatory kanałów Roku uruchamiane przez `/launch/<id>`. */
        val APP_IDS: Map<RemoteKey, String> = mapOf(
            RemoteKey.APP_NETFLIX to "12",
            RemoteKey.APP_YOUTUBE to "837",
            RemoteKey.APP_PRIME_VIDEO to "13",
            RemoteKey.APP_DISNEY_PLUS to "291097",
        )
    }
}
