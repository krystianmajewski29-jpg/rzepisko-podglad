package pl.rzepisko.pilot.wifi

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import pl.rzepisko.pilot.core.AbstractTransport
import pl.rzepisko.pilot.core.ConnectionState
import pl.rzepisko.pilot.core.Credentials
import pl.rzepisko.pilot.core.KeyAction
import pl.rzepisko.pilot.core.RemoteDevice
import pl.rzepisko.pilot.core.RemoteException
import pl.rzepisko.pilot.core.RemoteKey

/**
 * Telewizory LG z webOS.
 *
 * Protokół SSAP: WebSocket na porcie 3000. Rejestracja zwraca `client-key`, który
 * zapisujemy — z nim kolejne połączenia idą bez pytania na TV.
 *
 * Osobliwość webOS-a: klawisze nawigacyjne (strzałki, OK, BACK, kolory) nie mają
 * własnych endpointów SSAP. Trzeba poprosić o "pointer input socket" — drugi WebSocket,
 * na który leci prosty protokół tekstowy `type:button\nname:UP\n\n`. Głośność, kanały
 * i wyłączanie idą normalnym SSAP-em, bo działa nawet gdy pointer socket jest zajęty.
 */
class LgWebOsTransport(
    device: RemoteDevice,
    private val appName: String = "Uniwersalny Pilot",
) : AbstractTransport(device) {

    private var ssap: JsonWebSocket? = null
    private var pointer: JsonWebSocket? = null
    private val requestCounter = AtomicInteger(0)

    override val supportedKeys: Set<RemoteKey> = SSAP_URIS.keys + POINTER_BUTTONS.keys

    override val isConnected: Boolean
        get() = ssap != null && state.value is ConnectionState.Connected

    override suspend fun doConnect(): RemoteDevice = withContext(Dispatchers.IO) {
        LocalNetwork.requirePrivate(currentDevice.address)
        val clientKey = currentDevice.credentials[Credentials.LG_CLIENT_KEY]

        val ws = JsonWebSocket.open(
            client = HttpClients.plain,
            request = Request.Builder().url("ws://${currentDevice.address}:$PORT").build(),
        )

        try {
            ws.sendJson(registerPayload(clientKey))

            if (clientKey == null) {
                setState(ConnectionState.Pairing("Wybierz „Tak” na ekranie telewizora"))
            }

            // TV odpowiada najpierw `response` (prompt wyświetlony), potem `registered`.
            val registered = ws.awaitMessage(
                timeoutMillis = if (clientKey == null) PAIRING_TIMEOUT_MS else HANDSHAKE_TIMEOUT_MS,
                onTimeout = if (clientKey == null) {
                    "Nie potwierdzono parowania na telewizorze"
                } else {
                    "Telewizor nie odpowiedział na rejestrację"
                },
            ) { it["type"]?.jsonPrimitive?.contentOrNullSafe() in setOf("registered", "error") }

            if (registered["type"]?.jsonPrimitive?.contentOrNullSafe() == "error") {
                val error = registered["error"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                throw RemoteException("Telewizor odrzucił parowanie: $error")
            }

            ssap = ws
            val newKey = registered["payload"]?.jsonObject
                ?.get("client-key")?.jsonPrimitive?.contentOrNullSafe()

            if (newKey != null && newKey != clientKey) {
                currentDevice.withCredential(Credentials.LG_CLIENT_KEY, newKey)
            } else {
                currentDevice
            }
        } catch (e: Exception) {
            ws.close()
            ssap = null
            throw e
        }
    }

    override suspend fun sendKey(key: RemoteKey, action: KeyAction) {
        if (action == KeyAction.RELEASE) return // webOS nie rozróżnia press/release
        ensureConnected()
        withContext(Dispatchers.IO) {
            SSAP_URIS[key]?.let { uri ->
                request(uri, SSAP_PAYLOADS[key])
                return@withContext
            }
            val button = POINTER_BUTTONS[key]
                ?: throw RemoteException("Telewizory LG nie mają przycisku ${key.name}")
            pointerSocket().sendRaw("type:button\nname:$button\n\n")
        }
    }

    override suspend fun sendText(text: String) {
        ensureConnected()
        withContext(Dispatchers.IO) {
            request("ssap://com.webos.service.ime/insertText", buildJsonObject { put("text", text) })
        }
    }

    /** Wysyła żądanie SSAP i czeka na odpowiedź o tym samym `id`. */
    private suspend fun request(uri: String, payload: JsonObject? = null): JsonObject {
        val socket = ssap ?: throw RemoteException("Brak połączenia z telewizorem")
        val id = "req_${requestCounter.incrementAndGet()}"
        socket.sendJson(
            buildJsonObject {
                put("type", "request")
                put("id", id)
                put("uri", uri)
                if (payload != null) put("payload", payload)
            },
        )
        return socket.awaitMessage(REQUEST_TIMEOUT_MS) {
            it["id"]?.jsonPrimitive?.contentOrNullSafe() == id
        }
    }

    /** Otwiera (leniwie) drugi WebSocket do przycisków nawigacyjnych. */
    private suspend fun pointerSocket(): JsonWebSocket {
        pointer?.let { return it }
        val response = request("ssap://com.webos.service.networkinput/getPointerInputSocket")
        val path = response["payload"]?.jsonObject
            ?.get("socketPath")?.jsonPrimitive?.contentOrNullSafe()
            ?: throw RemoteException("Telewizor nie udostępnił kanału przycisków")

        return JsonWebSocket.open(
            client = HttpClients.plain,
            request = Request.Builder().url(path).build(),
        ).also { pointer = it }
    }

    private fun registerPayload(clientKey: String?): JsonObject = buildJsonObject {
        put("type", "register")
        put("id", "register_0")
        putJsonObject("payload") {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            if (clientKey != null) put("client-key", clientKey)
            putJsonObject("manifest") {
                put("manifestVersion", 1)
                put("appVersion", "1.0")
                put("signed", buildJsonObject { put("appId", APP_ID) })
                put("appId", APP_ID)
                put("vendorId", "pl.rzepisko")
                put("localizedAppNames", buildJsonObject { put("", appName) })
                putJsonArray("permissions") {
                    PERMISSIONS.forEach { add(it) }
                }
            }
        }
    }

    override fun close() {
        pointer?.close()
        pointer = null
        ssap?.close()
        ssap = null
        setState(ConnectionState.Disconnected)
    }

    companion object {
        const val PORT = 3000
        private const val APP_ID = "pl.rzepisko.pilot"
        private const val PAIRING_TIMEOUT_MS = 45_000L
        private const val HANDSHAKE_TIMEOUT_MS = 8_000L
        private const val REQUEST_TIMEOUT_MS = 6_000L

        private val PERMISSIONS = listOf(
            "CONTROL_POWER",
            "CONTROL_AUDIO",
            "CONTROL_INPUT_TV",
            "CONTROL_INPUT_MEDIA_PLAYBACK",
            "CONTROL_INPUT_MEDIA_RECORDING",
            "CONTROL_INPUT_JOYSTICK",
            "CONTROL_INPUT_TEXT",
            "READ_INSTALLED_APPS",
            "LAUNCH",
            "READ_CURRENT_CHANNEL",
        )

        /** Klawisze mające dedykowany endpoint SSAP — pewniejsze niż pointer socket. */
        val SSAP_URIS: Map<RemoteKey, String> = mapOf(
            RemoteKey.POWER to "ssap://system/turnOff",
            RemoteKey.POWER_OFF to "ssap://system/turnOff",
            RemoteKey.VOLUME_UP to "ssap://audio/volumeUp",
            RemoteKey.VOLUME_DOWN to "ssap://audio/volumeDown",
            RemoteKey.CHANNEL_UP to "ssap://tv/channelUp",
            RemoteKey.CHANNEL_DOWN to "ssap://tv/channelDown",
            RemoteKey.PLAY to "ssap://media.controls/play",
            RemoteKey.PAUSE to "ssap://media.controls/pause",
            RemoteKey.STOP to "ssap://media.controls/stop",
            RemoteKey.REWIND to "ssap://media.controls/rewind",
            RemoteKey.FAST_FORWARD to "ssap://media.controls/fastForward",
            RemoteKey.APP_NETFLIX to "ssap://system.launcher/launch",
            RemoteKey.APP_YOUTUBE to "ssap://system.launcher/launch",
            RemoteKey.APP_PRIME_VIDEO to "ssap://system.launcher/launch",
            RemoteKey.APP_DISNEY_PLUS to "ssap://system.launcher/launch",
        )

        /** Dodatkowe pola żądania dla endpointów, które ich wymagają. */
        val SSAP_PAYLOADS: Map<RemoteKey, JsonObject> = mapOf(
            RemoteKey.APP_NETFLIX to buildJsonObject { put("id", "netflix") },
            RemoteKey.APP_YOUTUBE to buildJsonObject { put("id", "youtube.leanback.v4") },
            RemoteKey.APP_PRIME_VIDEO to buildJsonObject { put("id", "amazon") },
            RemoteKey.APP_DISNEY_PLUS to buildJsonObject { put("id", "com.disney.disneyplus-prod") },
        )

        /** Nazwy przycisków wysyłane przez pointer input socket. */
        val POINTER_BUTTONS: Map<RemoteKey, String> = mapOf(
            RemoteKey.UP to "UP",
            RemoteKey.DOWN to "DOWN",
            RemoteKey.LEFT to "LEFT",
            RemoteKey.RIGHT to "RIGHT",
            RemoteKey.OK to "ENTER",
            RemoteKey.MUTE to "MUTE",
            RemoteKey.BACK to "BACK",
            RemoteKey.HOME to "HOME",
            RemoteKey.MENU to "MENU",
            RemoteKey.EXIT to "EXIT",
            RemoteKey.INFO to "INFO",
            RemoteKey.GUIDE to "GUIDE",
            RemoteKey.RED to "RED",
            RemoteKey.GREEN to "GREEN",
            RemoteKey.YELLOW to "YELLOW",
            RemoteKey.BLUE to "BLUE",
            RemoteKey.NUM_0 to "0",
            RemoteKey.NUM_1 to "1",
            RemoteKey.NUM_2 to "2",
            RemoteKey.NUM_3 to "3",
            RemoteKey.NUM_4 to "4",
            RemoteKey.NUM_5 to "5",
            RemoteKey.NUM_6 to "6",
            RemoteKey.NUM_7 to "7",
            RemoteKey.NUM_8 to "8",
            RemoteKey.NUM_9 to "9",
        )
    }
}
