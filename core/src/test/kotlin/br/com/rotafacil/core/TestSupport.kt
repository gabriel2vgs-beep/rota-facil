package br.com.rotafacil.core

/** Armazenamento em memória só para testes. No app real a mesma interface é implementada com Room. */
class InMemoryDeliveryStore : DeliveryStore {
    val packages = LinkedHashMap<Long, PackageRecord>()
    val stops = LinkedHashMap<Pair<Long, Int>, StopRecord>()
    var transactions = 0

    override fun <T> transaction(block: () -> T): T { transactions++; return block() }
    override fun packagesByKey(romaneioId: Long, key: String) =
        packages.values.filter { it.romaneioId == romaneioId && it.lookupKey == key }
    override fun packageById(id: Long) = packages[id]
    override fun packagesOfStop(romaneioId: Long, stopNumber: Int) =
        packages.values.filter { it.romaneioId == romaneioId && it.stopNumber == stopNumber }.sortedBy { it.sequence }
    override fun stop(romaneioId: Long, stopNumber: Int) = stops[romaneioId to stopNumber]
    override fun savePackage(p: PackageRecord) { packages[p.id] = p }
    override fun saveStopStatus(romaneioId: Long, stopNumber: Int, status: StopStatus) {
        val key = romaneioId to stopNumber
        stops[key] = stops.getValue(key).copy(status = status)
    }

    fun load(romaneioId: Long, preview: ImportPreview): InMemoryDeliveryStore {
        var pid = 1L
        preview.stops.forEachIndexed { i, s ->
            stops[romaneioId to s.stopNumber] = StopRecord(
                id = (i + 1).toLong(), romaneioId = romaneioId, stopNumber = s.stopNumber, address = s.address,
                neighborhood = s.neighborhood, city = s.city, latitude = s.latitude, longitude = s.longitude,
                optimizedOrder = null, packageCount = s.packages.size,
            )
        }
        preview.packages.forEach { p ->
            packages[pid] = PackageRecord(
                id = pid, romaneioId = romaneioId, trackingCode = p.trackingCode, lookupKey = p.lookupKey,
                fullAddress = p.fullAddress, complement = p.complement, neighborhood = p.neighborhood,
                latitude = p.latitude, longitude = p.longitude, stopNumber = p.stop, sequence = p.sequence,
            )
            pid++
        }
        return this
    }
}
