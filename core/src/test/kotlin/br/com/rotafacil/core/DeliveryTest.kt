package br.com.rotafacil.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Bipagem e regras de parada, com os 200 pacotes reais da planilha de referência. */
class DeliveryTest {
    private val rid = 1L
    private lateinit var preview: ImportPreview
    private lateinit var store: InMemoryDeliveryStore
    private lateinit var engine: DeliveryEngine
    private lateinit var rows: List<List<String>>   // verdade independente: células cruas da planilha

    @Before fun setUp() {
        preview = ImportTest.preview()
        rows = ImportTest.table().rows
        store = InMemoryDeliveryStore().load(rid, preview)
        engine = DeliveryEngine(store)
    }

    private fun stopOf(code: String) = rows.first { it[3] == code }[2].toInt()
    private fun idOf(code: String) = store.packages.values.first { it.trackingCode == code }.id

    @Test fun bipar_cada_um_dos_200_codigos_retorna_a_parada_correta() {
        var checked = 0
        for (row in rows) {
            val code = row[3]; val expectedStop = row[2].toInt()
            val out = engine.scan(rid, code, now = 1000L + checked)
            assertTrue("código $code: $out", out is ScanOutcome.Found)
            out as ScanOutcome.Found
            assertEquals("parada de $code", expectedStop, out.stop.stopNumber)
            assertEquals(expectedStop, out.pkg.stopNumber)
            assertEquals(code, out.pkg.trackingCode)
            // endereço mostrado é o da planilha para aquele pacote
            assertEquals(row[4].trim(), out.pkg.fullAddress)
            assertTrue(out.stop.address.isNotBlank())
            // lista de pacotes da parada = exatamente os pacotes da planilha com aquele Stop
            val expectedSiblings = rows.filter { it[2].toInt() == expectedStop }.map { it[3] }.toSet()
            assertEquals(expectedSiblings, out.stopPackages.map { it.trackingCode }.toSet())
            assertEquals(out.stop.packageCount, out.stopPackages.size)
            assertFalse(out.alreadyIdentified)
            checked++
        }
        assertEquals(200, checked)
    }

    @Test fun codigo_desconhecido_nunca_e_associado_a_outro_pacote() {
        val known = rows.map { it[3] }.toSet()
        assertEquals(ScanOutcome.NotFound("SPX000000000"), engine.scan(rid, "SPX000000000", 1))
        var n = 0
        for (code in known) {
            // troca o último caractere por outro: parecido, mas diferente
            val last = code.last()
            val alt = code.dropLast(1) + (if (last == 'X') 'Y' else 'X')
            if (alt in known) continue
            assertTrue("quase-igual $alt", engine.scan(rid, alt, 1) is ScanOutcome.NotFound)
            assertTrue("truncado", engine.scan(rid, code.dropLast(1), 1) is ScanOutcome.NotFound)
            assertTrue("com sufixo", engine.scan(rid, code + "0", 1) is ScanOutcome.NotFound)
            assertTrue("com prefixo", engine.scan(rid, "0$code", 1) is ScanOutcome.NotFound)
            n++
        }
        assertTrue(n > 190)
        assertTrue(store.packages.values.none { it.scannedAt != null })   // nada foi marcado como bipado
    }

    @Test fun codigo_vazio_e_invalido() {
        assertTrue(engine.scan(rid, "   \n", 1) is ScanOutcome.Invalid)
        assertTrue(engine.scan(rid, "", 1) is ScanOutcome.Invalid)
    }

    @Test fun leitura_com_espaco_ou_quebra_de_linha_nas_pontas_ainda_acha_o_pacote_certo() {
        val code = rows[10][3]
        val out = engine.scan(rid, " $code\r\n", 1) as ScanOutcome.Found
        assertEquals(code, out.pkg.trackingCode)
        assertEquals(rows[10][2].toInt(), out.stop.stopNumber)
        val lower = engine.scan(rid, code.lowercase(), 2) as ScanOutcome.Found
        assertEquals(code, lower.pkg.trackingCode)
    }

    @Test fun segunda_bipagem_avisa_ja_identificado_sem_alterar_nada() {
        val code = rows[5][3]
        val first = engine.scan(rid, code, 111) as ScanOutcome.Found
        assertFalse(first.alreadyIdentified); assertEquals(111L, first.pkg.scannedAt)
        val second = engine.scan(rid, code, 999) as ScanOutcome.Found
        assertTrue(second.alreadyIdentified)
        assertEquals("hora da 1ª bipagem é mantida", 111L, second.pkg.scannedAt)
        assertEquals(first.stop.stopNumber, second.stop.stopNumber)
        assertEquals(PackageStatus.PENDING, second.pkg.status)   // bipar não entrega
    }

    @Test fun bipar_nao_altera_paradas_nem_codigos() {
        val before = store.packages.values.map { Triple(it.id, it.trackingCode, it.stopNumber) }
        rows.forEach { engine.scan(rid, it[3], 5) }
        rows.forEach { engine.scan(rid, it[3], 6) }
        assertEquals(before, store.packages.values.map { Triple(it.id, it.trackingCode, it.stopNumber) })
        assertEquals(200, store.packages.values.count { it.scannedAt == 5L })
    }

    @Test fun parada_concluida_gera_alerta_ao_bipar_pacote_dela() {
        val code = rows[0][3]; val stop = stopOf(code)
        engine.markDelivered(idOf(code), 10)
        assertEquals(StopStatus.COMPLETED, store.stop(rid, stop)!!.status)
        val out = engine.scan(rid, code, 20) as ScanOutcome.Found
        assertTrue(out.stopAlreadyCompleted)
    }

    private fun stopWith(n: Int): Pair<Int, List<String>> {
        val grouped = rows.groupBy({ it[2].toInt() }, { it[3] })
        return grouped.entries.first { it.value.size == n }.let { it.key to it.value }
    }

    @Test fun concluir_um_pacote_nao_conclui_a_parada() {
        val (stop, codes) = stopWith(3)
        assertEquals(StopStatus.PENDING, store.stop(rid, stop)!!.status)
        assertEquals(StopStatus.IN_PROGRESS, engine.markDelivered(idOf(codes[0]), 1))
        assertEquals(StopStatus.IN_PROGRESS, engine.markDelivered(idOf(codes[1]), 2))
        assertEquals(StopStatus.IN_PROGRESS, store.stop(rid, stop)!!.status)
        // o terceiro continua pendente
        assertEquals(PackageStatus.PENDING, store.packageById(idOf(codes[2]))!!.status)
    }

    @Test fun parada_com_dois_entregues_e_um_nao_entregue_e_representada_corretamente() {
        val (stop, codes) = stopWith(3)
        engine.markDelivered(idOf(codes[0]), 1)
        engine.markDelivered(idOf(codes[1]), 2)
        val s = engine.markNotDelivered(idOf(codes[2]), FailureReason.ABSENT, null, 3)
        assertEquals(StopStatus.COMPLETED, s)
        val pk = store.packagesOfStop(rid, stop)
        assertEquals(2, pk.count { it.status == PackageStatus.DELIVERED })
        assertEquals(1, pk.count { it.status == PackageStatus.NOT_DELIVERED })
        val failed = pk.single { it.status == PackageStatus.NOT_DELIVERED }
        assertEquals(codes[2], failed.trackingCode)
        assertEquals(FailureReason.ABSENT, failed.failureReason)
        assertEquals(3L, failed.failedAt); assertNull(failed.deliveredAt)
        assertEquals(1L, pk.first { it.trackingCode == codes[0] }.deliveredAt)
    }

    @Test fun parada_de_quatro_pacotes_com_todos_os_motivos() {
        val (stop, codes) = stopWith(4)
        val reasons = listOf(FailureReason.ADDRESS_NOT_FOUND, FailureReason.REFUSED, FailureReason.ACCESS_PROBLEM, FailureReason.OTHER)
        codes.forEachIndexed { i, c -> engine.markNotDelivered(idOf(c), reasons[i], if (reasons[i] == FailureReason.OTHER) "  portão trancado " else null, 5) }
        assertEquals(StopStatus.COMPLETED, store.stop(rid, stop)!!.status)
        val other = store.packageById(idOf(codes[3]))!!
        assertEquals("portão trancado", other.failureNote)
    }

    @Test fun botao_entregue_da_parada_nao_sobrescreve_pacote_ja_classificado() {
        val (stop, codes) = stopWith(3)
        engine.markNotDelivered(idOf(codes[0]), FailureReason.REFUSED, null, 1)
        val s = engine.markStopDelivered(rid, stop, 2)
        assertEquals(StopStatus.COMPLETED, s)
        assertEquals(PackageStatus.NOT_DELIVERED, store.packageById(idOf(codes[0]))!!.status)
        assertEquals(PackageStatus.DELIVERED, store.packageById(idOf(codes[1]))!!.status)
        assertEquals(PackageStatus.DELIVERED, store.packageById(idOf(codes[2]))!!.status)
    }

    @Test fun botao_nao_entregue_da_parada_so_afeta_pendentes() {
        val (stop, codes) = stopWith(2)
        engine.markDelivered(idOf(codes[0]), 1)
        engine.markStopNotDelivered(rid, stop, FailureReason.ABSENT, null, 2)
        assertEquals(PackageStatus.DELIVERED, store.packageById(idOf(codes[0]))!!.status)
        assertEquals(PackageStatus.NOT_DELIVERED, store.packageById(idOf(codes[1]))!!.status)
    }

    @Test fun reabrir_pacote_volta_a_parada_para_em_andamento() {
        val (stop, codes) = stopWith(2)
        codes.forEach { engine.markDelivered(idOf(it), 1) }
        assertEquals(StopStatus.COMPLETED, store.stop(rid, stop)!!.status)
        assertEquals(StopStatus.IN_PROGRESS, engine.reopen(idOf(codes[0])))
        val p = store.packageById(idOf(codes[0]))!!
        assertEquals(PackageStatus.PENDING, p.status); assertNull(p.deliveredAt)
    }

    @Test fun marcar_pacote_nao_afeta_outras_paradas() {
        val (stop, codes) = stopWith(2)
        engine.markDelivered(idOf(codes[0]), 1)
        val others = store.packages.values.filter { it.stopNumber != stop }
        assertTrue(others.all { it.status == PackageStatus.PENDING })
        assertTrue(store.stops.values.filter { it.stopNumber != stop }.all { it.status == StopStatus.PENDING })
    }

    @Test fun progresso_e_contagens() {
        var pr = Progress.of(store.packages.values.toList(), store.stops.values.toList())
        assertEquals(200, pr.totalPackages); assertEquals(171, pr.totalStops); assertEquals(200, pr.pending); assertEquals(0, pr.percent)
        val (_, three) = stopWith(3)
        three.forEach { engine.markDelivered(idOf(it), 1) }
        engine.markNotDelivered(idOf(rows[0][3].takeIf { it !in three } ?: rows[1][3]), FailureReason.OTHER, null, 2)
        pr = Progress.of(store.packages.values.toList(), store.stops.values.toList())
        assertEquals(3, pr.delivered); assertEquals(1, pr.notDelivered); assertEquals(196, pr.pending)
        assertEquals(200, pr.delivered + pr.notDelivered + pr.pending)
        assertEquals(2, pr.percent)   // 4/200
        assertTrue(pr.completedStops >= 1)
    }

    @Test fun entregar_todos_leva_a_100_por_cento() {
        rows.forEach { engine.markDelivered(idOf(it[3]), 1) }
        val pr = Progress.of(store.packages.values.toList(), store.stops.values.toList())
        assertEquals(100, pr.percent); assertEquals(171, pr.completedStops)
    }

    @Test fun codigo_duplicado_permitido_resulta_em_ambiguo_nunca_em_escolha() {
        val t = TableReader().read("d.csv", "SPX TN,Stop,Endereço\nBR1,1,Rua A 1\nBR1,2,Rua B 2\nBR3,3,Rua C 3\n".toByteArray())
        val pv = ImportParser.parse("d", t, HeaderMapper.suggest(t).mapping, ImportOptions(allowDuplicateCodes = true))
        val st = InMemoryDeliveryStore().load(9, pv); val eng = DeliveryEngine(st)
        val out = eng.scan(9, "BR1", 1)
        assertTrue(out is ScanOutcome.Ambiguous)
        assertEquals(setOf(1, 2), (out as ScanOutcome.Ambiguous).matches.map { it.stopNumber }.toSet())
        assertTrue(st.packages.values.none { it.scannedAt != null })
        assertTrue(eng.scan(9, "BR3", 1) is ScanOutcome.Found)
    }

    @Test fun dados_originais_imutaveis_apos_operacao_completa() {
        val snapshot = store.packages.values.map { Triple(it.trackingCode, it.stopNumber, it.sequence) }
        rows.forEach { engine.scan(rid, it[3], 1) }
        rows.take(60).forEach { engine.markDelivered(idOf(it[3]), 2) }
        rows.drop(60).take(20).forEach { engine.markNotDelivered(idOf(it[3]), FailureReason.REFUSED, null, 3) }
        assertEquals(snapshot, store.packages.values.map { Triple(it.trackingCode, it.stopNumber, it.sequence) })
        assertEquals(200, store.packages.size)
        assertEquals((1..171).toList(), store.stops.values.map { it.stopNumber }.sorted())
    }
}
