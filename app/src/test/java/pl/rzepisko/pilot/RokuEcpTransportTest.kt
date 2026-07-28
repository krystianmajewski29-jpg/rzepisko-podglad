package pl.rzepisko.pilot

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.rzepisko.pilot.core.KeyAction
import pl.rzepisko.pilot.core.Protocol
import pl.rzepisko.pilot.core.RemoteDevice
import pl.rzepisko.pilot.core.RemoteException
import pl.rzepisko.pilot.core.RemoteKey
import pl.rzepisko.pilot.wifi.RokuEcpTransport

/**
 * Roku jest jedynym sterownikiem, który da się w całości przetestować lokalnie:
 * to zwykły HTTP, bez parowania i bez WebSocketów. Sprawdzamy, że na drut idą
 * dokładnie te ścieżki, których oczekuje ECP.
 */
class RokuEcpTransportTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun transport(): RokuEcpTransport = RokuEcpTransport(
        RemoteDevice(
            id = "test",
            name = "Roku testowe",
            protocol = Protocol.ROKU_ECP,
            address = "127.0.0.1:${server.port}",
        ),
    )

    @Test
    fun `polaczenie odpytuje device-info`() = runTest {
        server.enqueue(MockResponse().setBody("<device-info/>"))

        transport().connect()

        assertEquals("/query/device-info", server.takeRequest().path)
    }

    @Test
    fun `klikniecie wysyla POST na keypress`() = runTest {
        server.enqueue(MockResponse().setBody("<device-info/>"))
        server.enqueue(MockResponse())

        val transport = transport()
        transport.connect()
        transport.sendKey(RemoteKey.OK)

        server.takeRequest() // device-info
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/keypress/Select", request.path)
    }

    @Test
    fun `przytrzymanie i puszczenie uzywaja keydown i keyup`() = runTest {
        server.enqueue(MockResponse().setBody("<device-info/>"))
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())

        val transport = transport()
        transport.connect()
        transport.sendKey(RemoteKey.RIGHT, KeyAction.PRESS)
        transport.sendKey(RemoteKey.RIGHT, KeyAction.RELEASE)

        server.takeRequest()
        assertEquals("/keydown/Right", server.takeRequest().path)
        assertEquals("/keyup/Right", server.takeRequest().path)
    }

    @Test
    fun `przycisk aplikacji uruchamia kanal zamiast wysylac klawisz`() = runTest {
        server.enqueue(MockResponse().setBody("<device-info/>"))
        server.enqueue(MockResponse())

        val transport = transport()
        transport.connect()
        transport.sendKey(RemoteKey.APP_NETFLIX)

        server.takeRequest()
        assertEquals("/launch/12", server.takeRequest().path)
    }

    @Test
    fun `tekst leci znak po znaku jako Lit_`() = runTest {
        server.enqueue(MockResponse().setBody("<device-info/>"))
        repeat(3) { server.enqueue(MockResponse()) }

        val transport = transport()
        transport.connect()
        transport.sendText("abc")

        server.takeRequest()
        assertEquals("/keypress/Lit_a", server.takeRequest().path)
        assertEquals("/keypress/Lit_b", server.takeRequest().path)
        assertEquals("/keypress/Lit_c", server.takeRequest().path)
    }

    @Test
    fun `blad HTTP zamienia sie w czytelny komunikat`() = runTest {
        server.enqueue(MockResponse().setBody("<device-info/>"))
        server.enqueue(MockResponse().setResponseCode(403))

        val transport = transport()
        transport.connect()

        val error = assertThrows(RemoteException::class.java) {
            kotlinx.coroutines.runBlocking { transport.sendKey(RemoteKey.HOME) }
        }
        assertTrue(error.userMessage.contains("403"))
    }

    @Test
    fun `nieobslugiwany klawisz nie generuje ruchu sieciowego`() = runTest {
        server.enqueue(MockResponse().setBody("<device-info/>"))

        val transport = transport()
        transport.connect()

        assertThrows(RemoteException::class.java) {
            kotlinx.coroutines.runBlocking { transport.sendKey(RemoteKey.RED) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `odmawia polaczenia z adresem spoza sieci lokalnej`() = runTest {
        val transport = RokuEcpTransport(
            RemoteDevice(
                id = "test",
                name = "Zdalne",
                protocol = Protocol.ROKU_ECP,
                address = "93.184.216.34",
            ),
        )

        val error = assertThrows(RemoteException::class.java) {
            kotlinx.coroutines.runBlocking { transport.connect() }
        }
        assertTrue(error.userMessage.contains("sieci lokalnej"))
    }
}
