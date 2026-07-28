package pl.rzepisko.pilot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import pl.rzepisko.pilot.core.RemoteDevice

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "piloty")

/**
 * Trwała lista zapisanych pilotów.
 *
 * Wszystko siedzi w jednym kluczu jako JSON — lista ma realistycznie kilka pozycji,
 * więc osobne klucze na urządzenie nic by nie dały poza komplikacją odczytu.
 */
class DeviceStore(context: Context) {

    private val store = context.applicationContext.dataStore

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val devices: Flow<List<RemoteDevice>> = store.data.map { prefs ->
        val raw = prefs[DEVICES_KEY] ?: return@map emptyList()
        // Uszkodzony wpis nie może uwalić całej aplikacji przy starcie — wolimy
        // pustą listę i możliwość dodania urządzenia od razu.
        runCatching { json.decodeFromString<List<RemoteDevice>>(raw) }.getOrDefault(emptyList())
    }

    val activeDeviceId: Flow<String?> = store.data.map { it[ACTIVE_KEY] }

    suspend fun save(device: RemoteDevice) = mutate { current ->
        val without = current.filterNot { it.id == device.id }
        without + device
    }

    suspend fun delete(deviceId: String) {
        mutate { current -> current.filterNot { it.id == deviceId } }
        store.edit { prefs ->
            if (prefs[ACTIVE_KEY] == deviceId) prefs.remove(ACTIVE_KEY)
        }
    }

    suspend fun rename(deviceId: String, newName: String) = mutate { current ->
        current.map { if (it.id == deviceId) it.copy(name = newName) else it }
    }

    suspend fun setActive(deviceId: String) {
        store.edit { it[ACTIVE_KEY] = deviceId }
    }

    private suspend fun mutate(transform: (List<RemoteDevice>) -> List<RemoteDevice>) {
        store.edit { prefs ->
            val current = prefs[DEVICES_KEY]
                ?.let { runCatching { json.decodeFromString<List<RemoteDevice>>(it) }.getOrNull() }
                .orEmpty()
            prefs[DEVICES_KEY] = json.encodeToString(transform(current))
        }
    }

    private companion object {
        val DEVICES_KEY = stringPreferencesKey("devices_json")
        val ACTIVE_KEY = stringPreferencesKey("active_device_id")
    }
}
