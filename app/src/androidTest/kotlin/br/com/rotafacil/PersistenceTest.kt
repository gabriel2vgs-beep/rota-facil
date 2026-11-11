package br.com.rotafacil

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.rotafacil.core.*
import br.com.rotafacil.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Roda NO APARELHO/EMULADOR (./gradlew :app:connectedAndroidTest). Não foi executado durante o desenvolvimento
 * (não havia Android SDK disponível). Verifica que o banco Room guarda tudo e que os dados sobrevivem a fechar/reabrir.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun open(name: String) = Room.databaseBuilder(ctx, AppDb::class.java, name).allowMainThreadQueries().build()

    @Test fun dados_sobrevivem_a_fechar_e_reabrir_o_banco() {
        val name = "teste_persistencia.db"; ctx.deleteDatabase(name)
        var db = open(name)
        val dao = db.dao()
        val rid = dao.let { kotlinx.coroutines.runBlocking {
            val id = it.insertRomaneio(RomaneioEntity(importedAt = 1, fileName = "t.csv", totalPackages = 3, totalStops = 2, contentHash = "h"))
            it.insertStops(listOf(
                StopEntity(romaneioId = id, stopNumber = 1, address = "Rua A, 1", neighborhood = "", city = "", latitude = -23.4, longitude = -46.1, optimizedOrder = null, packageCount = 2, status = "PENDING"),
                StopEntity(romaneioId = id, stopNumber = 2, address = "Rua B, 2", neighborhood = "", city = "", latitude = -23.5, longitude = -46.2, optimizedOrder = null, packageCount = 1, status = "PENDING")))
            fun p(code: String, stop: Int, seq: Int) = PackageEntity(romaneioId = id, trackingCode = code, lookupKey = code, fullAddress = "x", complement = "", neighborhood = "",
                latitude = null, longitude = null, stopNumber = stop, sequence = seq, status = "PENDING", failureReason = null, failureNote = null, scannedAt = null, deliveredAt = null, failedAt = null)
            it.insertPackages(listOf(p("BR1", 1, 1), p("BR2", 1, 2), p("BR3", 2, 3)))
            id
        } }
        val engine = DeliveryEngine(RoomDeliveryStore(db))
        assertTrue(engine.scan(rid, "BR1", 111) is ScanOutcome.Found)
        assertEquals(StopStatus.IN_PROGRESS, engine.markDelivered(dao.packagesByKey(rid, "BR1").single().id, 222))
        db.close()   // simula o app sendo fechado

        db = open(name)
        val after = RoomDeliveryStore(db)
        val p1 = after.packagesByKey(rid, "BR1").single()
        assertEquals(PackageStatus.DELIVERED, p1.status); assertEquals(111L, p1.scannedAt); assertEquals(222L, p1.deliveredAt)
        assertEquals(PackageStatus.PENDING, after.packagesByKey(rid, "BR2").single().status)
        assertEquals(StopStatus.IN_PROGRESS, after.stop(rid, 1)!!.status)
        assertEquals(3, db.dao().countPackages(rid)); assertEquals(2, db.dao().countStops(rid))
        val again = DeliveryEngine(after).scan(rid, "BR1", 999) as ScanOutcome.Found
        assertTrue(again.alreadyIdentified)
        db.close(); ctx.deleteDatabase(name)
    }

    @Test fun fotos_de_prova_sobrevivem_e_somem_junto_com_o_romaneio() {
        val name = "teste_fotos.db"; ctx.deleteDatabase(name)
        var db = open(name); val dao = db.dao()
        val (rid, pkgId) = kotlinx.coroutines.runBlocking {
            val id = dao.insertRomaneio(RomaneioEntity(importedAt = 1, fileName = "t.csv", totalPackages = 1, totalStops = 1, contentHash = "h"))
            dao.insertStops(listOf(StopEntity(romaneioId = id, stopNumber = 1, address = "Rua A", neighborhood = "", city = "", latitude = null, longitude = null, optimizedOrder = null, packageCount = 1, status = "PENDING")))
            dao.insertPackages(listOf(PackageEntity(romaneioId = id, trackingCode = "BR1", lookupKey = "BR1", fullAddress = "x", complement = "", neighborhood = "", latitude = null, longitude = null,
                stopNumber = 1, sequence = 1, status = "PENDING", failureReason = null, failureNote = null, scannedAt = null, deliveredAt = null, failedAt = null)))
            val pid = dao.packagesByKey(id, "BR1").single().id
            dao.insertProof(ProofPhotoEntity(romaneioId = id, stopNumber = 1, packageId = null, filePath = "/x/a.jpg", takenAt = 5, latitude = -23.4, longitude = -46.1, sha256 = "aa"))
            dao.insertProof(ProofPhotoEntity(romaneioId = id, stopNumber = 1, packageId = pid, filePath = "/x/b.jpg", takenAt = 6, latitude = null, longitude = null, sha256 = "bb"))
            id to pid
        }
        db.close(); db = open(name)
        kotlinx.coroutines.runBlocking {
            assertEquals(2, db.dao().countProofs(rid))
            val stopPhoto = db.dao().proofById(1)!!.toRecord()
            val pkg = db.dao().packageById(pkgId)!!.toRecord()
            assertTrue(ProofRules.covers(stopPhoto, pkg))                       // foto da parada cobre o pacote
            db.dao().deleteRomaneio(rid)
            assertEquals(0, db.dao().countProofs(rid))                          // apagar o romaneio apaga as fotos do banco
        }
        db.close(); ctx.deleteDatabase(name)
    }
}
