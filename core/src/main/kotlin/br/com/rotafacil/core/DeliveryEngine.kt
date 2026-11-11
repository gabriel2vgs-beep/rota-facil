package br.com.rotafacil.core

enum class PackageStatus { PENDING, DELIVERED, NOT_DELIVERED }

enum class FailureReason(val label: String) {
    ABSENT("Destinatário ausente"),
    ADDRESS_NOT_FOUND("Endereço não localizado"),
    REFUSED("Recusado"),
    ACCESS_PROBLEM("Problema de acesso"),
    OTHER("Outro"),
}

/** PENDING = nenhum pacote resolvido; IN_PROGRESS = alguns resolvidos; COMPLETED = nenhum pendente (entregues ou classificados). */
enum class StopStatus { PENDING, IN_PROGRESS, COMPLETED }

data class PackageRecord(
    val id: Long,
    val romaneioId: Long,
    val trackingCode: String,
    val lookupKey: String,
    val fullAddress: String,
    val complement: String,
    val neighborhood: String,
    val latitude: Double?,
    val longitude: Double?,
    /** Número da parada da planilha. Imutável durante toda a operação. */
    val stopNumber: Int,
    /** Sequence da planilha. Imutável. */
    val sequence: Int,
    val status: PackageStatus = PackageStatus.PENDING,
    val failureReason: FailureReason? = null,
    val failureNote: String? = null,
    val scannedAt: Long? = null,
    val deliveredAt: Long? = null,
    val failedAt: Long? = null,
)

data class StopRecord(
    val id: Long,
    val romaneioId: Long,
    /** Número Stop original. Nunca muda, mesmo que a rota seja reotimizada. */
    val stopNumber: Int,
    val address: String,
    val neighborhood: String,
    val city: String,
    val latitude: Double?,
    val longitude: Double?,
    /** Posição na rota otimizada (1 = primeira). Null enquanto não organizada. */
    val optimizedOrder: Int?,
    val packageCount: Int,
    val status: StopStatus = StopStatus.PENDING,
) {
    val location: LatLng? get() = if (latitude != null && longitude != null) LatLng(latitude, longitude).takeIf { it.isValid } else null
}

object StopLogic {
    fun statusOf(packages: List<PackageStatus>): StopStatus = when {
        packages.isEmpty() -> StopStatus.PENDING
        packages.none { it == PackageStatus.PENDING } -> StopStatus.COMPLETED
        packages.all { it == PackageStatus.PENDING } -> StopStatus.PENDING
        else -> StopStatus.IN_PROGRESS
    }
}

data class Progress(
    val totalPackages: Int,
    val totalStops: Int,
    val delivered: Int,
    val pending: Int,
    val notDelivered: Int,
    val completedStops: Int,
) {
    /** Porcentagem de pacotes já resolvidos (entregues + não entregues). */
    val percent: Int get() = if (totalPackages == 0) 0 else ((delivered + notDelivered) * 100) / totalPackages

    companion object {
        fun of(packages: List<PackageRecord>, stops: List<StopRecord>) = Progress(
            totalPackages = packages.size,
            totalStops = stops.size,
            delivered = packages.count { it.status == PackageStatus.DELIVERED },
            pending = packages.count { it.status == PackageStatus.PENDING },
            notDelivered = packages.count { it.status == PackageStatus.NOT_DELIVERED },
            completedStops = stops.count { it.status == StopStatus.COMPLETED },
        )
    }
}

/** Resultado de uma bipagem. */
sealed class ScanOutcome {
    /** Código vazio ou ilegível. */
    data class Invalid(val raw: String) : ScanOutcome()

    /** Código não existe no romaneio: "PACOTE NÃO ENCONTRADO". Nunca associa a outro pacote. */
    data class NotFound(val code: String) : ScanOutcome()

    /** O mesmo código existe em mais de um pacote (só possível se o usuário permitiu duplicados). Nunca escolhe um. */
    data class Ambiguous(val code: String, val matches: List<PackageRecord>) : ScanOutcome()

    data class Found(
        val pkg: PackageRecord,
        val stop: StopRecord,
        /** Todos os pacotes da parada, inclusive o bipado. */
        val stopPackages: List<PackageRecord>,
        /** true se já tinha sido bipado antes: "PACOTE JÁ IDENTIFICADO". */
        val alreadyIdentified: Boolean,
        /** true se a parada já estava concluída ao bipar: mostrar alerta. */
        val stopAlreadyCompleted: Boolean,
    ) : ScanOutcome()
}

/** Armazenamento usado pelo motor. No app é implementado com Room; nos testes, em memória. */
interface DeliveryStore {
    fun <T> transaction(block: () -> T): T
    fun packagesByKey(romaneioId: Long, key: String): List<PackageRecord>
    fun packageById(id: Long): PackageRecord?
    fun packagesOfStop(romaneioId: Long, stopNumber: Int): List<PackageRecord>
    fun stop(romaneioId: Long, stopNumber: Int): StopRecord?
    fun savePackage(p: PackageRecord)
    fun saveStopStatus(romaneioId: Long, stopNumber: Int, status: StopStatus)
}

class DeliveryEngine(private val store: DeliveryStore) {

    /**
     * Bipagem. Regras:
     *  - só busca por igualdade EXATA da chave normalizada (nunca aproximada);
     *  - nunca altera parada, endereço, status de entrega nem código;
     *  - só grava a data/hora da primeira identificação.
     */
    fun scan(romaneioId: Long, raw: String, now: Long): ScanOutcome = store.transaction {
        val key = CodeNormalizer.key(raw)
        if (key.isEmpty()) return@transaction ScanOutcome.Invalid(raw)
        val matches = store.packagesByKey(romaneioId, key)
        when {
            matches.isEmpty() -> ScanOutcome.NotFound(CodeNormalizer.clean(raw))
            matches.size > 1 -> ScanOutcome.Ambiguous(CodeNormalizer.clean(raw), matches)
            else -> {
                val pkg = matches.single()
                val stop = store.stop(romaneioId, pkg.stopNumber)
                    ?: error("Inconsistência: pacote ${pkg.trackingCode} aponta para a parada ${pkg.stopNumber}, que não existe.")
                val already = pkg.scannedAt != null
                val saved = if (already) pkg else pkg.copy(scannedAt = now).also { store.savePackage(it) }
                ScanOutcome.Found(
                    pkg = saved,
                    stop = stop,
                    stopPackages = store.packagesOfStop(romaneioId, pkg.stopNumber),
                    alreadyIdentified = already,
                    stopAlreadyCompleted = stop.status == StopStatus.COMPLETED,
                )
            }
        }
    }

    fun markDelivered(packageId: Long, now: Long): StopStatus = update(packageId) {
        it.copy(status = PackageStatus.DELIVERED, deliveredAt = now, failedAt = null, failureReason = null, failureNote = null)
    }

    fun markNotDelivered(packageId: Long, reason: FailureReason, note: String?, now: Long): StopStatus = update(packageId) {
        it.copy(status = PackageStatus.NOT_DELIVERED, failedAt = now, deliveredAt = null, failureReason = reason, failureNote = note?.trim()?.takeIf { n -> n.isNotEmpty() })
    }

    /** Desfaz uma marcação (volta para pendente). */
    fun reopen(packageId: Long): StopStatus = update(packageId) {
        it.copy(status = PackageStatus.PENDING, deliveredAt = null, failedAt = null, failureReason = null, failureNote = null)
    }

    /** Botão ENTREGUE da parada: só afeta pacotes ainda PENDENTES. O que já foi classificado não é sobrescrito. */
    fun markStopDelivered(romaneioId: Long, stopNumber: Int, now: Long): StopStatus = store.transaction {
        store.packagesOfStop(romaneioId, stopNumber).filter { it.status == PackageStatus.PENDING }.forEach {
            store.savePackage(it.copy(status = PackageStatus.DELIVERED, deliveredAt = now))
        }
        refreshStop(romaneioId, stopNumber)
    }

    /** Botão NÃO ENTREGUE da parada: só afeta pacotes ainda PENDENTES. */
    fun markStopNotDelivered(romaneioId: Long, stopNumber: Int, reason: FailureReason, note: String?, now: Long): StopStatus = store.transaction {
        store.packagesOfStop(romaneioId, stopNumber).filter { it.status == PackageStatus.PENDING }.forEach {
            store.savePackage(it.copy(status = PackageStatus.NOT_DELIVERED, failedAt = now, failureReason = reason, failureNote = note?.trim()?.takeIf { n -> n.isNotEmpty() }))
        }
        refreshStop(romaneioId, stopNumber)
    }

    private fun update(packageId: Long, change: (PackageRecord) -> PackageRecord): StopStatus = store.transaction {
        val pkg = store.packageById(packageId) ?: error("Pacote $packageId não encontrado.")
        store.savePackage(change(pkg))
        refreshStop(pkg.romaneioId, pkg.stopNumber)
    }

    private fun refreshStop(romaneioId: Long, stopNumber: Int): StopStatus {
        val status = StopLogic.statusOf(store.packagesOfStop(romaneioId, stopNumber).map { it.status })
        store.saveStopStatus(romaneioId, stopNumber, status)
        return status
    }
}
