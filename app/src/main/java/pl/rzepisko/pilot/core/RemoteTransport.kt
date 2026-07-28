package pl.rzepisko.pilot.core

import kotlinx.coroutines.flow.StateFlow

/** Stan połączenia z urządzeniem. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState

    /** Czekamy, aż użytkownik potwierdzi parowanie na ekranie TV / w systemie. */
    data class Pairing(val hint: String) : ConnectionState
    data object Connected : ConnectionState
    data class Failed(val reason: String, val cause: Throwable? = null) : ConnectionState
}

/** Błąd, który da się pokazać użytkownikowi po polsku. */
class RemoteException(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

/**
 * Sterownik jednego urządzenia. Implementacje żyją w `wifi/` i `bluetooth/`.
 *
 * Kontrakt:
 *  - [connect] jest idempotentne; wywołane na już połączonym transporcie nic nie robi.
 *  - [sendKey] może samo wywołać [connect], jeśli połączenie padło.
 *  - [close] zwalnia gniazda/sockety i przestawia [state] na [ConnectionState.Disconnected].
 *  - Wszystkie metody są bezpieczne do wołania z dowolnego wątku (są `suspend`).
 */
interface RemoteTransport : AutoCloseable {

    val device: RemoteDevice

    val state: StateFlow<ConnectionState>

    /** Klawisze, które ten transport potrafi wysłać. UI wyszarza resztę. */
    val supportedKeys: Set<RemoteKey>

    /**
     * Nawiązuje połączenie, w razie potrzeby przeprowadzając parowanie.
     *
     * @return urządzenie z uzupełnionymi danymi logowania (token/klucz), jeśli parowanie
     *   je wyprodukowało — wołający powinien to zapisać. W przeciwnym razie [device].
     */
    suspend fun connect(): RemoteDevice

    suspend fun sendKey(key: RemoteKey, action: KeyAction = KeyAction.CLICK)

    /** Wysyła tekst do pola wyszukiwania na TV. Domyślnie nieobsługiwane. */
    suspend fun sendText(text: String): Unit =
        throw RemoteException("To urządzenie nie obsługuje wpisywania tekstu")

    override fun close()
}
