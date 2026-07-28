package pl.rzepisko.pilot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.rzepisko.pilot.PilotApp
import pl.rzepisko.pilot.core.Credentials
import pl.rzepisko.pilot.core.DiscoveredDevice
import pl.rzepisko.pilot.core.Protocol
import pl.rzepisko.pilot.core.RemoteDevice
import pl.rzepisko.pilot.core.TransportKind

data class DevicesUiState(
    val scanning: Boolean = false,
    val found: List<DiscoveredDevice> = emptyList(),
    val bluetoothDevices: List<DiscoveredDevice> = emptyList(),
    val message: String? = null,
)

class DevicesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PilotApp
    private val repository = app.repository

    val savedDevices: StateFlow<List<RemoteDevice>> = repository.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    val bluetoothSupported: Boolean get() = app.bluetoothDiscovery.isSupported

    fun startWifiScan() {
        if (_uiState.value.scanning) return
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(scanning = true, found = emptyList(), message = null)
        scanJob = viewModelScope.launch {
            app.ssdpDiscovery.discover()
                .onCompletion {
                    _uiState.value = _uiState.value.copy(
                        scanning = false,
                        message = if (_uiState.value.found.isEmpty()) {
                            "Nie znaleziono urządzeń. Sprawdź, czy telewizor jest włączony " +
                                "i w tej samej sieci Wi-Fi, albo dodaj go ręcznie po adresie IP."
                        } else {
                            null
                        },
                    )
                }
                .collect { device ->
                    _uiState.value = _uiState.value.copy(found = _uiState.value.found + device)
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.value = _uiState.value.copy(scanning = false)
    }

    fun refreshBluetoothDevices() {
        val devices = runCatching { app.bluetoothDiscovery.bondedDevices() }.getOrDefault(emptyList())
        _uiState.value = _uiState.value.copy(
            bluetoothDevices = devices,
            message = if (devices.isEmpty() && app.bluetoothDiscovery.isSupported) {
                "Brak sparowanych urządzeń. Sparuj telewizor w ustawieniach Bluetooth Androida, " +
                    "a potem wróć tutaj."
            } else {
                null
            },
        )
    }

    fun add(discovered: DiscoveredDevice, onAdded: (RemoteDevice) -> Unit = {}) {
        viewModelScope.launch {
            onAdded(repository.addDiscovered(discovered))
        }
    }

    /** Ręczne dodanie po adresie IP — ratunek, gdy SSDP nie przechodzi przez router. */
    fun addManually(
        name: String,
        address: String,
        protocol: Protocol,
        sonyPsk: String? = null,
        onAdded: (RemoteDevice) -> Unit = {},
    ) {
        viewModelScope.launch {
            var device = RemoteDevice(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { protocol.brands.first() },
                protocol = protocol,
                address = address.trim(),
            )
            if (protocol == Protocol.SONY_BRAVIA && !sonyPsk.isNullOrBlank()) {
                device = device.withCredential(Credentials.SONY_PSK, sonyPsk.trim())
            }
            repository.save(device)
            onAdded(device)
        }
    }

    fun delete(deviceId: String) {
        viewModelScope.launch { repository.delete(deviceId) }
    }

    fun rename(deviceId: String, newName: String) {
        viewModelScope.launch { repository.rename(deviceId, newName) }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    companion object {
        val wifiProtocols: List<Protocol> = Protocol.byKind(TransportKind.WIFI)
    }
}
