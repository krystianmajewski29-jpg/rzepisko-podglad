package pl.rzepisko.pilot.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.rzepisko.pilot.core.ConnectionState
import pl.rzepisko.pilot.core.RemoteKey
import pl.rzepisko.pilot.ui.RemoteViewModel
import pl.rzepisko.pilot.ui.components.DirectionalPad
import pl.rzepisko.pilot.ui.components.RemoteButton
import pl.rzepisko.pilot.ui.components.RemoteIcons
import pl.rzepisko.pilot.ui.components.RockerControl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    onOpenDevices: () -> Unit,
    viewModel: RemoteViewModel = viewModel(),
) {
    val device by viewModel.activeDevice.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val supported by viewModel.supportedKeys.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.restoreLastUsed() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = device?.name ?: "Brak wybranego pilota",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        ConnectionLabel(connection, device?.protocol?.displayName)
                    }
                },
                actions = {
                    if (connection is ConnectionState.Failed) {
                        IconButton(onClick = viewModel::reconnect) {
                            Icon(RemoteIcons.Wifi, contentDescription = "Połącz ponownie")
                        }
                    }
                    IconButton(onClick = onOpenDevices) {
                        Icon(RemoteIcons.Tv, contentDescription = "Wybierz urządzenie")
                    }
                },
            )
        },
    ) { padding ->
        if (device == null) {
            EmptyRemoteState(onOpenDevices, Modifier.padding(padding))
            return@Scaffold
        }

        RemoteControlLayout(
            supported = supported,
            enabled = connection !is ConnectionState.Connecting,
            onKey = viewModel::press,
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ConnectionLabel(state: ConnectionState, protocolName: String?) {
    val (text, color) = when (state) {
        is ConnectionState.Connected -> "Połączono${protocolName?.let { " · $it" }.orEmpty()}" to
            MaterialTheme.colorScheme.primary

        is ConnectionState.Connecting -> "Łączę…" to MaterialTheme.colorScheme.onSurfaceVariant
        is ConnectionState.Pairing -> state.hint to MaterialTheme.colorScheme.tertiary
        is ConnectionState.Failed -> state.reason to MaterialTheme.colorScheme.error
        ConnectionState.Disconnected -> "Rozłączono" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2)
}

@Composable
private fun EmptyRemoteState(onOpenDevices: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(RemoteIcons.Tv, contentDescription = null, modifier = Modifier.size(64.dp))
        Text("Nie masz jeszcze dodanego pilota", style = MaterialTheme.typography.titleMedium)
        Text(
            "Wyszukaj telewizor w sieci Wi-Fi albo wybierz urządzenie sparowane przez Bluetooth.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onOpenDevices) { Text("Dodaj urządzenie") }
    }
}

/** Sam układ przycisków — wydzielony, żeby dało się go podejrzeć w Preview bez ViewModelu. */
@Composable
fun RemoteControlLayout(
    supported: Set<RemoteKey>,
    enabled: Boolean,
    onKey: (RemoteKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    var numpadVisible by remember { mutableStateOf(false) }
    val press: (RemoteKey) -> Unit = { if (enabled) onKey(it) }
    fun has(key: RemoteKey) = enabled && key in supported

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Górny rząd: zasilanie osobno po lewej, bo pomyłkowe wyłączenie telewizora
        // jest najbardziej irytującym błędem, jaki może zrobić pilot.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteButton(
                onClick = { press(RemoteKey.POWER) },
                enabled = has(RemoteKey.POWER),
                icon = RemoteIcons.Power,
                contentDescription = "Włącz lub wyłącz",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            RemoteButton(
                onClick = { press(RemoteKey.SOURCE) },
                enabled = has(RemoteKey.SOURCE),
                label = "AV",
                contentDescription = "Źródło sygnału",
            )
            RemoteButton(
                onClick = { press(RemoteKey.MUTE) },
                enabled = has(RemoteKey.MUTE),
                icon = RemoteIcons.VolumeOff,
                contentDescription = "Wycisz",
            )
        }

        // Krzyżak z kołyskami po bokach — układ jak w fizycznym pilocie.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RockerControl(
                label = "Głos.",
                upKey = RemoteKey.VOLUME_UP,
                downKey = RemoteKey.VOLUME_DOWN,
                onKey = press,
                supportedKeys = if (enabled) supported else emptySet(),
                upIcon = RemoteIcons.Plus,
                downIcon = RemoteIcons.Minus,
                upDescription = "Głośniej",
                downDescription = "Ciszej",
            )
            DirectionalPad(
                onKey = press,
                supportedKeys = if (enabled) supported else emptySet(),
                modifier = Modifier.weight(1f),
            )
            RockerControl(
                label = "Kanał",
                upKey = RemoteKey.CHANNEL_UP,
                downKey = RemoteKey.CHANNEL_DOWN,
                onKey = press,
                supportedKeys = if (enabled) supported else emptySet(),
                upIcon = RemoteIcons.Up,
                downIcon = RemoteIcons.Down,
                upDescription = "Kanał w górę",
                downDescription = "Kanał w dół",
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RemoteButton(
                onClick = { press(RemoteKey.BACK) },
                enabled = has(RemoteKey.BACK),
                icon = RemoteIcons.Back,
                contentDescription = "Wstecz",
            )
            RemoteButton(
                onClick = { press(RemoteKey.HOME) },
                enabled = has(RemoteKey.HOME),
                icon = RemoteIcons.Home,
                contentDescription = "Ekran główny",
            )
            RemoteButton(
                onClick = { press(RemoteKey.MENU) },
                enabled = has(RemoteKey.MENU),
                icon = RemoteIcons.Menu,
                contentDescription = "Menu",
            )
            RemoteButton(
                onClick = { press(RemoteKey.INFO) },
                enabled = has(RemoteKey.INFO),
                icon = RemoteIcons.Info,
                contentDescription = "Informacje",
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RemoteButton(
                onClick = { press(RemoteKey.REWIND) },
                enabled = has(RemoteKey.REWIND),
                icon = RemoteIcons.Rewind,
                contentDescription = "Przewiń wstecz",
            )
            RemoteButton(
                onClick = { press(RemoteKey.PLAY) },
                enabled = has(RemoteKey.PLAY),
                icon = RemoteIcons.Play,
                contentDescription = "Odtwórz",
            )
            RemoteButton(
                onClick = { press(RemoteKey.PAUSE) },
                enabled = has(RemoteKey.PAUSE),
                icon = RemoteIcons.Pause,
                contentDescription = "Pauza",
            )
            RemoteButton(
                onClick = { press(RemoteKey.STOP) },
                enabled = has(RemoteKey.STOP),
                icon = RemoteIcons.Stop,
                contentDescription = "Zatrzymaj",
            )
            RemoteButton(
                onClick = { press(RemoteKey.FAST_FORWARD) },
                enabled = has(RemoteKey.FAST_FORWARD),
                icon = RemoteIcons.Forward,
                contentDescription = "Przewiń do przodu",
            )
        }

        ColorButtonRow(supported = if (enabled) supported else emptySet(), onKey = press)

        TextButton(onClick = { numpadVisible = !numpadVisible }) {
            Text(if (numpadVisible) "Ukryj klawiaturę" else "Pokaż klawiaturę numeryczną")
        }

        AnimatedVisibility(visible = numpadVisible) {
            NumericKeypad(supported = if (enabled) supported else emptySet(), onKey = press)
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Kolorowe przyciski telegazety. Pokazujemy je tylko wtedy, gdy urządzenie je zna —
 * pilot Bluetooth HID ich nie ma i pusty rząd wyszarzonych kółek tylko myliłby.
 */
@Composable
private fun ColorButtonRow(supported: Set<RemoteKey>, onKey: (RemoteKey) -> Unit) {
    val colorKeys = listOf(
        RemoteKey.RED to Color(0xFFD32F2F),
        RemoteKey.GREEN to Color(0xFF2E7D32),
        RemoteKey.YELLOW to Color(0xFFF9A825),
        RemoteKey.BLUE to Color(0xFF1565C0),
    )
    if (colorKeys.none { it.first in supported }) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        colorKeys.forEach { (key, color) ->
            RemoteButton(
                onClick = { onKey(key) },
                enabled = key in supported,
                label = "",
                contentDescription = "Przycisk ${key.name.lowercase()}",
                containerColor = color,
                contentColor = Color.White,
                shape = RoundedCornerShape(18.dp),
            )
        }
    }
}

@Composable
private fun NumericKeypad(supported: Set<RemoteKey>, onKey: (RemoteKey) -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            listOf(1..3, 4..6, 7..9).forEach { range ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    range.forEach { digit ->
                        val key = RemoteKey.forDigit(digit)
                        RemoteButton(
                            onClick = { onKey(key) },
                            enabled = key in supported,
                            label = digit.toString(),
                            contentDescription = "Cyfra $digit",
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(64.dp))
                RemoteButton(
                    onClick = { onKey(RemoteKey.NUM_0) },
                    enabled = RemoteKey.NUM_0 in supported,
                    label = "0",
                    contentDescription = "Cyfra 0",
                )
                Box(Modifier.size(64.dp))
            }
        }
    }
}
