package pl.rzepisko.pilot.ui

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.rzepisko.pilot.PilotApp
import pl.rzepisko.pilot.core.ConnectionState
import pl.rzepisko.pilot.core.KeyAction
import pl.rzepisko.pilot.core.RemoteDevice
import pl.rzepisko.pilot.core.RemoteKey

class RemoteViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PilotApp
    private val repository = app.repository

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val activeDevice: StateFlow<RemoteDevice?> = repository.activeDevice
    val supportedKeys: StateFlow<Set<RemoteKey>> = repository.supportedKeys

    val savedDevices: StateFlow<List<RemoteDevice>> = repository.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** Wybiera pilot i łączy się z nim. */
    fun select(device: RemoteDevice) {
        viewModelScope.launch {
            _error.value = null
            runCatching { repository.select(device) }
                .onFailure { _error.value = (connectionState.value as? ConnectionState.Failed)?.reason }
        }
    }

    /** Przy wejściu na ekran pilota przywraca ostatnio używane urządzenie. */
    fun restoreLastUsed() {
        if (activeDevice.value != null) return
        viewModelScope.launch {
            val id = repository.activeDeviceId.first() ?: return@launch
            val device = repository.devices.first().firstOrNull { it.id == id } ?: return@launch
            select(device)
        }
    }

    fun press(key: RemoteKey, action: KeyAction = KeyAction.CLICK) {
        buzz()
        viewModelScope.launch {
            repository.press(key, action)?.let { _error.value = it }
        }
    }

    fun type(text: String) {
        viewModelScope.launch {
            repository.type(text)?.let { _error.value = it }
        }
    }

    fun reconnect() {
        val device = activeDevice.value ?: return
        select(device)
    }

    fun disconnect() {
        viewModelScope.launch { repository.disconnect() }
    }

    fun setHaptics(enabled: Boolean) {
        _hapticsEnabled.value = enabled
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Krótkie drgnięcie przy naciśnięciu. Bez tego pilot na szkle jest nieużywalny
     * bez patrzenia na ekran — a właśnie tak używa się pilota.
     */
    private fun buzz() {
        if (!_hapticsEnabled.value) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(12, 90))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(12)
        }
    }
}
