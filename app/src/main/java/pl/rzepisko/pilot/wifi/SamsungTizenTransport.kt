package pl.rzepisko.pilot.wifi

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import pl.rzepisko.pilot.core.AbstractTransport
import pl.rzepisko.pilot.core.ConnectionState
import pl.rzepisko.pilot.core.Credentials
import pl.rzepisko.pilot.core.KeyAction
import pl.rzepisko.pilot.core.RemoteDevice
import pl.rzepisko.pilot.core.RemoteException
import pl.rzepisko.pilot.core.RemoteKey

/**
 * Samsung Smart TV z Tizenem (modele od 2016 r.).
 *
 * Protokół: WebSocket na porcie 8002 (TLS) — starsze sztuki wystawiają 8001 bez TLS-u.
 * Przy pierwszym połączeniu telewizor pokazuje pytanie "Zezwolić?" i dopiero po
 * akceptacji odsyła token w zdarzeniu `ms.channel.connect`. Token zapisujemy, bo bez
 * niego każde uruchomienie aplikacji wywoływałoby pytanie na nowo.
 */
class SamsungTizenTransport(
    device: RemoteDevice,
    private val appName: String = DEFAULT_APP_NAME,
) : AbstractTransport(device) {

    private var socket: JsonWebSocket? = null

    override val supportedKeys: Set<RemoteKey> = KEY_MAP.keys

    override val isConnected: Boolean
        get() = socket != null && state.value is ConnectionState.Connected

    override suspend fun doConnect(): RemoteDevice = withContext(Dispatchers.IO) {
        LocalNetwork.requirePrivate(currentDevice.address)
        val token = currentDevice.credentials[Credentials.SAMSUNG_TOKEN]

        if (token == null) {
            setState(ConnectionState.Pairing("Potwierdź połączenie na ekranie telewizora"))
        }

        val encodedName = Base64.encodeToString(appName.toByteArray(), Base64.NO_WRAP)
        val url = buildString {
            append("wss://").append(currentDevice.address).append(':').append(SECURE_PORT)
            append("/api/v2/channels/samsung.remote.control?name=").append(encodedName)
            if (token != null) append("&token=").append(token)
        }

        val ws = JsonWebSocket.open(
            client = HttpClients.trustingLocalTv,
            request = Request.Builder().url(url).build(),
        )

        // Telewizor odzywa się sam: albo ms.channel.connect (zgoda), albo
        // ms.channel.unauthorized (użytkownik odmówił lub token wygasł).
        val hello = try {
            ws.awaitMessage(
                timeoutMillis = if (token == null) PAIRING_TIMEOUT_MS else HANDSHAKE_TIMEOUT_MS,
                onTimeout = if (token == null) {
                    "Nie potwierdzono połączenia na telewizorze"
                } else {
                    "Telewizor nie odpowiedział na powitanie"
                },
            ) { it["event"]?.jsonPrimitive?.contentOrNullSafe() in HANDSHAKE_EVENTS }
        } catch (e: Exception) {
            ws.close()
            throw e
        }

        when (hello["event"]?.jsonPrimitive?.contentOrNullSafe()) {
            EVENT_UNAUTHORIZED -> {
                ws.close()
                throw RemoteException(
                    "Telewizor odrzucił połączenie. Usuń „$appName” w Ustawienia → Ogólne → " +
                        "Menedżer urządzeń zewnętrznych → Menedżer podłączania urządzeń i spróbuj ponownie.",
                )
            }

            EVENT_TIMEOUT -> {
                ws.close()
                throw RemoteException("Telewizor przerwał parowanie — spróbuj jeszcze raz")
            }
        }

        socket = ws

        // Nowy token pojawia się tylko przy pierwszym parowaniu.
        val newToken = hello["data"]?.jsonObject?.get("token")?.jsonPrimitive?.contentOrNullSafe()
        if (newToken != null && newToken != token) {
            currentDevice.withCredential(Credentials.SAMSUNG_TOKEN, newToken)
        } else {
            currentDevice
        }
    }

    override suspend fun sendKey(key: RemoteKey, action: KeyAction) {
        val code = KEY_MAP[key]
            ?: throw RemoteException("Telewizory Samsung nie mają przycisku ${key.name}")
        ensureConnected()
        val cmd = when (action) {
            KeyAction.CLICK -> "Click"
            KeyAction.PRESS -> "Press"
            KeyAction.RELEASE -> "Release"
        }
        val payload = buildJsonObject {
            put("method", "ms.remote.control")
            put(
                "params",
                buildJsonObject {
                    put("Cmd", cmd)
                    put("DataOfCmd", code)
                    put("Option", "false")
                    put("TypeOfRemote", "SendRemoteKey")
                },
            )
        }
        withContext(Dispatchers.IO) { requireSocket().sendJson(payload) }
    }

    override suspend fun sendText(text: String) {
        ensureConnected()
        val payload = buildJsonObject {
            put("method", "ms.remote.control")
            put(
                "params",
                buildJsonObject {
                    put("Cmd", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
                    put("DataOfCmd", "base64")
                    put("TypeOfRemote", "SendInputString")
                },
            )
        }
        withContext(Dispatchers.IO) { requireSocket().sendJson(payload) }
    }

    private fun requireSocket(): JsonWebSocket =
        socket ?: throw RemoteException("Brak połączenia z telewizorem")

    override fun close() {
        socket?.close()
        socket = null
        setState(ConnectionState.Disconnected)
    }

    companion object {
        const val SECURE_PORT = 8002
        private const val DEFAULT_APP_NAME = "Uniwersalny Pilot"
        private const val PAIRING_TIMEOUT_MS = 45_000L
        private const val HANDSHAKE_TIMEOUT_MS = 8_000L

        private const val EVENT_CONNECT = "ms.channel.connect"
        private const val EVENT_UNAUTHORIZED = "ms.channel.unauthorized"
        private const val EVENT_TIMEOUT = "ms.channel.timeOut"
        private val HANDSHAKE_EVENTS = setOf(EVENT_CONNECT, EVENT_UNAUTHORIZED, EVENT_TIMEOUT)

        /** Kody `KEY_*` z API Tizena. */
        val KEY_MAP: Map<RemoteKey, String> = mapOf(
            RemoteKey.POWER to "KEY_POWER",
            RemoteKey.POWER_OFF to "KEY_POWEROFF",
            RemoteKey.POWER_ON to "KEY_POWERON",
            RemoteKey.UP to "KEY_UP",
            RemoteKey.DOWN to "KEY_DOWN",
            RemoteKey.LEFT to "KEY_LEFT",
            RemoteKey.RIGHT to "KEY_RIGHT",
            RemoteKey.OK to "KEY_ENTER",
            RemoteKey.BACK to "KEY_RETURN",
            RemoteKey.HOME to "KEY_HOME",
            RemoteKey.MENU to "KEY_MENU",
            RemoteKey.EXIT to "KEY_EXIT",
            RemoteKey.INFO to "KEY_INFO",
            RemoteKey.GUIDE to "KEY_GUIDE",
            RemoteKey.SOURCE to "KEY_SOURCE",
            RemoteKey.VOLUME_UP to "KEY_VOLUP",
            RemoteKey.VOLUME_DOWN to "KEY_VOLDOWN",
            RemoteKey.MUTE to "KEY_MUTE",
            RemoteKey.CHANNEL_UP to "KEY_CHUP",
            RemoteKey.CHANNEL_DOWN to "KEY_CHDOWN",
            RemoteKey.PLAY to "KEY_PLAY",
            RemoteKey.PAUSE to "KEY_PAUSE",
            RemoteKey.PLAY_PAUSE to "KEY_PLAY_BACK",
            RemoteKey.STOP to "KEY_STOP",
            RemoteKey.REWIND to "KEY_REWIND",
            RemoteKey.FAST_FORWARD to "KEY_FF",
            RemoteKey.RECORD to "KEY_REC",
            RemoteKey.NUM_0 to "KEY_0",
            RemoteKey.NUM_1 to "KEY_1",
            RemoteKey.NUM_2 to "KEY_2",
            RemoteKey.NUM_3 to "KEY_3",
            RemoteKey.NUM_4 to "KEY_4",
            RemoteKey.NUM_5 to "KEY_5",
            RemoteKey.NUM_6 to "KEY_6",
            RemoteKey.NUM_7 to "KEY_7",
            RemoteKey.NUM_8 to "KEY_8",
            RemoteKey.NUM_9 to "KEY_9",
            RemoteKey.RED to "KEY_RED",
            RemoteKey.GREEN to "KEY_GREEN",
            RemoteKey.YELLOW to "KEY_YELLOW",
            RemoteKey.BLUE to "KEY_BLUE",
        )
    }
}

/** `jsonPrimitive.content` rzuca na JSON-owym `null` — tu wolimy dostać `null`. */
internal fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
