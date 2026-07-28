package pl.rzepisko.pilot.wifi

import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Klienci HTTP używani przez sterowniki Wi-Fi.
 *
 * Telewizory w LAN-ie odpowiadają szybko albo wcale, więc timeouty są krótkie —
 * lepiej pokazać błąd po 3 sekundach niż zawiesić UI na pół minuty.
 */
object HttpClients {

    val plain: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Wariant akceptujący certyfikaty telewizorów.
     *
     * Samsung na porcie 8002 serwuje samopodpisany certyfikat wystawiony na losową
     * nazwę — normalna walidacja PKI go odrzuca i nie da się tego naprawić, bo nie
     * istnieje CA, które by go podpisało. Zaufanie ograniczamy więc do adresów
     * w sieci lokalnej: [LocalNetwork.isPrivateAddress] jest sprawdzane przez sterowniki
     * zanim w ogóle sięgną po tego klienta.
     *
     * Do niczego poza LAN-em tego klienta nie używamy.
     */
    val trustingLocalTv: OkHttpClient by lazy {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        plain.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}

object LocalNetwork {

    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * Czy adres należy do sieci prywatnej (RFC 1918), link-local albo loopbacka.
     *
     * Bramka bezpieczeństwa: pilot z założenia steruje sprzętem w domu, więc odmawiamy
     * łączenia się z adresami publicznymi — zwłaszcza tymi, dla których wyłączamy
     * weryfikację certyfikatu.
     */
    fun isPrivateAddress(address: String): Boolean {
        val host = address.substringBefore(':').trim()
        val m = ipv4.matchEntire(host) ?: return false
        val octets = (1..4).map { m.groupValues[it].toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return when {
            a == 10 -> true
            a == 127 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }

    /**
     * Skleja `host:port` do URL-a. Gdy użytkownik wpisał adres z własnym portem
     * (np. `192.168.1.30:8061`), zostaje jego wybór — część osób wystawia telewizory
     * przez przekierowanie portów albo trzyma je za proxy.
     */
    fun endpoint(address: String, defaultPort: Int): String =
        if (address.contains(':')) address else "$address:$defaultPort"

    fun requirePrivate(address: String) {
        if (!isPrivateAddress(address)) {
            throw pl.rzepisko.pilot.core.RemoteException(
                "Adres $address nie jest adresem w sieci lokalnej. " +
                    "Pilot łączy się tylko z urządzeniami w Twojej sieci domowej.",
            )
        }
    }
}
