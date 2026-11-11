package br.com.rotafacil.ui

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.rotafacil.*
import br.com.rotafacil.core.*

// ───────────────────────────── INÍCIO ─────────────────────────────

@Composable
fun HomeScreen(vm: MainViewModel, onImport: () -> Unit, onScan: () -> Unit, onStops: () -> Unit, onMap: () -> Unit) {
    val pr by vm.progress.collectAsStateWithLifecycle()
    val romaneio by vm.romaneio.collectAsStateWithLifecycle()
    val gps by vm.gpsStatus.collectAsStateWithLifecycle()
    val busy by vm.routeBusy.collectAsStateWithLifecycle()
    val organized by vm.routeOrganized.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val next by vm.orderedStops.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val nav by vm.prefs.navApp.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Title("Rota Fácil", romaneio?.fileName ?: "Nenhum romaneio importado")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Progresso da rota", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${pr.percent}%", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                ProgressBar(pr.percent, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("${pr.completedStops} de ${pr.totalStops} paradas concluídas", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Encomendas", pr.totalPackages, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                StatTile("Paradas", pr.totalStops, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Entregues", pr.delivered, Green, Modifier.weight(1f))
                StatTile("Pendentes", pr.pending, Amber, Modifier.weight(1f))
                StatTile("Não entregues", pr.notDelivered, Red, Modifier.weight(1f))
            }

            nearby?.let { n ->
                AppCard(onClick = { onStops() }) {
                    Text("📍 Você está perto da parada ${n.stop.stopNumber}", fontWeight = FontWeight.Bold)
                    Text("${n.stop.address} • a ${Geo.formatDistance(n.meters)}", fontSize = 13.sp)
                }
            }

            if (gps != GpsStatus.OK && vm.prefs.manualStart.value == null) {
                AppCard { Text("⚠️ ${gps.text}. Ligue o GPS ou escolha um ponto inicial em Ajustes para organizar a rota.", fontSize = 14.sp) }
            }

            BigButton("IMPORTAR ROMANEIO", onImport, Modifier.fillMaxWidth(), primary = romaneio == null, icon = { Icon(Icons.Filled.UploadFile, null) })
            BigButton(if (busy) "ORGANIZANDO…" else "ORGANIZAR ROTA", { vm.organizeRoute() }, Modifier.fillMaxWidth(),
                primary = romaneio != null && !organized, enabled = romaneio != null && !busy, icon = { Icon(Icons.Filled.Route, null) })
            BigButton("INICIAR ENTREGAS", {
                val first = next.firstOrNull { it.status != StopStatus.COMPLETED }
                val loc = first?.location
                if (first == null) onStops()
                else if (loc != null) { openNavigation(ctx, nav, loc.lat, loc.lng); onStops() } else onStops()
            }, Modifier.fillMaxWidth(), primary = organized, enabled = romaneio != null, icon = { Icon(Icons.Filled.PlayArrow, null) })
            BigButton("BIPAR PACOTE", onScan, Modifier.fillMaxWidth(), primary = false, enabled = romaneio != null, icon = { Icon(Icons.Filled.QrCodeScanner, null) })
        }
    }
}

// ───────────────────────────── IMPORTAÇÃO ─────────────────────────────

private fun displayName(ctx: Context, uri: android.net.Uri): String? =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(vm: MainViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val ui by vm.importUi.collectAsStateWithLifecycle()
    val error by vm.importError.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickFile(uri, displayName(ctx, uri))
    }
    val mimes = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
            Text("Importar romaneio", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        error?.let { AppCard(Modifier.padding(16.dp)) { Text(it, color = MaterialTheme.colorScheme.error) } }
        val u = ui
        if (u == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Escolha a planilha da Shopee (.xlsx, .xls ou .csv). Você verá uma prévia antes de confirmar; nada é salvo até lá.")
                BigButton("ESCOLHER ARQUIVO", { picker.launch(mimes) }, Modifier.fillMaxWidth(), icon = { Icon(Icons.Filled.FolderOpen, null) })
            }
            return@Column
        }
        val pv = u.preview
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard {
                    Text(u.fileName, fontWeight = FontWeight.Bold)
                    Text("${u.table.rows.size} linhas lidas", fontSize = 13.sp)
                    if (pv != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("${pv.totalPackages} pacotes", MaterialTheme.colorScheme.secondary)
                            Chip("${pv.totalStops} paradas", Green)
                        }
                        val multi = pv.stops.count { it.packages.size > 1 }
                        if (multi > 0) Text("$multi parada(s) com mais de um pacote", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            item { Text("Colunas reconhecidas", fontWeight = FontWeight.SemiBold) }
            items(Field.values().toList()) { f -> MappingRow(f, u.table.headers, u.mapping.indexOf(f)) { vm.setColumn(f, it) } }

            if (pv != null) {
                item {
                    if (pv.issues.any { it.code == IssueCode.DUPLICATE_CODE_IN_FILE }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(u.allowDuplicates, { vm.setAllowDuplicates(it) })
                            Text("Permitir códigos repetidos (a bipagem pedirá confirmação)", fontSize = 13.sp)
                        }
                    }
                }
                if (pv.errors.isNotEmpty()) {
                    item { Text("Erros (impedem a importação)", fontWeight = FontWeight.Bold, color = Red) }
                    items(pv.errors.take(30)) { Text("• ${it.message}", fontSize = 13.sp, color = Red) }
                }
                if (pv.warnings.isNotEmpty()) {
                    item { Text("Avisos", fontWeight = FontWeight.Bold, color = Amber) }
                    items(pv.warnings.take(30)) { Text("• ${it.message}", fontSize = 13.sp) }
                    if (pv.warnings.size > 30) item { Text("… e mais ${pv.warnings.size - 30} avisos", fontSize = 13.sp) }
                }
                item { Text("Prévia das paradas", fontWeight = FontWeight.SemiBold) }
                items(pv.stops.take(15)) { s ->
                    AppCard {
                        Text("PARADA ${s.stopNumber} • ${s.packages.size} pacote(s)", fontWeight = FontWeight.Bold)
                        Text(s.address, fontSize = 13.sp)
                        s.packages.forEach { Text("${it.trackingCode}${if (it.complement.isNotBlank()) " — ${it.complement}" else ""}", fontSize = 12.sp) }
                    }
                }
                if (pv.stops.size > 15) item { Text("… e mais ${pv.stops.size - 15} paradas", fontSize = 13.sp) }
            } else item { Text("Indique as colunas de código de rastreamento e endereço para ver a prévia.", color = Amber) }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("OUTRO ARQUIVO", { vm.cancelImport(); picker.launch(mimes) }, Modifier.weight(1f), primary = false)
            BigButton(if (u.busy) "SALVANDO…" else "CONFIRMAR", { vm.confirmImport(onDone) }, Modifier.weight(1f), enabled = pv?.canConfirm == true && !u.busy)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MappingRow(field: Field, headers: List<String>, selected: Int?, onChange: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(field.label + if (field.required) " *" else "", Modifier.weight(1f), fontSize = 14.sp)
        Box {
            OutlinedButton({ open = true }, shape = MaterialTheme.shapes.small) { Text(selected?.let { headers.getOrNull(it) } ?: "— não usar —", maxLines = 1) }
            DropdownMenu(open, { open = false }) {
                if (!field.required) DropdownMenuItem({ Text("— não usar —") }, { onChange(null); open = false })
                headers.forEachIndexed { i, h -> DropdownMenuItem({ Text(h.ifBlank { "(coluna ${i + 1})" }) }, { onChange(i); open = false }) }
            }
        }
    }
}

// ───────────────────────────── PARADAS ─────────────────────────────

@Composable
fun StopsScreen(vm: MainViewModel, onOpen: (Int) -> Unit) {
    val stops by vm.orderedStops.collectAsStateWithLifecycle()
    val pkgs by vm.packagesByStop.collectAsStateWithLifecycle()
    val loc by vm.location.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(0) }   // 0 todas, 1 abertas, 2 concluídas
    var query by remember { mutableStateOf("") }
    val q = Text.normalize(query)
    val shown = stops.filter { s ->
        (filter == 0 || (filter == 1 && s.status != StopStatus.COMPLETED) || (filter == 2 && s.status == StopStatus.COMPLETED)) &&
            (q.isEmpty() || Text.normalize(s.address).contains(q) || s.stopNumber.toString() == query.trim() ||
                pkgs[s.stopNumber].orEmpty().any { it.lookupKey.contains(query.trim().uppercase()) })
    }
    Column(Modifier.fillMaxSize()) {
        Title("Paradas", "${stops.size} paradas na rota")
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            placeholder = { Text("Buscar parada, endereço ou código") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = MaterialTheme.shapes.medium)
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Todas", "Abertas", "Concluídas").forEachIndexed { i, t -> FilterChip(filter == i, { filter = i }, { Text(t) }) }
        }
        if (stops.isEmpty()) { Text("Importe um romaneio para ver as paradas.", Modifier.padding(16.dp)); return@Column }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { s ->
                val list = pkgs[s.stopNumber].orEmpty()
                val dist = loc?.let { l -> s.location?.let { Geo.haversineMeters(l, it) } }
                AppCard(onClick = { onOpen(s.stopNumber) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PARADA ${s.stopNumber}", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Chip(stopStatusLabel(s.status), stopStatusColor(s.status))
                    }
                    Text(s.address, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(buildString {
                        append("${list.size} pacote(s) • ${list.count { it.status != PackageStatus.PENDING }}/${list.size} resolvidos")
                        s.optimizedOrder?.let { append(" • rota nº $it") }
                        dist?.let { append(" • ≈ ${Geo.formatDistance(it)}") }
                    }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
fun ReasonDialog(onDismiss: () -> Unit, onConfirm: (FailureReason, String?) -> Unit) {
    var reason by remember { mutableStateOf(FailureReason.ABSENT) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Motivo da não entrega") },
        text = {
            Column {
                FailureReason.values().forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { reason = r }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(reason == r, { reason = r }); Text(r.label)
                    }
                }
                if (reason == FailureReason.OTHER) OutlinedTextField(note, { note = it }, label = { Text("Descreva") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton({ onConfirm(reason, note) }, enabled = reason != FailureReason.OTHER || note.isNotBlank()) { Text("Confirmar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

// ───────────────────────────── AJUSTES ─────────────────────────────

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val theme by vm.prefs.themeMode.collectAsStateWithLifecycle()
    val nav by vm.prefs.navApp.collectAsStateWithLifecycle()
    val osrm by vm.prefs.osrmUrl.collectAsStateWithLifecycle()
    val requirePhoto by vm.prefs.requirePhoto.collectAsStateWithLifecycle()
    val manual by vm.prefs.manualStart.collectAsStateWithLifecycle()
    val gps by vm.gpsStatus.collectAsStateWithLifecycle()
    val loc by vm.location.collectAsStateWithLifecycle()
    var startText by remember(manual) { mutableStateOf(manual?.let { "${it.lat}, ${it.lng}" } ?: "") }
    var osrmText by remember(osrm) { mutableStateOf(osrm) }
    var confirmDelete by remember { mutableStateOf(false) }
    val romaneio by vm.romaneio.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Title("Ajustes")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCard {
                Text("Tema", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Sistema", "Claro", "Escuro").forEachIndexed { i, t -> FilterChip(theme == i, { vm.prefs.setTheme(i) }, { Text(t) }) }
                }
            }
            AppCard {
                Text("Navegação externa", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(nav == "maps", { vm.prefs.setNavApp("maps") }, { Text("Google Maps") })
                    FilterChip(nav == "waze", { vm.prefs.setNavApp("waze") }, { Text("Waze") })
                }
            }
            AppCard {
                Text("Prova de entrega", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Exigir foto ao marcar ENTREGUE", fontSize = 14.sp)
                        Text("Ligado: o botão ENTREGUE abre a câmera primeiro. Desligado: a foto é opcional (botão da câmera em cada pacote e na parada).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Switch(requirePhoto, { vm.prefs.setRequirePhoto(it) })
                }
            }
            AppCard {
                Text("Localização", fontWeight = FontWeight.SemiBold)
                Text("${gps.text}" + (loc?.let { " • %.5f, %.5f".format(java.util.Locale.ROOT, it.lat, it.lng) } ?: ""), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                BigButton("ATUALIZAR LOCALIZAÇÃO", { vm.tracker.start() }, Modifier.fillMaxWidth(), primary = false, icon = { Icon(Icons.Filled.MyLocation, null) })
                Spacer(Modifier.height(10.dp))
                Text("Ponto inicial manual (opcional)", fontSize = 13.sp)
                Text("Se preenchido, é usado no lugar do GPS. Cole as coordenadas do Google Maps, ex.: -23.4833, -46.1307", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                OutlinedTextField(startText, { startText = it }, Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    isError = startText.isNotBlank() && parseLatLng(startText) == null)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ parseLatLng(startText)?.let { vm.prefs.setManualStart(it) } }, enabled = parseLatLng(startText) != null) { Text("Usar este ponto") }
                    TextButton({ vm.prefs.setManualStart(null); startText = "" }) { Text("Usar o GPS") }
                }
            }
            AppCard {
                Text("Serviço de rotas (OSRM)", fontWeight = FontWeight.SemiBold)
                Text("O servidor padrão é público e de demonstração. Para uso diário, informe o endereço do seu próprio servidor OSRM.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                OutlinedTextField(osrmText, { osrmText = it }, Modifier.fillMaxWidth(), singleLine = true)
                TextButton({ vm.prefs.setOsrmUrl(osrmText) }) { Text("Salvar") }
            }
            if (romaneio != null) AppCard {
                Text("Romaneio atual", fontWeight = FontWeight.SemiBold)
                Text(romaneio!!.fileName, fontSize = 13.sp)
                TextButton({ confirmDelete = true }) { Text("Apagar romaneio e entregas", color = Red) }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Apagar romaneio?") },
        text = { Text("Todos os pacotes, paradas e o andamento das entregas serão apagados deste aparelho. Isso não pode ser desfeito.") },
        confirmButton = { TextButton({ vm.deleteRomaneio(); confirmDelete = false }) { Text("Apagar", color = Red) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } },
    )
}
