package br.com.rotafacil.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.rotafacil.MainViewModel
import br.com.rotafacil.PhotoStorage
import br.com.rotafacil.core.*
import br.com.rotafacil.openNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// ───────────────────────────── CÂMERA DA PROVA ─────────────────────────────

/**
 * Tira a foto de prova. Fluxo: câmera → revisar (REFAZER / USAR FOTO) → grava → (opcional) marca como entregue.
 * Se [thenDeliver] e a foto não puder ser gravada, NADA é marcado como entregue.
 */
@Composable
fun CaptureScreen(vm: MainViewModel, stopNumber: Int, packageId: Long?, thenDeliver: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val pkgs by vm.packagesByStop.collectAsStateWithLifecycle()
    val pkg = packageId?.let { id -> pkgs[stopNumber].orEmpty().firstOrNull { it.id == id } }

    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var prepared by remember { mutableStateOf<File?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val preparedRef = rememberUpdatedState(prepared)
    DisposableEffect(Unit) { onDispose { PhotoStorage.discard(preparedRef.value) } }   // foto não confirmada nunca fica no aparelho

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val providerRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    DisposableEffect(Unit) { onDispose { providerRef[0]?.unbindAll() } }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White) }
            Column {
                Text("Foto de prova • Parada $stopNumber", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    when { pkg != null -> "Pacote ${pkg.trackingCode}"; else -> "Vale para todos os pacotes da parada" } + if (thenDeliver) " • marcará como ENTREGUE" else "",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                )
            }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = Color(0xFFFF8A80)) }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val shot = prepared
            if (shot != null) {
                val bmp by produceState<Bitmap?>(null, shot) { value = withContext(Dispatchers.IO) { PhotoStorage.thumbnail(shot.path, 1400) } }
                bmp?.let { Image(it.asImageBitmap(), "Foto tirada", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            } else if (granted) {
                AndroidView(Modifier.fillMaxSize(), factory = { c ->
                    val view = PreviewView(c).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
                    val future = ProcessCameraProvider.getInstance(c)
                    future.addListener({
                        try {
                            val provider = future.get(); providerRef[0] = provider
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        } catch (e: Exception) { error = "Não consegui abrir a câmera: ${e.message}" }
                    }, ContextCompat.getMainExecutor(c))
                    view
                })
            } else {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("A câmera precisa de permissão para tirar a foto.", color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    BigButton("PERMITIR CÂMERA", { launcher.launch(Manifest.permission.CAMERA) })
                }
            }
            if (working) CircularProgressIndicator(color = Color.White)
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (prepared == null) {
                BigButton(if (working) "AGUARDE…" else "TIRAR FOTO", {
                    error = null; working = true
                    val raw = PhotoStorage.rawTempFile(ctx)
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(raw).build(), ContextCompat.getMainExecutor(ctx),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                scope.launch {
                                    try { prepared = withContext(Dispatchers.IO) { PhotoStorage.prepare(ctx, raw) } }
                                    catch (e: Exception) { error = "Não consegui processar a foto: ${e.message}" }
                                    working = false
                                }
                            }
                            override fun onError(e: ImageCaptureException) { PhotoStorage.discard(raw); working = false; error = "Falha ao tirar a foto: ${e.message}" }
                        },
                    )
                }, Modifier.fillMaxWidth(), enabled = granted && !working, icon = { Icon(Icons.Filled.PhotoCamera, null) })
            } else {
                BigButton("REFAZER", { PhotoStorage.discard(prepared); prepared = null }, Modifier.weight(1f), primary = false, enabled = !working)
                BigButton(if (working) "SALVANDO…" else "USAR FOTO", {
                    val file = prepared ?: return@BigButton
                    working = true
                    vm.saveProof(file, stopNumber, packageId, thenDeliver) { ok ->
                        working = false
                        if (ok) { prepared = null; onClose() }
                    }
                }, Modifier.weight(1f), enabled = !working)
            }
        }
    }
}

// ───────────────────────────── MINIATURAS E VISUALIZADOR ─────────────────────────────

@Composable
fun PhotoThumb(photo: ProofPhotoRecord, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, photo.filePath) { value = withContext(Dispatchers.IO) { PhotoStorage.thumbnail(photo.filePath, 240) } }
    Box(Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), "Foto de prova", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.BrokenImage, "Foto indisponível", tint = Gray)
    }
}

@Composable
fun PhotoStrip(photos: List<ProofPhotoRecord>, onOpen: (ProofPhotoRecord) -> Unit) {
    if (photos.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        items(photos, key = { it.id }) { PhotoThumb(it) { onOpen(it) } }
    }
}

private fun fmtTime(ms: Long) = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date(ms))

@Composable
fun PhotoViewer(photo: ProofPhotoRecord, onClose: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(null, photo.filePath) { value = withContext(Dispatchers.IO) { PhotoStorage.thumbnail(photo.filePath, 1400) } }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bmp?.let { Image(it.asImageBitmap(), "Foto de prova", Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit) }
                    ?: Text("Foto indisponível (arquivo não encontrado).", color = Red)
                Text("Parada ${photo.stopNumber} • " + if (photo.packageId == null) "foto da parada inteira" else "foto de um pacote", fontWeight = FontWeight.SemiBold)
                Text("Tirada em ${fmtTime(photo.takenAt)}", fontSize = 13.sp)
                if (photo.latitude != null && photo.longitude != null) Text("Local: %.5f, %.5f".format(Locale.ROOT, photo.latitude, photo.longitude), fontSize = 13.sp)
                else Text("Local: GPS indisponível na hora da foto", fontSize = 13.sp)
                Text("Impressão digital (SHA-256): ${photo.sha256.take(16)}…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ confirm = true }) { Text("Apagar foto", color = Red) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClose) { Text("Fechar") }
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Apagar esta foto?") },
        text = { Text("A foto de prova será removida do aparelho. Isso não pode ser desfeito.") },
        confirmButton = { TextButton({ confirm = false; onDelete() }) { Text("Apagar", color = Red) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } },
    )
}

// ───────────────────────────── DETALHE DA PARADA ─────────────────────────────

@Composable
fun StopDetailScreen(vm: MainViewModel, stopNumber: Int, onBack: () -> Unit, onCapture: (stop: Int, packageId: Long?, thenDeliver: Boolean) -> Unit) {
    val stops by vm.stops.collectAsStateWithLifecycle()
    val pkgs by vm.packagesByStop.collectAsStateWithLifecycle()
    val allPhotos by vm.photos.collectAsStateWithLifecycle()
    val loc by vm.location.collectAsStateWithLifecycle()
    val nav by vm.prefs.navApp.collectAsStateWithLifecycle()
    val require by vm.prefs.requirePhoto.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val stop = stops.firstOrNull { it.stopNumber == stopNumber }
    val list = pkgs[stopNumber].orEmpty()
    val photos = allPhotos.filter { it.stopNumber == stopNumber }
    var reasonFor by remember { mutableStateOf<Long?>(null) }   // id do pacote; -1 = parada inteira
    var viewing by remember { mutableStateOf<ProofPhotoRecord?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
            Text("Parada $stopNumber", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (stop == null) { Text("Parada não encontrada.", Modifier.padding(16.dp)); return@Column }
        val dist = loc?.let { l -> stop.location?.let { Geo.haversineMeters(l, it) } }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard {
                    Row { Text("PARADA ${stop.stopNumber}", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f)); Chip(stopStatusLabel(stop.status), stopStatusColor(stop.status)) }
                    Text(listOf(stop.address, stop.neighborhood, stop.city).filter { it.isNotBlank() }.joinToString(" • "), fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("${list.size} pacote(s)" + (stop.optimizedOrder?.let { " • posição $it na rota" } ?: "") + (dist?.let { " • ≈ ${Geo.formatDistance(it)}" } ?: ""), fontSize = 13.sp)
                    stop.location?.let { p ->
                        Spacer(Modifier.height(8.dp))
                        BigButton("NAVEGAR (${if (nav == "waze") "Waze" else "Google Maps"})", { openNavigation(ctx, nav, p.lat, p.lng) }, Modifier.fillMaxWidth(), primary = false, icon = { Icon(Icons.Filled.Navigation, null) })
                    }
                }
            }
            item {
                AppCard {
                    Text("Foto de prova da parada", fontWeight = FontWeight.SemiBold)
                    Text("Vale para todos os ${list.size} pacote(s) desta parada.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    PhotoStrip(photos.filter { it.packageId == null }) { viewing = it }
                    BigButton("TIRAR FOTO DA PARADA", { onCapture(stopNumber, null, false) }, Modifier.fillMaxWidth(), primary = false, icon = { Icon(Icons.Filled.PhotoCamera, null) })
                }
            }
            items(list, key = { it.id }) { p ->
                val own = photos.filter { it.packageId == p.id }
                val hasProof = ProofRules.hasProof(p, photos)
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.trackingCode, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (hasProof) { Chip("📷 com foto", MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(6.dp)) }
                        Chip(pkgStatusLabel(p.status), pkgStatusColor(p.status))
                    }
                    if (p.complement.isNotBlank()) Text(p.complement, fontSize = 14.sp)
                    else if (list.size > 1) Text(p.fullAddress, fontSize = 13.sp)
                    if (p.status == PackageStatus.NOT_DELIVERED) Text("Motivo: ${p.failureReason?.label ?: "—"}${p.failureNote?.let { " ($it)" } ?: ""}", fontSize = 13.sp, color = Red)
                    if (p.status == PackageStatus.DELIVERED && require && !hasProof) Text("⚠ Entregue sem foto de prova", fontSize = 13.sp, color = Amber)
                    if (p.scannedAt != null) Text("✔ bipado", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    PhotoStrip(own) { viewing = it }
                    Spacer(Modifier.height(8.dp))
                    if (p.status == PackageStatus.PENDING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            BigButton("ENTREGUE", {
                                if (ProofRules.needsPhoto(require, listOf(p), photos)) onCapture(stopNumber, p.id, true) else vm.delivered(p.id)
                            }, Modifier.weight(1f))
                            BigButton("NÃO ENTREGUE", { reasonFor = p.id }, Modifier.weight(1f), primary = false)
                            FilledTonalIconButton({ onCapture(stopNumber, p.id, false) }, Modifier.size(56.dp)) { Icon(Icons.Filled.PhotoCamera, "Foto do pacote") }
                        }
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ vm.reopen(p.id) }) { Text("Desfazer") }
                        TextButton({ onCapture(stopNumber, p.id, false) }) { Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Adicionar foto") }
                    }
                }
            }
        }
        val pending = list.filter { it.status == PackageStatus.PENDING }
        if (pending.isNotEmpty()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("TODOS ENTREGUES", {
                    if (ProofRules.needsPhoto(require, pending, photos)) onCapture(stopNumber, null, true) else vm.stopDelivered(stopNumber)
                }, Modifier.weight(1f))
                BigButton("NENHUM ENTREGUE", { reasonFor = -1L }, Modifier.weight(1f), primary = false)
            }
        }
    }
    reasonFor?.let { target ->
        ReasonDialog(
            onDismiss = { reasonFor = null },
            onConfirm = { reason, note ->
                if (target == -1L) vm.stopNotDelivered(stopNumber, reason, note) else vm.notDelivered(target, reason, note)
                reasonFor = null
            },
        )
    }
    viewing?.let { ph -> PhotoViewer(ph, onClose = { viewing = null }, onDelete = { vm.deleteProof(ph.id); viewing = null }) }
}
