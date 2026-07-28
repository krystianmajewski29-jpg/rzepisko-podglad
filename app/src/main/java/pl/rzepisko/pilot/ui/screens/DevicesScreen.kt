package pl.rzepisko.pilot.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.rzepisko.pilot.core.DiscoveredDevice
import pl.rzepisko.pilot.core.Protocol
import pl.rzepisko.pilot.core.RemoteDevice
import pl.rzepisko.pilot.core.TransportKind
import pl.rzepisko.pilot.ui.DevicesViewModel
import pl.rzepisko.pilot.ui.components.RemoteIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onDeviceChosen: (RemoteDevice) -> Unit,
    onBack: () -> Unit,
    viewModel: DevicesViewModel = viewModel(),
) {
    val context = LocalContext.current
    val saved by viewModel.savedDevices.collectAsState()
    val state by viewModel.uiState.collectAsState()
    var mode by remember { mutableStateOf(TransportKind.WIFI) }
    var showManualDialog by remember { mutableStateOf(false) }
    var deviceToDelete by remember { mutableStateOf<RemoteDevice?>(null) }

    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.refreshBluetoothDevices()
    }

    // Przy wejściu w tryb Bluetooth prosimy o zgodę dopiero wtedy, gdy jest potrzebna —
    // użytkownik korzystający wyłącznie z Wi-Fi nie powinien jej w ogóle widzieć.
    LaunchedEffect(mode) {
        when (mode) {
            TransportKind.WIFI -> viewModel.startWifiScan()
            TransportKind.BLUETOOTH -> {
                viewModel.stopScan()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    viewModel.refreshBluetoothDevices()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Urządzenia") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(RemoteIcons.Back, contentDescription = "Wróć")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == TransportKind.WIFI,
                        onClick = { mode = TransportKind.WIFI },
                        label = { Text("Wi-Fi") },
                        leadingIcon = { Icon(RemoteIcons.Wifi, contentDescription = null) },
                    )
                    FilterChip(
                        selected = mode == TransportKind.BLUETOOTH,
                        onClick = { mode = TransportKind.BLUETOOTH },
                        enabled = viewModel.bluetoothSupported,
                        label = { Text("Bluetooth") },
                        leadingIcon = { Icon(RemoteIcons.Bluetooth, contentDescription = null) },
                    )
                }
            }

            if (mode == TransportKind.BLUETOOTH && !viewModel.bluetoothSupported) {
                item {
                    InfoCard(
                        "Tryb Bluetooth wymaga Androida 9 lub nowszego — na tym telefonie " +
                            "dostępne jest tylko sterowanie przez Wi-Fi.",
                    )
                }
            }

            if (saved.isNotEmpty()) {
                item { SectionHeader("Zapisane piloty") }
                items(saved, key = { it.id }) { device ->
                    Card(onClick = { onDeviceChosen(device) }) {
                        ListItem(
                            headlineContent = { Text(device.name) },
                            supportingContent = {
                                Text("${device.protocol.displayName} · ${device.address}")
                            },
                            leadingContent = {
                                Icon(
                                    if (device.kind == TransportKind.BLUETOOTH) {
                                        RemoteIcons.Bluetooth
                                    } else {
                                        RemoteIcons.Wifi
                                    },
                                    contentDescription = null,
                                )
                            },
                            trailingContent = {
                                TextButton(onClick = { deviceToDelete = device }) { Text("Usuń") }
                            },
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(
                        if (mode == TransportKind.WIFI) "Znalezione w sieci" else "Sparowane przez Bluetooth",
                    )
                    if (state.scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(
                            onClick = {
                                if (mode == TransportKind.WIFI) {
                                    viewModel.startWifiScan()
                                } else {
                                    viewModel.refreshBluetoothDevices()
                                }
                            },
                        ) { Text("Szukaj ponownie") }
                    }
                }
            }

            val discovered = if (mode == TransportKind.WIFI) state.found else state.bluetoothDevices
            items(discovered, key = { "${it.address}|${it.protocol}" }) { found ->
                DiscoveredRow(found) { viewModel.add(found, onDeviceChosen) }
            }

            state.message?.let { message ->
                item { InfoCard(message) }
            }

            if (mode == TransportKind.BLUETOOTH) {
                item {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Otwórz ustawienia Bluetooth") }
                }
            } else {
                item {
                    OutlinedButton(
                        onClick = { showManualDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Dodaj ręcznie po adresie IP") }
                }
            }
        }
    }

    if (showManualDialog) {
        ManualDeviceDialog(
            onDismiss = { showManualDialog = false },
            onConfirm = { name, address, protocol, psk ->
                showManualDialog = false
                viewModel.addManually(name, address, protocol, psk, onDeviceChosen)
            },
        )
    }

    deviceToDelete?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToDelete = null },
            title = { Text("Usunąć pilot?") },
            text = { Text("„${device.name}” zniknie z listy. Ustawienia parowania trzeba będzie wykonać ponownie.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(device.id)
                        deviceToDelete = null
                    },
                ) { Text("Usuń") }
            },
            dismissButton = {
                TextButton(onClick = { deviceToDelete = null }) { Text("Anuluj") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun InfoCard(text: String) {
    Card {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveredRow(device: DiscoveredDevice, onAdd: () -> Unit) {
    Card(onClick = onAdd) {
        ListItem(
            headlineContent = { Text(device.name) },
            supportingContent = { Text("${device.protocol.displayName} · ${device.address}") },
            trailingContent = { Text("Dodaj", color = MaterialTheme.colorScheme.primary) },
        )
    }
}

@Composable
private fun ManualDeviceDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, address: String, protocol: Protocol, psk: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var psk by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(Protocol.SAMSUNG_TIZEN) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dodaj urządzenie") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nazwa (opcjonalna)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Adres IP, np. 192.168.1.20") },
                    singleLine = true,
                )
                Text("Protokół", style = MaterialTheme.typography.labelLarge)
                DevicesViewModel.wifiProtocols.forEach { candidate ->
                    FilterChip(
                        selected = protocol == candidate,
                        onClick = { protocol = candidate },
                        label = { Text(candidate.displayName) },
                    )
                }
                if (protocol == Protocol.SONY_BRAVIA) {
                    OutlinedTextField(
                        value = psk,
                        onValueChange = { psk = it },
                        label = { Text("Klucz PSK z telewizora") },
                        supportingText = {
                            Text("Ustawienia → Sieć → Ustawienia sieci domowej → IP Control")
                        },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = address.isNotBlank(),
                onClick = { onConfirm(name, address, protocol, psk.takeIf { it.isNotBlank() }) },
            ) { Text("Dodaj") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
