package pl.rzepisko.pilot.discovery

import pl.rzepisko.pilot.core.DiscoveredDevice
import pl.rzepisko.pilot.core.Protocol

/**
 * Zamienia surową odpowiedź SSDP na [DiscoveredDevice].
 *
 * Wydzielone z [SsdpDiscovery], bo to czysta funkcja na tekście — nie potrzebuje
 * kontekstu Androida ani gniazda, więc daje się przetestować zwykłym testem JVM
 * na prawdziwych odpowiedziach zebranych z telewizorów.
 */
internal object SsdpResponseParser {

    /**
     * Rozpoznaje protokół po nagłówkach odpowiedzi.
     *
     * Kolejność warunków ma znaczenie: telewizory Philips i TCL z systemem Roku
     * odpowiadają jednocześnie jako Roku i jako własna marka. Roku sprawdzamy pierwsze,
     * bo jego protokół działa na takim sprzęcie, a JointSpace/Tizen już nie.
     */
    fun parse(response: String, address: String): DiscoveredDevice? {
        val headers = response.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.take(idx).trim().uppercase() to line.drop(idx + 1).trim()
            }
            .toMap()

        val isSsdpResponse = response.startsWith("HTTP/1.1 200", ignoreCase = true) ||
            headers.containsKey("ST") ||
            headers.containsKey("NT")
        if (!isSsdpResponse) return null

        val searchTarget = (headers["ST"] ?: headers["NT"]).orEmpty()
        val server = headers["SERVER"].orEmpty()
        val haystack = "$searchTarget $server ${headers["USN"].orEmpty()}"

        val protocol = when {
            haystack.contains("roku", ignoreCase = true) -> Protocol.ROKU_ECP
            haystack.contains("samsung", ignoreCase = true) -> Protocol.SAMSUNG_TIZEN
            haystack.contains("webos", ignoreCase = true) ||
                haystack.contains("lge", ignoreCase = true) ||
                haystack.contains("lg elec", ignoreCase = true) -> Protocol.LG_WEBOS

            haystack.contains("sony", ignoreCase = true) ||
                haystack.contains("ircc", ignoreCase = true) -> Protocol.SONY_BRAVIA

            haystack.contains("philips", ignoreCase = true) -> Protocol.PHILIPS_JOINTSPACE
            else -> return null
        }

        // Samsung podaje czytelną nazwę we własnych nagłówkach. Pozostali producenci
        // trzymają ją dopiero w opisie XML spod adresu z LOCATION — do czasu, aż
        // użytkownik nada własną, podpisujemy urządzenie marką i adresem.
        val name = headers["FRIENDLYNAME.TIZEN.COM"]
            ?: headers["DEVICE-NAME"]
            ?: "${protocol.brands.first()} ($address)"

        return DiscoveredDevice(
            name = name,
            address = address,
            protocol = protocol,
            model = headers["MODELNAME.TIZEN.COM"] ?: server.takeIf { it.isNotBlank() },
            source = "SSDP",
        )
    }
}
