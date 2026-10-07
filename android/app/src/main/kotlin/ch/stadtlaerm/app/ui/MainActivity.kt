package ch.stadtlaerm.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import ch.stadtlaerm.app.edition.EditionUi
import ch.stadtlaerm.chart.StadtlaermTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { StadtlaermTheme { AppRoot() } }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Messen", Icons.Filled.Home),
    Tab("Nächte", Icons.Filled.DateRange),
    Tab("Kalibrieren", Icons.Filled.Build),
    Tab("Daten", Icons.Filled.Share),
    Tab("Einstellungen", Icons.Filled.Settings),
)

@Composable
fun AppRoot() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        // Public: emits nothing (same as Scaffold's default). Labor: the red warning banner.
        topBar = { EditionUi.Banner() },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        CompositionLocalProvider(LocalSnackbarHost provides snackbar) {
            when (selected) {
                0 -> MeasureScreen(m)
                1 -> NightsScreen(m)
                2 -> CalibrateScreen(m)
                3 -> DataScreen(m)
                else -> SettingsScreen(m)
            }
        }
    }
}
