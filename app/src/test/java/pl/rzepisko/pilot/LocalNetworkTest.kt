package pl.rzepisko.pilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.rzepisko.pilot.wifi.LocalNetwork

/**
 * [LocalNetwork.isPrivateAddress] pilnuje, żeby pilot nie łączył się poza sieć domową —
 * a to od niego zależy, czy wolno użyć klienta HTTP z wyłączoną weryfikacją certyfikatu.
 */
class LocalNetworkTest {

    @Test
    fun `akceptuje adresy z sieci prywatnych`() {
        listOf(
            "192.168.1.30",
            "192.168.0.1",
            "10.0.0.8",
            "10.255.255.254",
            "172.16.0.1",
            "172.31.255.254",
            "169.254.10.10",
            "127.0.0.1",
        ).forEach { address ->
            assertTrue("$address powinien być uznany za lokalny", LocalNetwork.isPrivateAddress(address))
        }
    }

    @Test
    fun `odrzuca adresy publiczne`() {
        listOf(
            "8.8.8.8",
            "1.1.1.1",
            "172.32.0.1", // tuż poza blokiem 172.16-31
            "172.15.255.255",
            "11.0.0.1",
            "126.0.0.1",
        ).forEach { address ->
            assertFalse("$address nie powinien być uznany za lokalny", LocalNetwork.isPrivateAddress(address))
        }
    }

    @Test
    fun `odrzuca smieci i nazwy hostow`() {
        listOf("", "telewizor.local", "192.168.1", "192.168.1.256", "999.1.1.1", "::1")
            .forEach { assertFalse(it, LocalNetwork.isPrivateAddress(it)) }
    }

    @Test
    fun `pomija numer portu`() {
        assertTrue(LocalNetwork.isPrivateAddress("192.168.1.30:8002"))
    }
}
