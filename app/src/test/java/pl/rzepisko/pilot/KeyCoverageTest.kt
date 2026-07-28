package pl.rzepisko.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.rzepisko.pilot.bluetooth.HidKeyMap
import pl.rzepisko.pilot.core.Protocol
import pl.rzepisko.pilot.core.RemoteKey
import pl.rzepisko.pilot.wifi.LgWebOsTransport
import pl.rzepisko.pilot.wifi.PhilipsJointSpaceTransport
import pl.rzepisko.pilot.wifi.RokuEcpTransport
import pl.rzepisko.pilot.wifi.SamsungTizenTransport
import pl.rzepisko.pilot.wifi.SonyBraviaTransport

/**
 * Kontrakt między sterownikami a UI: ekran pilota rysuje przyciski na podstawie
 * `supportedKeys`. Jeżeli któryś sterownik zgubi klawisz nawigacyjny, użytkownik
 * dostanie wyszarzony krzyżak — te testy wyłapią to zanim zrobi to on.
 */
class KeyCoverageTest {

    private val essentialKeys = setOf(
        RemoteKey.UP,
        RemoteKey.DOWN,
        RemoteKey.LEFT,
        RemoteKey.RIGHT,
        RemoteKey.OK,
        RemoteKey.BACK,
        RemoteKey.HOME,
        RemoteKey.VOLUME_UP,
        RemoteKey.VOLUME_DOWN,
    )

    private val keyMaps: Map<String, Set<RemoteKey>> = mapOf(
        "Samsung" to SamsungTizenTransport.KEY_MAP.keys,
        "LG" to (LgWebOsTransport.SSAP_URIS.keys + LgWebOsTransport.POINTER_BUTTONS.keys),
        "Sony" to SonyBraviaTransport.IRCC_CODES.keys,
        "Roku" to (RokuEcpTransport.ECP_KEYS.keys + RokuEcpTransport.APP_IDS.keys),
        "Philips" to PhilipsJointSpaceTransport.KEY_NAMES.keys,
        "Bluetooth HID" to HidKeyMap.CODES.keys,
    )

    @Test
    fun `kazdy sterownik obsluguje klawisze podstawowe`() {
        keyMaps.forEach { (name, keys) ->
            val missing = essentialKeys - keys
            assertTrue("$name nie obsługuje: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `kazdy sterownik potrafi wylaczyc telewizor`() {
        keyMaps.forEach { (name, keys) ->
            assertTrue(
                "$name nie ma przycisku zasilania",
                keys.any { it in setOf(RemoteKey.POWER, RemoteKey.POWER_OFF) },
            )
        }
    }

    @Test
    fun `sterowniki Wi-Fi obsluguja pelna klawiature numeryczna`() {
        listOf("Samsung", "LG", "Sony", "Philips").forEach { name ->
            val keys = keyMaps.getValue(name)
            assertTrue("$name nie ma pełnej klawiatury numerycznej", keys.containsAll(RemoteKey.digits))
        }
    }

    @Test
    fun `mapowania nie odwoluja sie do nieistniejacych klawiszy`() {
        val allKeys = RemoteKey.entries.toSet()
        keyMaps.forEach { (name, keys) ->
            assertTrue("$name ma klawisze spoza enuma", allKeys.containsAll(keys))
        }
    }

    @Test
    fun `cyfry maja spojne odwzorowanie na wartosci liczbowe`() {
        (0..9).forEach { digit ->
            assertEquals(digit, RemoteKey.forDigit(digit).digit)
        }
        assertEquals(null, RemoteKey.OK.digit)
        assertEquals(10, RemoteKey.digits.size)
    }

    @Test
    fun `kazdy protokol deklaruje przynajmniej jedna marke`() {
        Protocol.entries.forEach { protocol ->
            assertTrue("${protocol.name} nie ma przypisanej marki", protocol.brands.isNotEmpty())
        }
    }
}
