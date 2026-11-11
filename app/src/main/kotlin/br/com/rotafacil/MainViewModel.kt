package br.com.rotafacil

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import br.com.rotafacil.core.*
import br.com.rotafacil.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

data class ImportUi(
    val fileName: String,
    val table: RawTable,
    val mapping: ColumnMapping,
    val allowDuplicates: Boolean,
    val existing: List<ExistingRomaneio>,
    val preview: ImportPreview?,
    val busy: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)
    private val dao = db.dao()
    private val engine = DeliveryEngine(RoomDeliveryStore(db))
    private val tableReader = TableReader(JxlXlsReader())

    val prefs = Prefs(app)
    val tracker = LocationTracker(app)
    val location: StateFlow<LatLng?> get() = tracker.location
    val gpsStatus: StateFlow<GpsStatus> get() = tracker.status

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private fun say(text: String) { messages.tryEmit(text) }

    // --- dados (sempre vindos do banco local, funcionam offline) -----------------------------

    val romaneio: StateFlow<RomaneioEntity?> =
        dao.latestRomaneio().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val packages: StateFlow<List<PackageRecord>> = romaneio.flatMapLatest { r ->
        if (r == null) flowOf(emptyList()) else dao.packagesFlow(r.id).map { l -> l.map { it.toRecord() } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val stops: StateFlow<List<StopRecord>> = romaneio.flatMapLatest { r ->
        if (r == null) flowOf(emptyList()) else dao.stopsFlow(r.id).map { l -> l.map { it.toRecord() } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val packagesByStop: StateFlow<Map<Int, List<PackageRecord>>> =
        packages.map { l -> l.groupBy { it.stopNumber } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val progress: StateFlow<Progress> = combine(packages, stops) { p, s -> Progress.of(p, s) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Progress(0, 0, 0, 0, 0, 0))

    val photos: StateFlow<List<ProofPhotoRecord>> = romaneio.flatMapLatest { r ->
        if (r == null) flowOf(emptyList()) else dao.proofsFlow(r.id).map { l -> l.map { it.toRecord() } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Paradas na ordem da rota (as ainda sem ordem otimizada vêm pelo número Stop). */
    val orderedStops: StateFlow<List<StopRecord>> = stops
        .map { l -> l.sortedWith(compareBy({ it.optimizedOrder ?: Int.MAX_VALUE }, { it.stopNumber })) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val routeOrganized: StateFlow<Boolean> = stops.map { l -> l.isNotEmpty() && l.all { it.optimizedOrder != null } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // --- localização ---------------------------------------------------------------------------

    /** Ponto inicial: o escolhido manualmente, se houver; senão o GPS. Nunca nulo se a rota puder ser calculada. */
    fun startPoint(): LatLng? = prefs.manualStart.value ?: location.value

    val nearby: StateFlow<Proximity.Nearby?> = combine(location, stops) { loc, s ->
        if (loc == null) null else Proximity.nearestOpenStop(loc, s, 100.0)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // --- importação ---------------------------------------------------------------------------

    val importUi = MutableStateFlow<ImportUi?>(null)
    val importError = MutableStateFlow<String?>(null)

    fun pickFile(uri: Uri, displayName: String?) {
        importError.value = null
        viewModelScope.launch {
            try {
                val (name, table, existing) = withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw UnsupportedFormatException("Não consegui abrir o arquivo.")
                    if (bytes.size > 30 * 1024 * 1024) throw UnsupportedFormatException("Arquivo grande demais (máximo 30 MB).")
                    val nm = displayName ?: uri.lastPathSegment ?: "romaneio"
                    val t = tableReader.read(nm, bytes)
                    val keys = dao.allKeys().groupBy({ it.romaneioId }, { it.lookupKey })
                    val ex = dao.romaneios().map { ExistingRomaneio(it.id, it.fileName, it.contentHash, keys[it.id].orEmpty().toSet()) }
                    Triple(nm, t, ex)
                }
                val auto = HeaderMapper.suggest(table)
                importUi.value = ImportUi(name, table, auto.mapping, false, existing, null)
                recomputePreview()
            } catch (e: UnsupportedFormatException) {
                importError.value = e.message
            } catch (e: Exception) {
                importError.value = "Erro ao ler o arquivo: ${e.message}"
            }
        }
    }

    fun setColumn(field: Field, column: Int?) {
        val ui = importUi.value ?: return
        val cols = ui.mapping.columns.toMutableMap()
        if (column == null) cols.remove(field) else {
            cols.entries.removeAll { it.value == column && it.key != field }   // uma coluna por campo
            cols[field] = column
        }
        importUi.value = ui.copy(mapping = ColumnMapping(cols))
        recomputePreview()
    }

    fun setAllowDuplicates(v: Boolean) {
        importUi.value = importUi.value?.copy(allowDuplicates = v)
        recomputePreview()
    }

    private fun recomputePreview() {
        val ui = importUi.value ?: return
        val ready = ui.mapping.has(Field.TRACKING) && ui.mapping.has(Field.ADDRESS)
        val preview = if (!ready) null else {
            DuplicateGuard.check(ImportParser.parse(ui.fileName, ui.table, ui.mapping, ImportOptions(ui.allowDuplicates)), ui.existing)
        }
        importUi.value = ui.copy(preview = preview)
    }

    fun confirmImport(onDone: () -> Unit) {
        val ui = importUi.value ?: return
        val preview = ui.preview ?: return
        if (!preview.canConfirm) return
        importUi.value = ui.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.withTransaction {
                        val rid = dao.insertRomaneio(RomaneioEntity(importedAt = System.currentTimeMillis(), fileName = preview.fileName,
                            totalPackages = preview.totalPackages, totalStops = preview.totalStops, contentHash = preview.contentHash))
                        dao.insertStops(preview.stops.map {
                            StopEntity(romaneioId = rid, stopNumber = it.stopNumber, address = it.address, neighborhood = it.neighborhood,
                                city = it.city, latitude = it.latitude, longitude = it.longitude, optimizedOrder = null,
                                packageCount = it.packages.size, status = StopStatus.PENDING.name)
                        })
                        dao.insertPackages(preview.packages.map {
                            PackageEntity(romaneioId = rid, trackingCode = it.trackingCode, lookupKey = it.lookupKey, fullAddress = it.fullAddress,
                                complement = it.complement, neighborhood = it.neighborhood, latitude = it.latitude, longitude = it.longitude,
                                stopNumber = it.stop, sequence = it.sequence, status = PackageStatus.PENDING.name,
                                failureReason = null, failureNote = null, scannedAt = null, deliveredAt = null, failedAt = null)
                        })
                        // Conferência final: se algo não bateu, a transação inteira é desfeita.
                        check(dao.countPackages(rid) == preview.totalPackages) { "Conferência falhou: pacotes gravados diferem do preview." }
                        check(dao.countStops(rid) == preview.totalStops) { "Conferência falhou: paradas gravadas diferem do preview." }
                    }
                }
                say("Romaneio importado: ${preview.totalPackages} pacotes em ${preview.totalStops} paradas.")
                importUi.value = null
                onDone()
            } catch (e: Exception) {
                importUi.value = ui.copy(busy = false)
                importError.value = "A importação foi cancelada e nada foi salvo: ${e.message}"
            }
        }
    }

    fun cancelImport() { importUi.value = null; importError.value = null }

    fun deleteRomaneio() {
        val r = romaneio.value ?: return
        viewModelScope.launch(Dispatchers.IO) { dao.deleteRomaneio(r.id); PhotoStorage.deleteAll(getApplication(), r.id); routePath.value = emptyList() }
    }

    // --- bipagem ---------------------------------------------------------------------------------

    val scanOutcome = MutableStateFlow<ScanOutcome?>(null)
    private var lastRaw: String? = null
    private var lastRawAt = 0L

    fun scan(raw: String, fromCamera: Boolean = false) {
        val now = System.currentTimeMillis()
        if (fromCamera && raw == lastRaw && now - lastRawAt < 2500) return   // evita leituras repetidas do mesmo código
        lastRaw = raw; lastRawAt = now
        val r = romaneio.value
        if (r == null) { say("Importe um romaneio antes de bipar."); return }
        viewModelScope.launch(Dispatchers.IO) { scanOutcome.value = engine.scan(r.id, raw, now) }
    }

    fun clearScan() { scanOutcome.value = null; lastRaw = null }

    // --- entregas ------------------------------------------------------------------------------------

    private fun act(block: () -> Unit) { viewModelScope.launch(Dispatchers.IO) { try { block() } catch (e: Exception) { say("Erro: ${e.message}") } } }

    fun delivered(packageId: Long) = act { engine.markDelivered(packageId, System.currentTimeMillis()) }
    fun notDelivered(packageId: Long, reason: FailureReason, note: String?) = act { engine.markNotDelivered(packageId, reason, note, System.currentTimeMillis()) }
    fun reopen(packageId: Long) = act { engine.reopen(packageId) }
    fun stopDelivered(stopNumber: Int) { val r = romaneio.value ?: return; act { engine.markStopDelivered(r.id, stopNumber, System.currentTimeMillis()) } }
    fun stopNotDelivered(stopNumber: Int, reason: FailureReason, note: String?) {
        val r = romaneio.value ?: return
        act { engine.markStopNotDelivered(r.id, stopNumber, reason, note, System.currentTimeMillis()) }
    }

    // --- fotos de prova ---------------------------------------------------------------------------------

    /**
     * Grava a foto (já revisada pelo motorista) e, se [thenDeliver], marca como entregue DEPOIS de a foto estar salva.
     * [packageId] null = foto da parada inteira. Se gravar a foto falhar, nada é marcado como entregue.
     */
    fun saveProof(prepared: File, stopNumber: Int, packageId: Long?, thenDeliver: Boolean, onDone: (Boolean) -> Unit) {
        val r = romaneio.value
        if (r == null) { PhotoStorage.discard(prepared); say("Nenhum romaneio aberto."); onDone(false); return }
        val code = packageId?.let { id -> packages.value.firstOrNull { it.id == id }?.trackingCode }
        val here = location.value
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    val stored = PhotoStorage.commit(getApplication(), prepared, r.id, ProofRules.fileName(stopNumber, code, now, ZoneId.systemDefault()))
                    try {
                        dao.insertProof(ProofPhotoEntity(romaneioId = r.id, stopNumber = stopNumber, packageId = packageId, filePath = stored.file.path,
                            takenAt = now, latitude = here?.lat, longitude = here?.lng, sha256 = stored.sha256))
                    } catch (e: Exception) { stored.file.delete(); throw e }
                    if (thenDeliver) {
                        if (packageId != null) engine.markDelivered(packageId, now) else engine.markStopDelivered(r.id, stopNumber, now)
                    }
                }
                say(if (thenDeliver) "Foto salva e entrega registrada." else "Foto de prova salva.")
                onDone(true)
            } catch (e: Exception) {
                PhotoStorage.discard(prepared)
                say("Não consegui salvar a foto: ${e.message}. Nada foi marcado como entregue.")
                onDone(false)
            }
        }
    }

    fun deleteProof(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try { dao.proofById(id)?.let { PhotoStorage.discard(File(it.filePath)); dao.deleteProof(id) } } catch (e: Exception) { say("Erro ao apagar a foto: ${e.message}") }
        }
    }

    // --- rota -----------------------------------------------------------------------------------------

    val routeBusy = MutableStateFlow(false)
    val routePath = MutableStateFlow<List<LatLng>>(emptyList())

    /** Reorganiza as paradas ainda abertas a partir do ponto inicial. Em caso de falha, mantém a última rota. */
    fun organizeRoute(onDone: (Boolean) -> Unit = {}) {
        val r = romaneio.value
        if (r == null) { say("Importe um romaneio antes de organizar a rota."); onDone(false); return }
        val start = startPoint()
        if (start == null) {
            say("Sem localização válida. Ligue o GPS ou escolha um ponto inicial em Ajustes.")
            onDone(false); return
        }
        if (routeBusy.value) return
        routeBusy.value = true
        viewModelScope.launch {
            var ok = false
            try {
                val snapshot = stops.value
                val open = snapshot.filter { it.status != StopStatus.COMPLETED }
                val route = withContext(Dispatchers.IO) {
                    RouteOptimizer.optimize(start, open.map { RouteStop(it.stopNumber, it.location) }, OsrmMatrixProvider(Http, prefs.osrmUrl.value))
                }
                val orders = RoutePlan.assignOrders(snapshot, route)
                withContext(Dispatchers.IO) { db.withTransaction { orders.forEach { (sn, o) -> dao.updateStopOrder(r.id, sn, o) } } }
                val extra = if (route.unlocatedStops.isNotEmpty()) " ${route.unlocatedStops.size} parada(s) sem coordenadas ficaram no fim." else ""
                say("Rota organizada: ${open.size} paradas, cerca de ${(route.totalSeconds / 60).toInt()} min de deslocamento.$extra")
                ok = true
                loadRoutePath()
            } catch (e: RoutingException) {
                say("${e.message} Mantive a última rota calculada.")
            } catch (e: Exception) {
                say("Não consegui organizar a rota: ${e.message}")
            } finally {
                routeBusy.value = false
                onDone(ok)
            }
        }
    }

    /** Traçado real das ruas até as próximas paradas (opcional; se estiver offline o mapa usa linhas retas). */
    fun loadRoutePath() {
        val start = startPoint() ?: return
        val next = orderedStops.value.filter { it.status != StopStatus.COMPLETED && it.optimizedOrder != null }.take(20).mapNotNull { it.location }
        if (next.isEmpty()) { routePath.value = emptyList(); return }
        viewModelScope.launch {
            try {
                routePath.value = withContext(Dispatchers.IO) { OsrmRouteGeometry(Http, prefs.osrmUrl.value).path(listOf(start) + next) }
            } catch (_: Exception) { routePath.value = emptyList() }
        }
    }

    override fun onCleared() { tracker.stop() }
}
