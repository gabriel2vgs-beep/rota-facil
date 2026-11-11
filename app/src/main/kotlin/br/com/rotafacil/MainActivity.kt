package br.com.rotafacil

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.rotafacil.ui.*

private val Brand = Color(0xFFEE4D2D)

private val LightColors = lightColorScheme(
    primary = Brand, onPrimary = Color.White, secondary = Color(0xFF3B82F6), tertiary = Color(0xFF16A34A),
    background = Color(0xFFFAFAFA), surface = Color.White, surfaceVariant = Color(0xFFF1F1F3), error = Color(0xFFDC2626),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6B4A), onPrimary = Color.Black, secondary = Color(0xFF60A5FA), tertiary = Color(0xFF4ADE80),
    background = Color(0xFF000000), surface = Color(0xFF121212), surfaceVariant = Color(0xFF1E1E22), error = Color(0xFFF87171),
)

@Composable
fun RotaTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        shapes = Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

enum class Tab(val label: String, val icon: ImageVector, val iconOff: ImageVector) {
    HOME("Início", Icons.Filled.Home, Icons.Outlined.Home),
    STOPS("Paradas", Icons.Filled.ViewList, Icons.Outlined.ViewList),
    SCAN("Bipar", Icons.Filled.QrCodeScanner, Icons.Outlined.QrCodeScanner),
    MAP("Mapa", Icons.Filled.Map, Icons.Outlined.Map),
    SETTINGS("Ajustes", Icons.Filled.Settings, Icons.Outlined.Settings),
}

sealed class Screen {
    data class Main(val tab: Tab) : Screen()
    object Import : Screen()
    data class StopDetail(val stopNumber: Int) : Screen()
    data class Capture(val stopNumber: Int, val packageId: Long?, val thenDeliver: Boolean) : Screen()
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onStart() { super.onStart(); vm.tracker.start() }
    override fun onStop() { super.onStop(); vm.tracker.stop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by vm.prefs.themeMode.collectAsStateWithLifecycle()
            RotaTheme(mode) { RotaApp(vm) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RotaApp(vm: MainViewModel) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Main(Tab.HOME))) }
    val current = stack.last()
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun goTab(t: Tab) { stack = listOf(Screen.Main(t)) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Pede a permissão de localização na primeira abertura.
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.tracker.start() }
    LaunchedEffect(Unit) {
        if (!vm.tracker.hasPermission()) locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else vm.tracker.start()
    }

    // Botão voltar do sistema
    androidx.activity.compose.BackHandler(enabled = stack.size > 1 || (current is Screen.Main && current.tab != Tab.HOME)) {
        if (stack.size > 1) pop() else goTab(Tab.HOME)
    }

    // Resultado da bipagem aparece sobre qualquer tela
    val outcome by vm.scanOutcome.collectAsStateWithLifecycle()
    outcome?.let { ScanResultSheet(vm, it, onOpenStop = { n -> vm.clearScan(); push(Screen.StopDetail(n)) }, onClose = { vm.clearScan() }) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (current is Screen.Main) {
                NavigationBar(tonalElevation = 0.dp, containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.values().forEach { t ->
                        NavigationBarItem(
                            selected = current.tab == t, onClick = { goTab(t) },
                            icon = { Icon(if (current.tab == t) t.icon else t.iconOff, t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AnimatedContent(targetState = current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screens") { s ->
                when (s) {
                    is Screen.Main -> when (s.tab) {
                        Tab.HOME -> HomeScreen(vm, onImport = { push(Screen.Import) }, onScan = { goTab(Tab.SCAN) }, onStops = { goTab(Tab.STOPS) }, onMap = { goTab(Tab.MAP) })
                        Tab.STOPS -> StopsScreen(vm, onOpen = { push(Screen.StopDetail(it)) })
                        Tab.SCAN -> ScanScreen(vm)
                        Tab.MAP -> MapScreen(vm, onOpen = { push(Screen.StopDetail(it)) })
                        Tab.SETTINGS -> SettingsScreen(vm)
                    }
                    Screen.Import -> ImportScreen(vm, onBack = { vm.cancelImport(); pop() }, onDone = { goTab(Tab.HOME) })
                    is Screen.StopDetail -> StopDetailScreen(vm, s.stopNumber, onBack = { pop() }, onCapture = { st, pid, d -> push(Screen.Capture(st, pid, d)) })
                    is Screen.Capture -> CaptureScreen(vm, s.stopNumber, s.packageId, s.thenDeliver, onClose = { pop() })
                }
            }
        }
    }
}
