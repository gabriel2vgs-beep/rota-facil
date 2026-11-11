package br.com.rotafacil.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.rotafacil.MainViewModel
import br.com.rotafacil.core.*
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun ScanScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    val outcome by vm.scanOutcome.collectAsStateWithLifecycle()
    val romaneio by vm.romaneio.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Title("Bipar pacote", "Aponte a câmera para o código de barras")
        if (romaneio == null) { Text("Importe um romaneio antes de bipar.", Modifier.padding(16.dp)); return@Column }
        if (granted) {
            val paused = outcome != null   // pausa a leitura enquanto o resultado está na tela
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(300.dp).clip(MaterialTheme.shapes.large)) {
                CameraPreview(owner, paused) { vm.scan(it, fromCamera = true) }
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                Text("A câmera precisa de permissão para ler os códigos. Você ainda pode digitar o código abaixo.")
                BigButton("PERMITIR CÂMERA", { launcher.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth())
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ou digite o código", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(manual, { manual = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("BR…") })
                Button({ vm.scan(manual); manual = "" }, enabled = manual.isNotBlank(), modifier = Modifier.height(56.dp)) { Text("BUSCAR") }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CameraPreview(owner: androidx.lifecycle.LifecycleOwner, paused: Boolean, onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val pausedState = rememberUpdatedState(paused)
    val onCodeState = rememberUpdatedState(onCode)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
    }
    val providerRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    val disposed = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) { onDispose { disposed[0] = true; providerRef[0]?.unbindAll(); scanner.close(); executor.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            val view = PreviewView(c).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                if (disposed[0]) return@addListener
                val provider = future.get(); providerRef[0] = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || pausedState.value) { proxy.close(); return@setAnalyzer }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { list ->
                            // Só aceita se a câmera enxergar exatamente UM código; se vir vários, ignora (evita pegar o de outro pacote).
                            val values = list.mapNotNull { it.rawValue?.trim() }.filter { it.isNotEmpty() }.distinct()
                            if (values.size == 1 && !pausedState.value) onCodeState.value(values[0])
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) { }
            }, ContextCompat.getMainExecutor(c))
            view
        },
    )
}

/** Resultado da bipagem: mostra imediatamente a parada, o endereço e os pacotes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultSheet(vm: MainViewModel, outcome: ScanOutcome, onOpenStop: (Int) -> Unit, onClose: () -> Unit) {
    val loc by vm.location.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (outcome) {
                is ScanOutcome.Invalid -> {
                    Text("CÓDIGO INVÁLIDO", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Red)
                    Text("Não consegui ler um código. Tente de novo.")
                }
                is ScanOutcome.NotFound -> {
                    Text("PACOTE NÃO ENCONTRADO", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Red)
                    Text(outcome.code, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Este código não está no romaneio importado. Nenhuma parada foi associada.")
                }
                is ScanOutcome.Ambiguous -> {
                    Text("CÓDIGO REPETIDO NO ROMANEIO", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Amber)
                    Text("${outcome.code} aparece em mais de um pacote. Escolha a parada:")
                    outcome.matches.forEach { m -> BigButton("PARADA ${m.stopNumber} — ${m.fullAddress}", { onOpenStop(m.stopNumber) }, Modifier.fillMaxWidth(), primary = false) }
                }
                is ScanOutcome.Found -> {
                    val s = outcome.stop
                    if (outcome.alreadyIdentified) Chip("PACOTE JÁ IDENTIFICADO", Amber)
                    if (outcome.stopAlreadyCompleted) Chip("⚠ ESTA PARADA JÁ FOI CONCLUÍDA", Red)
                    Text("PARADA ${s.stopNumber.toString().padStart(2, '0')}", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                    Text(s.address, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (outcome.pkg.complement.isNotBlank()) Text(outcome.pkg.complement, fontSize = 15.sp)
                    val dist = loc?.let { l -> s.location?.let { Geo.haversineMeters(l, it) } }
                    Text(buildString {
                        append("Status: ${stopStatusLabel(s.status)}")
                        append(" • Posição na rota: ${s.optimizedOrder?.let { "$it" } ?: "rota ainda não organizada"}")
                        dist?.let { append(" • ≈ ${Geo.formatDistance(it)}") }
                    }, fontSize = 13.sp)
                    HorizontalDivider()
                    Text("Pacotes nesta parada: ${outcome.stopPackages.size}", fontWeight = FontWeight.Bold)
                    outcome.stopPackages.forEach { p ->
                        val here = p.id == outcome.pkg.id
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${p.trackingCode} — " + if (here) "identificado agora" else pkgStatusLabel(p.status).lowercase(),
                                fontWeight = if (here) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                            if (p.status != PackageStatus.PENDING) Chip(pkgStatusLabel(p.status), pkgStatusColor(p.status))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    BigButton("ABRIR PARADA", { onOpenStop(s.stopNumber) }, Modifier.fillMaxWidth())
                }
            }
            BigButton("BIPAR OUTRO", onClose, Modifier.fillMaxWidth(), primary = false)
        }
    }
}
