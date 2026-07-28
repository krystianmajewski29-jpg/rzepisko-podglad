package pl.rzepisko.pilot.wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.rzepisko.pilot.core.AbstractTransport
import pl.rzepisko.pilot.core.ConnectionState
import pl.rzepisko.pilot.core.KeyAction
import pl.rzepisko.pilot.core.RemoteDevice
import pl.rzepisko.pilot.core.RemoteException
import pl.rzepisko.pilot.core.RemoteKey

/**
 * Philips z JointSpace API v6 (roczniki ~2014-2015, port 1925, bez szyfrowania).
 *
 * Ograniczenie, o którym warto wiedzieć: nowsze Philipsy (Android TV, API v6.2+ na
 * porcie 1926) wymagają HTTPS z uwierzytelnianiem Digest i parowania PIN-em. Ten
 * sterownik ich **nie** obsłuży — dla nich właściwym wyborem jest tryb Bluetooth,
 * i tak podpowiada UI, gdy próba na porcie 1925 zwróci brak odpowiedzi.
 */
class PhilipsJointSpaceTransport(device: RemoteDevice) : AbstractTransport(device) {

    override val supportedKeys: Set<RemoteKey> = KEY_NAMES.keys

    private val baseUrl: String
        get() = "http://" + LocalNetwork.endpoint(currentDevice.address, PORT) + "/$API_VERSION"

    override suspend fun doConnect(): RemoteDevice = withContext(Dispatchers.IO) {
        LocalNetwork.requirePrivate(currentDevice.address)
        val response = try {
            HttpClients.plain.await(Request.Builder().url("$baseUrl/system").get().build())
        } catch (e: Exception) {
            throw RemoteException(
                "Brak odpowiedzi na porcie $PORT. Nowsze telewizory Philips wymagają " +
                    "parowania PIN-em, którego ta wersja nie obsługuje — użyj trybu Bluetooth.",
                e,
            )
        }
        response.use {
            if (!it.isSuccessful) {
                throw RemoteException("Telewizor odpowiedział błędem HTTP ${it.code}")
            }
        }
        currentDevice
    }

    override suspend fun sendKey(key: RemoteKey, action: KeyAction) {
        if (action == KeyAction.RELEASE) return
        val name = KEY_NAMES[key]
            ?: throw RemoteException("Telewizory Philips nie mają przycisku ${key.name}")
        ensureConnected()
        withContext(Dispatchers.IO) {
            val response = HttpClients.plain.await(
                Request.Builder()
                    .url("$baseUrl/input/key")
                    .post("""{"key":"$name"}""".toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            )
            response.use {
                if (!it.isSuccessful) {
                    throw RemoteException("Nie udało się wysłać przycisku (HTTP ${it.code})")
                }
            }
        }
    }

    override fun close() = setState(ConnectionState.Disconnected)

    companion object {
        const val PORT = 1925
        private const val API_VERSION = 6
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val KEY_NAMES: Map<RemoteKey, String> = mapOf(
            RemoteKey.POWER to "Standby",
            RemoteKey.POWER_OFF to "Standby",
            RemoteKey.UP to "CursorUp",
            RemoteKey.DOWN to "CursorDown",
            RemoteKey.LEFT to "CursorLeft",
            RemoteKey.RIGHT to "CursorRight",
            RemoteKey.OK to "Confirm",
            RemoteKey.BACK to "Back",
            RemoteKey.HOME to "Home",
            RemoteKey.MENU to "Options",
            RemoteKey.EXIT to "Exit",
            RemoteKey.INFO to "Info",
            RemoteKey.SOURCE to "Source",
            RemoteKey.VOLUME_UP to "VolumeUp",
            RemoteKey.VOLUME_DOWN to "VolumeDown",
            RemoteKey.MUTE to "Mute",
            RemoteKey.CHANNEL_UP to "ChannelStepUp",
            RemoteKey.CHANNEL_DOWN to "ChannelStepDown",
            RemoteKey.PLAY to "Play",
            RemoteKey.PAUSE to "Pause",
            RemoteKey.PLAY_PAUSE to "PlayPause",
            RemoteKey.STOP to "Stop",
            RemoteKey.REWIND to "Rewind",
            RemoteKey.FAST_FORWARD to "FastForward",
            RemoteKey.PREVIOUS to "Previous",
            RemoteKey.NEXT to "Next",
            RemoteKey.RECORD to "Record",
            RemoteKey.NUM_0 to "Digit0",
            RemoteKey.NUM_1 to "Digit1",
            RemoteKey.NUM_2 to "Digit2",
            RemoteKey.NUM_3 to "Digit3",
            RemoteKey.NUM_4 to "Digit4",
            RemoteKey.NUM_5 to "Digit5",
            RemoteKey.NUM_6 to "Digit6",
            RemoteKey.NUM_7 to "Digit7",
            RemoteKey.NUM_8 to "Digit8",
            RemoteKey.NUM_9 to "Digit9",
            RemoteKey.RED to "RedColour",
            RemoteKey.GREEN to "GreenColour",
            RemoteKey.YELLOW to "YellowColour",
            RemoteKey.BLUE to "BlueColour",
        )
    }
}
