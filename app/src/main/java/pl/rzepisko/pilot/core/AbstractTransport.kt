package pl.rzepisko.pilot.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wspólna mechanika dla wszystkich sterowników: publikowanie stanu i serializacja
 * połączeń, żeby dwa równoległe [sendKey] nie próbowały łączyć się jednocześnie.
 */
abstract class AbstractTransport(initialDevice: RemoteDevice) : RemoteTransport {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val connectMutex = Mutex()

    @Volatile
    protected var currentDevice: RemoteDevice = initialDevice

    override val device: RemoteDevice get() = currentDevice

    protected fun setState(newState: ConnectionState) {
        _state.value = newState
    }

    /** Czy [doConnect] trzeba w ogóle wołać. Nadpisz, jeśli transport jest bezstanowy. */
    protected open val isConnected: Boolean
        get() = _state.value is ConnectionState.Connected

    /** Właściwe nawiązanie połączenia. Wołane pod muteksem, więc nie musi być reentrantne. */
    protected abstract suspend fun doConnect(): RemoteDevice

    final override suspend fun connect(): RemoteDevice = connectMutex.withLock {
        if (isConnected) return@withLock currentDevice
        setState(ConnectionState.Connecting)
        try {
            currentDevice = doConnect()
            setState(ConnectionState.Connected)
            currentDevice
        } catch (e: RemoteException) {
            setState(ConnectionState.Failed(e.userMessage, e))
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName ?: "nieznany błąd"
            setState(ConnectionState.Failed(message, e))
            throw RemoteException("Nie udało się połączyć: $message", e)
        }
    }

    /** Zapewnia połączenie przed wysłaniem klawisza. */
    protected suspend fun ensureConnected() {
        if (!isConnected) connect()
    }
}
