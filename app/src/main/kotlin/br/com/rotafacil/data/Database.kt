package br.com.rotafacil.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import br.com.rotafacil.core.DeliveryStore
import br.com.rotafacil.core.FailureReason
import br.com.rotafacil.core.PackageRecord
import br.com.rotafacil.core.ProofPhotoRecord
import br.com.rotafacil.core.PackageStatus
import br.com.rotafacil.core.StopRecord
import br.com.rotafacil.core.StopStatus
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.Callable

@Entity(tableName = "romaneios")
data class RomaneioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val importedAt: Long,
    val fileName: String,
    val totalPackages: Int,
    val totalStops: Int,
    val contentHash: String,
)

@Entity(
    tableName = "packages",
    foreignKeys = [ForeignKey(RomaneioEntity::class, ["id"], ["romaneioId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("romaneioId", "lookupKey"), Index("romaneioId", "stopNumber")],
)
data class PackageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val romaneioId: Long,
    val trackingCode: String,
    val lookupKey: String,
    val fullAddress: String,
    val complement: String,
    val neighborhood: String,
    val latitude: Double?,
    val longitude: Double?,
    val stopNumber: Int,
    val sequence: Int,
    val status: String,
    val failureReason: String?,
    val failureNote: String?,
    val scannedAt: Long?,
    val deliveredAt: Long?,
    val failedAt: Long?,
)

@Entity(
    tableName = "stops",
    foreignKeys = [ForeignKey(RomaneioEntity::class, ["id"], ["romaneioId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("romaneioId", "stopNumber", unique = true)],
)
data class StopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val romaneioId: Long,
    val stopNumber: Int,
    val address: String,
    val neighborhood: String,
    val city: String,
    val latitude: Double?,
    val longitude: Double?,
    val optimizedOrder: Int?,
    val packageCount: Int,
    val status: String,
)

@Entity(
    tableName = "proof_photos",
    foreignKeys = [
        ForeignKey(RomaneioEntity::class, ["id"], ["romaneioId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PackageEntity::class, ["id"], ["packageId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("romaneioId", "stopNumber"), Index("packageId")],
)
data class ProofPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val romaneioId: Long,
    val stopNumber: Int,
    /** null = foto da parada inteira. */
    val packageId: Long?,
    val filePath: String,
    val takenAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val sha256: String,
)

data class KeyRow(val romaneioId: Long, val lookupKey: String)

@Dao
interface RotaDao {
    @Insert suspend fun insertRomaneio(r: RomaneioEntity): Long
    @Insert suspend fun insertPackages(list: List<PackageEntity>)
    @Insert suspend fun insertStops(list: List<StopEntity>)

    @Query("SELECT * FROM romaneios ORDER BY id DESC LIMIT 1") fun latestRomaneio(): Flow<RomaneioEntity?>
    @Query("SELECT * FROM romaneios") suspend fun romaneios(): List<RomaneioEntity>
    @Query("SELECT romaneioId, lookupKey FROM packages") suspend fun allKeys(): List<KeyRow>
    @Query("SELECT COUNT(*) FROM packages WHERE romaneioId = :rid") suspend fun countPackages(rid: Long): Int
    @Query("SELECT COUNT(*) FROM stops WHERE romaneioId = :rid") suspend fun countStops(rid: Long): Int
    @Query("DELETE FROM romaneios WHERE id = :id") suspend fun deleteRomaneio(id: Long)

    // Fotos de prova
    @Insert suspend fun insertProof(p: ProofPhotoEntity): Long
    @Query("SELECT * FROM proof_photos WHERE romaneioId = :rid ORDER BY takenAt") fun proofsFlow(rid: Long): Flow<List<ProofPhotoEntity>>
    @Query("SELECT * FROM proof_photos WHERE id = :id") suspend fun proofById(id: Long): ProofPhotoEntity?
    @Query("DELETE FROM proof_photos WHERE id = :id") suspend fun deleteProof(id: Long)
    @Query("SELECT COUNT(*) FROM proof_photos WHERE romaneioId = :rid") suspend fun countProofs(rid: Long): Int

    @Query("SELECT * FROM packages WHERE romaneioId = :rid ORDER BY sequence") fun packagesFlow(rid: Long): Flow<List<PackageEntity>>
    @Query("SELECT * FROM stops WHERE romaneioId = :rid ORDER BY stopNumber") fun stopsFlow(rid: Long): Flow<List<StopEntity>>

    // Usadas pelo motor de entrega (chamadas em thread de fundo, dentro de transação)
    @Query("SELECT * FROM packages WHERE romaneioId = :rid AND lookupKey = :key") fun packagesByKey(rid: Long, key: String): List<PackageEntity>
    @Query("SELECT * FROM packages WHERE id = :id") fun packageById(id: Long): PackageEntity?
    @Query("SELECT * FROM packages WHERE romaneioId = :rid AND stopNumber = :stop ORDER BY sequence") fun packagesOfStop(rid: Long, stop: Int): List<PackageEntity>
    @Query("SELECT * FROM stops WHERE romaneioId = :rid AND stopNumber = :stop") fun stop(rid: Long, stop: Int): StopEntity?
    @Update fun updatePackage(p: PackageEntity)
    @Query("UPDATE stops SET status = :status WHERE romaneioId = :rid AND stopNumber = :stop") fun updateStopStatus(rid: Long, stop: Int, status: String)
    @Query("UPDATE stops SET optimizedOrder = :order WHERE romaneioId = :rid AND stopNumber = :stop") fun updateStopOrder(rid: Long, stop: Int, order: Int)
}

@Database(entities = [RomaneioEntity::class, PackageEntity::class, StopEntity::class, ProofPhotoEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): RotaDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "rotafacil.db").build().also { instance = it }
        }
    }
}

// --- conversões entre Room e o modelo do core ---------------------------------------------

fun PackageEntity.toRecord() = PackageRecord(
    id, romaneioId, trackingCode, lookupKey, fullAddress, complement, neighborhood, latitude, longitude, stopNumber, sequence,
    status = runCatching { PackageStatus.valueOf(status) }.getOrDefault(PackageStatus.PENDING),
    failureReason = failureReason?.let { r -> runCatching { FailureReason.valueOf(r) }.getOrNull() },
    failureNote = failureNote, scannedAt = scannedAt, deliveredAt = deliveredAt, failedAt = failedAt,
)

fun PackageRecord.toEntity() = PackageEntity(
    id, romaneioId, trackingCode, lookupKey, fullAddress, complement, neighborhood, latitude, longitude, stopNumber, sequence,
    status.name, failureReason?.name, failureNote, scannedAt, deliveredAt, failedAt,
)

fun ProofPhotoEntity.toRecord() = ProofPhotoRecord(id, romaneioId, stopNumber, packageId, filePath, takenAt, latitude, longitude, sha256)

fun StopEntity.toRecord() = StopRecord(
    id, romaneioId, stopNumber, address, neighborhood, city, latitude, longitude, optimizedOrder, packageCount,
    runCatching { StopStatus.valueOf(status) }.getOrDefault(StopStatus.PENDING),
)

/** Implementação do armazenamento do motor de entrega com Room (tudo gravado em transação). */
class RoomDeliveryStore(private val db: AppDb) : DeliveryStore {
    private val dao = db.dao()
    override fun <T> transaction(block: () -> T): T = db.runInTransaction(Callable { block() })
    override fun packagesByKey(romaneioId: Long, key: String) = dao.packagesByKey(romaneioId, key).map { it.toRecord() }
    override fun packageById(id: Long) = dao.packageById(id)?.toRecord()
    override fun packagesOfStop(romaneioId: Long, stopNumber: Int) = dao.packagesOfStop(romaneioId, stopNumber).map { it.toRecord() }
    override fun stop(romaneioId: Long, stopNumber: Int) = dao.stop(romaneioId, stopNumber)?.toRecord()
    override fun savePackage(p: PackageRecord) = dao.updatePackage(p.toEntity())
    override fun saveStopStatus(romaneioId: Long, stopNumber: Int, status: StopStatus) = dao.updateStopStatus(romaneioId, stopNumber, status.name)
}
