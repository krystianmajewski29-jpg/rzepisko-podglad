package pl.rzepisko.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.rzepisko.pilot.bluetooth.HidCode
import pl.rzepisko.pilot.bluetooth.HidDescriptor
import pl.rzepisko.pilot.bluetooth.HidKeyMap
import pl.rzepisko.pilot.core.RemoteKey

class HidKeyMapTest {

    @Test
    fun `raport klawiatury ma osiem bajtow i kod na trzeciej pozycji`() {
        val report = HidKeyMap.buildKeyboardReport(usage = 0x52) // strzałka w górę

        assertEquals(HidDescriptor.KEYBOARD_REPORT_SIZE, report.size)
        assertEquals(0, report[0].toInt()) // brak modyfikatorów
        assertEquals(0, report[1].toInt()) // bajt rezerwowy
        assertEquals(0x52, report[2].toInt())
    }

    @Test
    fun `shift ustawia bit lewego modyfikatora`() {
        val report = HidKeyMap.buildKeyboardReport(usage = 0x04, withShift = true)

        assertEquals(0x02, report[0].toInt())
    }

    @Test
    fun `raport Consumer koduje usage jako little-endian`() {
        // AC Home = 0x223 → bajty 23 02
        val report = HidKeyMap.buildConsumerReport(0x223)

        assertEquals(HidDescriptor.CONSUMER_REPORT_SIZE, report.size)
        assertEquals(0x23, report[0].toInt() and 0xFF)
        assertEquals(0x02, report[1].toInt() and 0xFF)
    }

    @Test
    fun `puste raporty zwalniaja wszystkie klawisze`() {
        assertTrue(HidKeyMap.emptyKeyboardReport().all { it.toInt() == 0 })
        assertTrue(HidKeyMap.emptyConsumerReport().all { it.toInt() == 0 })
    }

    @Test
    fun `nawigacja idzie klawiatura a glosnosc strona Consumer`() {
        assertTrue(HidKeyMap.CODES[RemoteKey.UP] is HidCode.Keyboard)
        assertTrue(HidKeyMap.CODES[RemoteKey.OK] is HidCode.Keyboard)
        assertTrue(HidKeyMap.CODES[RemoteKey.VOLUME_UP] is HidCode.Consumer)
        assertTrue(HidKeyMap.CODES[RemoteKey.PLAY_PAUSE] is HidCode.Consumer)
    }

    @Test
    fun `wszystkie cyfry maja przypisany kod klawiatury`() {
        RemoteKey.digits.forEach { key ->
            assertNotNull("brak kodu dla $key", HidKeyMap.CODES[key])
        }
        assertEquals(HidCode.Keyboard(0x27), HidKeyMap.CODES[RemoteKey.NUM_0])
        assertEquals(HidCode.Keyboard(0x1E), HidKeyMap.CODES[RemoteKey.NUM_1])
        assertEquals(HidCode.Keyboard(0x26), HidKeyMap.CODES[RemoteKey.NUM_9])
    }

    @Test
    fun `zadne usage Consumer nie wychodzi poza zakres deskryptora`() {
        // Deskryptor deklaruje Logical Maximum 1023 — większa wartość nie zmieściłaby
        // się w zadeklarowanym zakresie i telewizor odrzuciłby raport.
        HidKeyMap.CODES.values.filterIsInstance<HidCode.Consumer>().forEach {
            assertTrue("usage ${it.usage} poza zakresem", it.usage in 0..1023)
        }
    }

    @Test
    fun `mapowanie znakow obsluguje litery cyfry i spacje`() {
        assertEquals(0x04 to false, HidKeyMap.keyboardUsageForChar('a'))
        assertEquals(0x04 to true, HidKeyMap.keyboardUsageForChar('A'))
        assertEquals(0x1E to false, HidKeyMap.keyboardUsageForChar('1'))
        assertEquals(0x27 to false, HidKeyMap.keyboardUsageForChar('0'))
        assertEquals(0x2C to false, HidKeyMap.keyboardUsageForChar(' '))
        assertNull(HidKeyMap.keyboardUsageForChar('ł'))
    }

    @Test
    fun `deskryptor deklaruje oba identyfikatory raportow`() {
        val bytes = HidDescriptor.BYTES.map { it.toInt() and 0xFF }
        val reportIdMarkers = bytes.windowed(2).filter { it[0] == 0x85 }.map { it[1] }

        assertEquals(
            listOf(HidDescriptor.REPORT_ID_KEYBOARD, HidDescriptor.REPORT_ID_CONSUMER),
            reportIdMarkers,
        )
    }
}
