package pl.rzepisko.pilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import pl.rzepisko.pilot.ui.DevicesViewModel
import pl.rzepisko.pilot.ui.RemoteViewModel
import pl.rzepisko.pilot.ui.screens.DevicesScreen
import pl.rzepisko.pilot.ui.screens.RemoteScreen
import pl.rzepisko.pilot.ui.theme.PilotTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PilotTheme {
                PilotNavigation()
            }
        }
    }
}

private object Routes {
    const val REMOTE = "pilot"
    const val DEVICES = "urzadzenia"
}

@Composable
private fun PilotNavigation() {
    val navController = rememberNavController()

    // Oba ViewModele są związane z aktywnością, nie z ekranem: wybór urządzenia na
    // ekranie listy musi natychmiast zmienić stan pilota, a powrót do pilota nie może
    // gubić otwartego połączenia.
    val remoteViewModel: RemoteViewModel = viewModel()
    val devicesViewModel: DevicesViewModel = viewModel()

    NavHost(navController = navController, startDestination = Routes.REMOTE) {
        composable(Routes.REMOTE) {
            RemoteScreen(
                onOpenDevices = { navController.navigate(Routes.DEVICES) },
                viewModel = remoteViewModel,
            )
        }
        composable(Routes.DEVICES) {
            DevicesScreen(
                onDeviceChosen = { device ->
                    remoteViewModel.select(device)
                    navController.popBackStack(Routes.REMOTE, inclusive = false)
                },
                onBack = { navController.popBackStack() },
                viewModel = devicesViewModel,
            )
        }
    }
}
