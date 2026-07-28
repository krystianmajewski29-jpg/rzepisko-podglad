package pl.rzepisko.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.rzepisko.pilot.core.Protocol
import pl.rzepisko.pilot.discovery.SsdpResponseParser

class SsdpResponseParserTest {

    @Test
    fun `rozpoznaje telewizor Samsung i wyciaga nazwe z naglowka Tizen`() {
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("ST: urn:samsung.com:device:RemoteControlReceiver:1\r\n")
            append("USN: uuid:0a1b2c3d::urn:samsung.com:device:RemoteControlReceiver:1\r\n")
            append("SERVER: SHP, UPnP/1.0, Samsung UPnP SDK/1.0\r\n")
            append("FriendlyName.tizen.com: Salon TV\r\n")
            append("ModelName.tizen.com: UE55TU8502\r\n")
            append("\r\n")
        }

        val device = SsdpResponseParser.parse(response, "192.168.1.30")

        assertEquals(Protocol.SAMSUNG_TIZEN, device?.protocol)
        assertEquals("Salon TV", device?.name)
        assertEquals("UE55TU8502", device?.model)
        assertEquals("192.168.1.30", device?.address)
    }

    @Test
    fun `rozpoznaje LG po identyfikatorze uslugi webOS`() {
        val response = "HTTP/1.1 200 OK\r\n" +
            "ST: urn:lge-com:service:webos-second-screen:1\r\n" +
            "USN: uuid:aaaa-bbbb\r\n\r\n"

        val device = SsdpResponseParser.parse(response, "192.168.1.44")

        assertEquals(Protocol.LG_WEBOS, device?.protocol)
        // Bez nazwy w nagłówkach podpisujemy urządzenie marką i adresem.
        assertTrue(device?.name?.contains("192.168.1.44") == true)
    }

    @Test
    fun `Roku wygrywa z Philipsem na telewizorze Philips Roku TV`() {
        val response = "HTTP/1.1 200 OK\r\n" +
            "ST: roku:ecp\r\n" +
            "SERVER: Roku UPnP/1.0 MiniUPnPd/1.4 Philips\r\n\r\n"

        assertEquals(Protocol.ROKU_ECP, SsdpResponseParser.parse(response, "192.168.1.50")?.protocol)
    }

    @Test
    fun `rozpoznaje Sony po uslugie IRCC`() {
        val response = "HTTP/1.1 200 OK\r\n" +
            "ST: urn:schemas-sony-com:service:IRCC:1\r\n\r\n"

        assertEquals(Protocol.SONY_BRAVIA, SsdpResponseParser.parse(response, "10.0.0.8")?.protocol)
    }

    @Test
    fun `ignoruje urzadzenia UPnP innych producentow`() {
        val response = "HTTP/1.1 200 OK\r\n" +
            "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
            "SERVER: AVM FRITZ!Box UPnP/1.0\r\n\r\n"

        assertNull(SsdpResponseParser.parse(response, "192.168.1.1"))
    }

    @Test
    fun `ignoruje smieci nie bedace odpowiedzia SSDP`() {
        assertNull(SsdpResponseParser.parse("cokolwiek innego", "192.168.1.9"))
    }

    @Test
    fun `czyta powiadomienia NOTIFY z naglowkiem NT`() {
        val response = "NOTIFY * HTTP/1.1\r\n" +
            "NT: urn:samsung.com:device:RemoteControlReceiver:1\r\n" +
            "NTS: ssdp:alive\r\n\r\n"

        assertEquals(
            Protocol.SAMSUNG_TIZEN,
            SsdpResponseParser.parse(response, "192.168.1.30")?.protocol,
        )
    }
}
