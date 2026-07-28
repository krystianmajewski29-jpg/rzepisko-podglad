package pl.rzepisko.pilot.data

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.rzepisko.pilot.core.ConnectionState
import pl.rzepisko.pilot.core.DiscoveredDevice
import pl.rzepisko.pilot.core.KeyAction
import pl.rzepisko.pilot.core.RemoteDevice
import pl.rzepisko.pilot.core.RemoteKey
import pl.rzepisko.pilot.core.RemoteTransport

/**
 * Jedno źródło prawdy dla warstwy UI: zapisane piloty i połączenie z aktywnym.
 *
 * Trzyma dokładnie jeden otwarty transport. Przełączenie na inny pilot zamyka
 * poprzedni — dwa równoległe połączenia z telewizorami nie są nikomu potrzebne,
 * a zapominanie o zamknięciu gniazd kończy się wyciekami i zablokowanym HID-em.
 */
class RemoteRepository(
    context: Context,
    private val store: DeviceStore = DeviceStore(context),
    private val factory: TransportFactory = TransportFactory(context),
) {

    val devices: Flow<List<RemoteDevice>> = store.devices
    val activeDeviceId: Flow<String?> = store.activeDeviceId

    private val transportMutex = Mutex()
    private var transport: RemoteTransport? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _activeDevice = MutableStateFlow<RemoteDevice?>(null)
    val activeDevice: StateFlow<RemoteDevice?> = _activeDevice.asStateFlow()

    private val _supportedKeys = MutableStateFlow<Set<RemoteKey>>(emptySet())
    val supportedKeys: StateFlow<Set<RemoteKey>> = _supportedKeys.asStateFlow()

    suspend fun addDiscovered(discovered: DiscoveredDevice): RemoteDevice {
        val device = discovered.toRemoteDevice(id = UUID.randomUUID().toString())
        store.save(device)
        return device
    }

    suspend fun save(device: RemoteDevice) = store.save(device)

    suspend fun rename(deviceId: String, newName: String) = store.rename(deviceId, newName)

    suspend fun delete(deviceId: String) {
        if (_activeDevice.value?.id == deviceId) disconnect()
        store.delete(deviceId)
    }

    /**
     * Ustawia aktywny pilot i nawiązuje połączenie.
     *
     * Jeżeli parowanie zwróciło token / klucz, zapisujemy go od razu — inaczej przy
     * następnym uruchomieniu telewizor znowu pytałby o zgodę.
     */
    suspend fun select(device: RemoteDevice) = transportMutex.withLock {
        if (_activeDevice.value?.id != device.id) {
            transport?.runCatching { close() }
            transport = null
        }
        _activeDevice.value = device
        store.setActive(device.id)

        val active = transport ?: factory.create(device).also { transport = it }
        _supportedKeys.value = active.supportedKeys
        _connectionState.value = ConnectionState.Connecting

        try {
            val updated = active.connect()
            _connectionState.value = ConnectionState.Connected
            if (updated != device) {
                store.save(updated)
                _activeDevice.value = updated
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed(
                (e as? pl.rzepisko.pilot.core.RemoteException)?.userMessage
                    ?: e.message
                    ?: "Nie udało się połączyć",
                e,
            )
            throw e
        }
    }

    /** Zwraca `null` przy powodzeniu albo komunikat błędu do pokazania użytkownikowi. */
    suspend fun press(key: RemoteKey, action: KeyAction = KeyAction.CLICK): String? {
        val active = transport ?: return "Najpierw wybierz pilot"
        return try {
            active.sendKey(key, action)
            _connectionState.value = ConnectionState.Connected
            null
        } catch (e: pl.rzepisko.pilot.core.RemoteException) {
            _connectionState.value = ConnectionState.Failed(e.userMessage, e)
            e.userMessage
        } catch (e: Exception) {
            val message = e.message ?: "Nie udało się wysłać polecenia"
            _connectionState.value = ConnectionState.Failed(message, e)
            message
        }
    }

    suspend fun type(text: String): String? {
        val active = transport ?: return "Najpierw wybierz pilot"
        return try {
            active.sendText(text)
            null
        } catch (e: pl.rzepisko.pilot.core.RemoteException) {
            e.userMessage
        } catch (e: Exception) {
            e.message ?: "Nie udało się wysłać tekstu"
        }
    }

    suspend fun disconnect() = transportMutex.withLock {
        transport?.runCatching { close() }
        transport = null
        _connectionState.value = ConnectionState.Disconnected
        _activeDevice.value = null
        _supportedKeys.value = emptySet()
    }
}
