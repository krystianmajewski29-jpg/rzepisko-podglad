package pl.rzepisko.pilot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.rzepisko.pilot.core.RemoteKey

/**
 * Pojedynczy przycisk pilota.
 *
 * Cel dotykowy 64 dp zamiast wytycznych 48 dp: pilota obsługuje się kciukiem i zwykle
 * nie patrząc na ekran, więc przycisk musi dać się trafić „na czucie”.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 2.dp,
        modifier = modifier
            .size(64.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics { contentDescription?.let { description -> this.contentDescription = description } },
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
                label != null -> Text(
                    text = label,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (label.length > 3) 13.sp else 17.sp,
                )
            }
        }
    }
}

/**
 * Krzyżak nawigacyjny: cztery kierunki wokół OK.
 *
 * Zrobiony jako pięć osobnych przycisków w siatce, a nie jeden okrąg z detekcją kąta —
 * dzięki temu każdy kierunek ma stabilny cel dotykowy i własny opis dla TalkBacka.
 */
@Composable
fun DirectionalPad(
    onKey: (RemoteKey) -> Unit,
    supportedKeys: Set<RemoteKey>,
    modifier: Modifier = Modifier,
) {
    val enabled = { key: RemoteKey -> key in supportedKeys }

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier.aspectRatio(1f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RemoteButton(
                onClick = { onKey(RemoteKey.UP) },
                enabled = enabled(RemoteKey.UP),
                icon = RemoteIcons.Up,
                contentDescription = "W górę",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteButton(
                    onClick = { onKey(RemoteKey.LEFT) },
                    enabled = enabled(RemoteKey.LEFT),
                    icon = RemoteIcons.Left,
                    contentDescription = "W lewo",
                )
                RemoteButton(
                    onClick = { onKey(RemoteKey.OK) },
                    enabled = enabled(RemoteKey.OK),
                    label = "OK",
                    contentDescription = "Zatwierdź",
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
                RemoteButton(
                    onClick = { onKey(RemoteKey.RIGHT) },
                    enabled = enabled(RemoteKey.RIGHT),
                    icon = RemoteIcons.Right,
                    contentDescription = "W prawo",
                )
            }
            RemoteButton(
                onClick = { onKey(RemoteKey.DOWN) },
                enabled = enabled(RemoteKey.DOWN),
                icon = RemoteIcons.Down,
                contentDescription = "W dół",
            )
        }
    }
}

/** Pionowy kołyskowy przycisk (głośność, kanały) — plus na górze, minus na dole. */
@Composable
fun RockerControl(
    label: String,
    upKey: RemoteKey,
    downKey: RemoteKey,
    onKey: (RemoteKey) -> Unit,
    supportedKeys: Set<RemoteKey>,
    upIcon: ImageVector,
    downIcon: ImageVector,
    upDescription: String,
    downDescription: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(32.dp),
            )
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RemoteButton(
            onClick = { onKey(upKey) },
            enabled = upKey in supportedKeys,
            icon = upIcon,
            contentDescription = upDescription,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RemoteButton(
            onClick = { onKey(downKey) },
            enabled = downKey in supportedKeys,
            icon = downIcon,
            contentDescription = downDescription,
        )
    }
}
