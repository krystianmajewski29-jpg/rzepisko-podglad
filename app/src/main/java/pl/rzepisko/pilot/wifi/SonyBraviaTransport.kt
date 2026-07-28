package pl.rzepisko.pilot.wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.rzepisko.pilot.core.AbstractTransport
import pl.rzepisko.pilot.core.ConnectionState
import pl.rzepisko.pilot.core.Credentials
import pl.rzepisko.pilot.core.KeyAction
import pl.rzepisko.pilot.core.RemoteDevice
import pl.rzepisko.pilot.core.RemoteException
import pl.rzepisko.pilot.core.RemoteKey

/**
 * Sony Bravia — sterowanie po IP (usługa IRCC).
 *
 * Telewizor przyjmuje SOAP-owe POST-y na `/sony/IRCC` z nagłówkiem `X-Auth-PSK`.
 * PSK ustawia się ręcznie na telewizorze:
 * Ustawienia → Sieć → Ustawienia sieci domowej → IP Control → Wstępnie współdzielony klucz.
 *
 * Transport jest bezstanowy — każde naciśnięcie to osobny HTTP POST — więc [doConnect]
 * służy tylko do sprawdzenia, czy PSK jest poprawny (żeby nie odkryć tego dopiero
 * przy pierwszym klawiszu).
 */
class SonyBraviaTransport(device: RemoteDevice) : AbstractTransport(device) {

    override val supportedKeys: Set<RemoteKey> = IRCC_CODES.keys

    override suspend fun doConnect(): RemoteDevice = withContext(Dispatchers.IO) {
        LocalNetwork.requirePrivate(currentDevice.address)
        if (psk().isBlank()) {
            throw RemoteException(
                "Brak klucza PSK. Ustaw go na telewizorze (Ustawienia → Sieć → IP Control) " +
                    "i wpisz ten sam klucz w ustawieniach pilota.",
            )
        }
        // Najtańsze żądanie, jakie weryfikuje PSK.
        val response = HttpClients.plain.await(
            Request.Builder()
                .url("http://${currentDevice.address}/sony/system")
                .addHeader("X-Auth-PSK", psk())
                .post(
                    """{"method":"getPowerStatus","id":1,"params":[],"version":"1.0"}"""
                        .toRequestBody(JSON_MEDIA_TYPE),
                )
                .build(),
        )
        response.use {
            when {
                it.code == 403 -> throw RemoteException("Telewizor odrzucił klucz PSK — sprawdź, czy się zgadza")
                !it.isSuccessful -> throw RemoteException("Telewizor odpowiedział błędem HTTP ${it.code}")
            }
        }
        currentDevice
    }

    override suspend fun sendKey(key: RemoteKey, action: KeyAction) {
        if (action == KeyAction.RELEASE) return
        val code = IRCC_CODES[key]
            ?: throw RemoteException("Telewizory Sony nie mają przycisku ${key.name}")
        ensureConnected()

        val envelope = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1">
                  <IRCCCode>$code</IRCCCode>
                </u:X_SendIRCC>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        withContext(Dispatchers.IO) {
            val response = HttpClients.plain.await(
                Request.Builder()
                    .url("http://${currentDevice.address}/sony/IRCC")
                    .addHeader("X-Auth-PSK", psk())
                    .addHeader("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"")
                    .post(envelope.toRequestBody(SOAP_MEDIA_TYPE))
                    .build(),
            )
            response.use {
                if (!it.isSuccessful) {
                    setState(ConnectionState.Failed("HTTP ${it.code}"))
                    throw RemoteException("Nie udało się wysłać przycisku (HTTP ${it.code})")
                }
            }
        }
    }

    private fun psk(): String = currentDevice.credentials[Credentials.SONY_PSK].orEmpty()

    override fun close() = setState(ConnectionState.Disconnected)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SOAP_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()

        /**
         * Kody IRCC (base64) z profilu Sony. Są takie same dla całej serii Bravia —
         * telewizor traktuje je jak ramki podczerwieni przysłane po sieci.
         */
        val IRCC_CODES: Map<RemoteKey, String> = mapOf(
            RemoteKey.POWER to "AAAAAQAAAAEAAAAVAw==",
            RemoteKey.POWER_ON to "AAAAAQAAAAEAAAAuAw==",
            RemoteKey.POWER_OFF to "AAAAAQAAAAEAAAAvAw==",
            RemoteKey.UP to "AAAAAQAAAAEAAAB0Aw==",
            RemoteKey.DOWN to "AAAAAQAAAAEAAAB1Aw==",
            RemoteKey.LEFT to "AAAAAQAAAAEAAAA0Aw==",
            RemoteKey.RIGHT to "AAAAAQAAAAEAAAAzAw==",
            RemoteKey.OK to "AAAAAQAAAAEAAABlAw==",
            RemoteKey.BACK to "AAAAAgAAAJcAAAAjAw==",
            RemoteKey.HOME to "AAAAAQAAAAEAAABgAw==",
            RemoteKey.MENU to "AAAAAgAAAJcAAAAsAw==",
            RemoteKey.EXIT to "AAAAAQAAAAEAAABjAw==",
            RemoteKey.INFO to "AAAAAQAAAAEAAAA6Aw==",
            RemoteKey.GUIDE to "AAAAAQAAAAEAAABbAw==",
            RemoteKey.SOURCE to "AAAAAQAAAAEAAAAlAw==",
            RemoteKey.VOLUME_UP to "AAAAAQAAAAEAAAASAw==",
            RemoteKey.VOLUME_DOWN to "AAAAAQAAAAEAAAATAw==",
            RemoteKey.MUTE to "AAAAAQAAAAEAAAAUAw==",
            RemoteKey.CHANNEL_UP to "AAAAAQAAAAEAAAAQAw==",
            RemoteKey.CHANNEL_DOWN to "AAAAAQAAAAEAAAARAw==",
            RemoteKey.PLAY to "AAAAAgAAAJcAAAAaAw==",
            RemoteKey.PAUSE to "AAAAAgAAAJcAAAAZAw==",
            RemoteKey.STOP to "AAAAAgAAAJcAAAAYAw==",
            RemoteKey.REWIND to "AAAAAgAAAJcAAAAbAw==",
            RemoteKey.FAST_FORWARD to "AAAAAgAAAJcAAAAcAw==",
            RemoteKey.PREVIOUS to "AAAAAgAAAJcAAAA8Aw==",
            RemoteKey.NEXT to "AAAAAgAAAJcAAAA9Aw==",
            RemoteKey.RECORD to "AAAAAgAAAJcAAAAgAw==",
            RemoteKey.NUM_0 to "AAAAAQAAAAEAAAAJAw==",
            RemoteKey.NUM_1 to "AAAAAQAAAAEAAAAAAw==",
            RemoteKey.NUM_2 to "AAAAAQAAAAEAAAABAw==",
            RemoteKey.NUM_3 to "AAAAAQAAAAEAAAACAw==",
            RemoteKey.NUM_4 to "AAAAAQAAAAEAAAADAw==",
            RemoteKey.NUM_5 to "AAAAAQAAAAEAAAAEAw==",
            RemoteKey.NUM_6 to "AAAAAQAAAAEAAAAFAw==",
            RemoteKey.NUM_7 to "AAAAAQAAAAEAAAAGAw==",
            RemoteKey.NUM_8 to "AAAAAQAAAAEAAAAHAw==",
            RemoteKey.NUM_9 to "AAAAAQAAAAEAAAAIAw==",
            RemoteKey.RED to "AAAAAgAAAJcAAAAlAw==",
            RemoteKey.GREEN to "AAAAAgAAAJcAAAAmAw==",
            RemoteKey.YELLOW to "AAAAAgAAAJcAAAAnAw==",
            RemoteKey.BLUE to "AAAAAgAAAJcAAAAkAw==",
        )
    }
}
