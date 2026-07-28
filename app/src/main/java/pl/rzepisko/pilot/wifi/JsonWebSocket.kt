package pl.rzepisko.pilot.wifi

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import pl.rzepisko.pilot.core.RemoteException

internal val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/**
 * Cienka warstwa nad WebSocketem OkHttpa: otwieranie jako `suspend`, ramki tekstowe
 * wystawione jako [SharedFlow] obiektów JSON.
 *
 * Samsung i LG używają tego samego wzorca "wyślij żądanie, poczekaj na ramkę pasującą
 * do predykatu", więc siedzi to tutaj zamiast być kopiowane w obu sterownikach.
 */
class JsonWebSocket private constructor(
    private val socket: WebSocket,
    private val _messages: MutableSharedFlow<JsonObject>,
    private val failure: () -> Throwable?,
) : AutoCloseable {

    val messages: SharedFlow<JsonObject> = _messages

    fun sendRaw(text: String) {
        if (!socket.send(text)) {
            throw RemoteException("Połączenie z urządzeniem zostało przerwane", failure())
        }
    }

    fun sendJson(payload: JsonObject) = sendRaw(payload.toString())

    /**
     * Czeka na pierwszą ramkę spełniającą [predicate].
     *
     * @throws RemoteException gdy upłynie [timeoutMillis] albo gniazdo padnie.
     */
    suspend fun awaitMessage(
        timeoutMillis: Long,
        onTimeout: String = "Urządzenie nie odpowiedziało na czas",
        predicate: (JsonObject) -> Boolean,
    ): JsonObject = withTimeoutOrNull(timeoutMillis) {
        _messages.first(predicate)
    } ?: throw RemoteException(onTimeout, failure())

    override fun close() {
        socket.close(NORMAL_CLOSURE, null)
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000

        /**
         * Otwiera połączenie i wraca dopiero po handshake'u (albo po błędzie).
         *
         * Bufor `messages` ma pojemność 64 i `replay = 8`: telewizory potrafią wysłać
         * powitanie zanim wołający zdąży zacząć nasłuchiwać, a bez replaya taki
         * pakiet by przepadł i parowanie wisiałoby do timeoutu.
         */
        suspend fun open(
            client: OkHttpClient,
            request: Request,
            connectTimeoutMillis: Long = 10_000,
        ): JsonWebSocket {
            val messages = MutableSharedFlow<JsonObject>(
                replay = 8,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val lastFailure = AtomicReference<Throwable?>(null)

            val opened = withTimeoutOrNull(connectTimeoutMillis) {
                suspendCancellableCoroutine<WebSocket?> { cont ->
                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (cont.isActive) cont.resume(webSocket)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = runCatching {
                                LenientJson.parseToJsonElement(text) as? JsonObject
                            }.getOrNull() ?: return
                            messages.tryEmit(obj)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            lastFailure.set(t)
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    val ws = client.newWebSocket(request, listener)
                    cont.invokeOnCancellation { ws.cancel() }
                }
            }

            val socket = opened ?: throw RemoteException(
                lastFailure.get()
                    ?.let { "Nie udało się otworzyć połączenia: ${it.message ?: it::class.simpleName}" }
                    ?: "Urządzenie nie odpowiada — sprawdź, czy jest włączone i w tej samej sieci",
                lastFailure.get(),
            )
            return JsonWebSocket(socket, messages) { lastFailure.get() }
        }
    }
}
