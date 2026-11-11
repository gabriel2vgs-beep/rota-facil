package br.com.rotafacil.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

class ProofTest {
    private val rid = 1L
    private lateinit var store: InMemoryDeliveryStore
    private lateinit var engine: DeliveryEngine
    private lateinit var rows: List<List<String>>

    @Before fun setUp() {
        store = InMemoryDeliveryStore().load(rid, ImportTest.preview()); engine = DeliveryEngine(store)
        rows = ImportTest.table().rows
    }

    private fun photo(id: Long, stop: Int, pkg: Long?, romaneio: Long = rid) =
        ProofPhotoRecord(id, romaneio, stop, pkg, "/x/$id.jpg", 1000L + id, -23.4, -46.1, "h$id")

    private fun stopWith(n: Int): Pair<Int, List<PackageRecord>> {
        val stop = rows.groupBy({ it[2].toInt() }, { it[3] }).entries.first { it.value.size == n }.key
        return stop to store.packagesOfStop(rid, stop)
    }

    @Test fun foto_da_parada_cobre_todos_os_pacotes_dela_e_so_dela() {
        val (stop, pk) = stopWith(3)
        val p = photo(1, stop, null)
        assertTrue(pk.all { ProofRules.covers(p, it) })
        val outsider = store.packages.values.first { it.stopNumber != stop }
        assertFalse(ProofRules.covers(p, outsider))
    }

    @Test fun foto_de_pacote_cobre_so_aquele_pacote() {
        val (stop, pk) = stopWith(3)
        val p = photo(1, stop, pk[0].id)
        assertTrue(ProofRules.covers(p, pk[0]))
        assertFalse(ProofRules.covers(p, pk[1])); assertFalse(ProofRules.covers(p, pk[2]))
    }

    @Test fun foto_de_outro_romaneio_ou_outra_parada_nao_cobre() {
        val (stop, pk) = stopWith(2)
        assertFalse(ProofRules.covers(photo(1, stop, null, romaneio = 99), pk[0]))
        assertFalse(ProofRules.covers(photo(2, stop + 1, null), pk[0]))
        assertFalse(ProofRules.covers(photo(3, stop, pk[0].id + 1000), pk[0]))
    }

    @Test fun pendentes_sem_prova() {
        val (stop, pk) = stopWith(3)
        val photos = listOf(photo(1, stop, pk[0].id))
        assertEquals(listOf(pk[1].id, pk[2].id), ProofRules.pendingWithoutProof(store.packagesOfStop(rid, stop), photos).map { it.id })
        // pacote já entregue não conta como "sem prova pendente"
        engine.markDelivered(pk[1].id, 5)
        assertEquals(listOf(pk[2].id), ProofRules.pendingWithoutProof(store.packagesOfStop(rid, stop), photos).map { it.id })
        // foto da parada resolve todos
        assertTrue(ProofRules.pendingWithoutProof(store.packagesOfStop(rid, stop), photos + photo(2, stop, null)).isEmpty())
    }

    @Test fun exigir_foto_so_quando_o_ajuste_esta_ligado_e_falta_prova() {
        val (stop, pk) = stopWith(2)
        assertFalse(ProofRules.needsPhoto(false, pk, emptyList()))
        assertTrue(ProofRules.needsPhoto(true, pk, emptyList()))
        assertTrue(ProofRules.needsPhoto(true, pk, listOf(photo(1, stop, pk[0].id))))   // falta o segundo
        assertFalse(ProofRules.needsPhoto(true, pk, listOf(photo(1, stop, null))))
        assertFalse(ProofRules.needsPhoto(true, emptyList(), emptyList()))
    }

    @Test fun foto_nao_altera_status_nem_parada_do_pacote() {
        val (stop, pk) = stopWith(3)
        val before = store.packagesOfStop(rid, stop)
        ProofRules.hasProof(pk[0], listOf(photo(1, stop, null)))
        assertEquals(before, store.packagesOfStop(rid, stop))
        assertEquals(StopStatus.PENDING, store.stop(rid, stop)!!.status)
    }

    @Test fun redimensionamento_mantem_proporcao_e_nunca_aumenta() {
        assertEquals(1600 to 1200, ProofRules.scaledSize(4000, 3000))
        assertEquals(1200 to 1600, ProofRules.scaledSize(3000, 4000))      // retrato
        assertEquals(800 to 600, ProofRules.scaledSize(800, 600))          // menor que o máximo: intacta
        assertEquals(1600 to 1600, ProofRules.scaledSize(1600, 1600))
        assertEquals(1600 to 1, ProofRules.scaledSize(16000, 1))           // extremo: nunca zero
        try { ProofRules.scaledSize(0, 10); fail() } catch (_: IllegalArgumentException) { }
        for ((w, h) in listOf(4032 to 3024, 3024 to 4032, 5000 to 5000, 1601 to 900)) {
            val (a, b) = ProofRules.scaledSize(w, h)
            assertTrue(maxOf(a, b) <= 1600)
            assertEquals(w.toDouble() / h, a.toDouble() / b, 0.01)
        }
    }

    @Test fun sample_size_nunca_deixa_a_imagem_menor_que_o_alvo() {
        for ((w, h) in listOf(4032 to 3024, 8000 to 6000, 1000 to 800, 3200 to 100, 1600 to 1600)) {
            val s = ProofRules.sampleSize(w, h)
            assertTrue(s >= 1 && (s and (s - 1)) == 0)   // potência de 2
            assertTrue("${w}x$h s=$s", maxOf(w, h) / s >= minOf(1600, maxOf(w, h)))
        }
        assertEquals(2, ProofRules.sampleSize(4032, 3024))   // 2016 px >= 1600; com 4 cairia para 1008
        assertEquals(4, ProofRules.sampleSize(8000, 6000))   // 2000 px >= 1600; com 8 cairia para 1000
        assertEquals(1, ProofRules.sampleSize(1600, 1200)); assertEquals(1, ProofRules.sampleSize(3199, 100))
    }

    @Test fun nome_de_arquivo_e_seguro_e_deterministico() {
        val z = ZoneId.of("America/Sao_Paulo")
        val t = java.time.ZonedDateTime.of(2026, 11, 10, 9, 30, 15, 0, z).toInstant().toEpochMilli()
        assertEquals("parada-007_BR266773045760C_20261110-093015.jpg", ProofRules.fileName(7, "BR266773045760C", t, z))
        assertEquals("parada-171_20261110-093015.jpg", ProofRules.fileName(171, null, t, z))
        assertEquals("parada-007_20261110-093015_2.jpg", ProofRules.fileName(7, "", t, z, unique = 2))
        val evil = ProofRules.fileName(1, "../../etc/passwd\u0000/x", t, z)
        assertFalse(evil.contains("/") || evil.contains("..") || evil.contains("\u0000") || evil.contains("\\"))
        assertTrue(evil.endsWith(".jpg"))
    }
}
