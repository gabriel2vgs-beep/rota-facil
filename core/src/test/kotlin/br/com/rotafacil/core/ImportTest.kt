package br.com.rotafacil.core

import org.junit.Assert.*
import org.junit.Test

/** Testes da importação usando a planilha REAL de referência (core/src/test/resources). */
class ImportTest {

    companion object {
        const val FILE = "Romaneio_Shopee_Organizado_09-11-2026.xlsx"
        fun bytes(): ByteArray = ImportTest::class.java.getResourceAsStream("/$FILE")!!.readBytes()
        fun table(): RawTable = TableReader().read(FILE, bytes())
        fun preview(options: ImportOptions = ImportOptions()): ImportPreview {
            val t = table()
            val m = HeaderMapper.suggest(t)
            assertTrue("faltam colunas: ${m.missingRequired}", m.isComplete)
            return ImportParser.parse(FILE, t, m.mapping, options)
        }
    }

    @Test fun cabecalhosReaisSaoReconhecidos() {
        val t = table()
        assertEquals(
            listOf("AT ID", "Sequence", "Stop", "SPX TN", "Destination Address", "Bairro", "City", "Zipcode/Postal code", "Latitude", "Longitude"),
            t.headers,
        )
        val m = HeaderMapper.suggest(t).mapping.columns
        assertEquals(0, m[Field.BATCH]); assertEquals(1, m[Field.SEQUENCE]); assertEquals(2, m[Field.STOP])
        assertEquals(3, m[Field.TRACKING]); assertEquals(4, m[Field.ADDRESS]); assertEquals(5, m[Field.NEIGHBORHOOD])
        assertEquals(6, m[Field.CITY]); assertEquals(7, m[Field.ZIP]); assertEquals(8, m[Field.LATITUDE]); assertEquals(9, m[Field.LONGITUDE])
    }

    @Test fun todos_os_200_pacotes_sao_importados() {
        val p = preview()
        assertEquals(200, p.totalPackages)
        assertEquals(200, p.packages.map { it.lookupKey }.toSet().size)
        assertEquals("erros: ${p.errors}", emptyList<Issue>(), p.errors)
        assertTrue(p.canConfirm)
    }

    @Test fun os_171_numeros_de_parada_sao_reconhecidos() {
        val p = preview()
        assertEquals(171, p.totalStops)
        assertEquals((1..171).toList(), p.stops.map { it.stopNumber })
        assertEquals(GroupingMode.BY_STOP_COLUMN, p.groupingMode)
    }

    @Test fun pacotes_da_mesma_parada_continuam_agrupados_e_conferem_com_a_planilha() {
        val t = table()
        val p = preview()
        // Verdade independente: lê direto das células cruas, sem passar pelo parser.
        val expected = t.rows.groupBy({ it[2].toInt() }, { it[3].trim() })
        assertEquals(expected.keys, p.stops.map { it.stopNumber }.toSet())
        for (stop in p.stops) {
            assertEquals("parada ${stop.stopNumber}", expected.getValue(stop.stopNumber).toSet(), stop.packages.map { it.trackingCode }.toSet())
            assertTrue(stop.packages.all { it.stop == stop.stopNumber })
        }
        assertEquals(mapOf(1 to 149, 2 to 17, 3 to 3, 4 to 2), p.stops.groupingBy { it.packages.size }.eachCount())
        assertEquals(200, p.stops.sumOf { it.packages.size })
    }

    @Test fun nenhum_codigo_SPX_e_alterado() {
        val t = table()
        val p = preview()
        val original = t.rows.map { it[3] }
        val imported = p.packages.map { it.trackingCode }
        assertEquals(original.sorted(), imported.sorted())
        // cada pacote mantém exatamente o código da sua linha de origem
        for (pkg in p.packages) assertEquals(t.rows[pkg.sourceRow - 2][3], pkg.trackingCode)
    }

    @Test fun enderecos_originais_sao_preservados_e_complementos_derivados() {
        val t = table()
        val p = preview()
        for (pkg in p.packages) assertEquals(t.rows[pkg.sourceRow - 2][4].trim(), pkg.fullAddress)
        val s37 = p.stops.first { it.stopNumber == 37 }
        assertEquals("Estrada Imperial, 330", s37.address)
        assertEquals(4, s37.packages.size)
        assertTrue(s37.packages.all { it.complement.isNotBlank() })
        val s55 = p.stops.first { it.stopNumber == 55 }
        assertEquals("R Pedro Genovês, 205", s55.address)
    }

    @Test fun sequence_e_stop_originais_sao_preservados() {
        val t = table()
        val p = preview()
        for (pkg in p.packages) {
            val row = t.rows[pkg.sourceRow - 2]
            assertEquals(row[1].toInt(), pkg.sequence)
            assertEquals(row[2].toInt(), pkg.stop)
        }
    }

    @Test fun coordenadas_da_parada_vem_dos_pacotes() {
        val p = preview()
        assertTrue(p.stops.all { it.latitude != null && it.longitude != null })
        // paradas com mais de um pacote têm todas as coordenadas iguais na planilha → sem aviso de conflito
        assertTrue(p.issues.none { it.code == IssueCode.STOP_COORDINATE_CONFLICT })
    }

    @Test fun leitura_de_csv_equivale_a_leitura_do_xlsx() {
        val t = table()
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val csv = (listOf(t.headers) + t.rows).joinToString("\r\n") { r -> r.joinToString(";") { q(it) } }
        val fromCsv = TableReader().read("x.csv", csv.toByteArray(Charsets.UTF_8))
        assertEquals(t.headers, fromCsv.headers)
        assertEquals(t.rows.size, fromCsv.rows.size)
        val a = ImportParser.parse("a", t, HeaderMapper.suggest(t).mapping)
        val b = ImportParser.parse("b", fromCsv, HeaderMapper.suggest(fromCsv).mapping)
        assertEquals(a.contentHash, b.contentHash)
        assertEquals(200, b.totalPackages); assertEquals(171, b.totalStops)
    }

    @Test fun csv_windows1252_com_virgula_e_aspas() {
        val text = "SPX TN,Stop,Endereço,Sequence\nBR1,1,\"Rua A, 10\",1\nBR2,1,\"Rua A, 10, Apto 2\",2\n"
        val t = TableReader().read("w.csv", text.toByteArray(java.nio.charset.Charset.forName("windows-1252")))
        assertEquals("Endereço", t.headers[2])
        val p = ImportParser.parse("w", t, HeaderMapper.suggest(t).mapping)
        assertEquals(2, p.totalPackages); assertEquals(1, p.totalStops)
        assertEquals("Rua A, 10", p.stops[0].address)
    }

    // --- cenários de dados ruins -------------------------------------------------

    private fun parseCsv(text: String, options: ImportOptions = ImportOptions()): ImportPreview {
        val t = TableReader().read("t.csv", text.toByteArray(Charsets.UTF_8))
        return ImportParser.parse("t.csv", t, HeaderMapper.suggest(t).mapping, options)
    }

    @Test fun codigo_duplicado_bloqueia_e_nada_e_descartado() {
        val p = parseCsv("SPX TN,Stop,Endereço\nBR1,1,Rua A 1\nBR1,2,Rua B 2\nBR3,3,Rua C 3\n")
        assertFalse(p.canConfirm)
        assertTrue(p.errors.any { it.code == IssueCode.DUPLICATE_CODE_IN_FILE })
        assertEquals(3, p.totalPackages)
        val allowed = parseCsv("SPX TN,Stop,Endereço\nBR1,1,Rua A 1\nBR1,2,Rua B 2\n", ImportOptions(allowDuplicateCodes = true))
        assertTrue(allowed.canConfirm); assertEquals(2, allowed.totalPackages)
    }

    @Test fun stop_ausente_ou_invalido_bloqueia() {
        val p = parseCsv("SPX TN,Stop,Endereço\nBR1,,Rua A 1\nBR2,abc,Rua B 2\nBR3,3,Rua C 3\n")
        assertFalse(p.canConfirm)
        assertTrue(p.errors.any { it.code == IssueCode.MISSING_STOP })
        assertTrue(p.errors.any { it.code == IssueCode.INVALID_STOP })
        assertEquals(1, p.totalPackages) // os 2 problemáticos ficam fora do preview, mas o ERRO impede confirmar
    }

    @Test fun codigo_vazio_bloqueia() {
        val p = parseCsv("SPX TN,Stop,Endereço\n,1,Rua A 1\nBR2,2,Rua B 2\n")
        assertFalse(p.canConfirm)
        assertTrue(p.errors.any { it.code == IssueCode.MISSING_TRACKING })
    }

    @Test fun coordenadas_invalidas_geram_aviso_e_parada_sem_coordenada() {
        val p = parseCsv("SPX TN,Stop,Endereço,Latitude,Longitude\nBR1,1,Rua A 1,abc,-46.1\nBR2,2,Rua B 2,-23.4,-46.1\n")
        assertTrue(p.canConfirm)
        assertTrue(p.warnings.any { it.code == IssueCode.INVALID_COORDINATES })
        assertTrue(p.warnings.any { it.code == IssueCode.STOP_NO_COORDINATES && it.stop == 1 })
        assertNull(p.stops.first { it.stopNumber == 1 }.latitude)
    }

    @Test fun conflito_de_coordenadas_na_mesma_parada_gera_aviso() {
        val p = parseCsv("SPX TN,Stop,Endereço,Latitude,Longitude\nBR1,1,Rua A 1,-23.40,-46.10\nBR2,1,Rua A 1,-23.50,-46.10\n")
        assertTrue(p.warnings.any { it.code == IssueCode.STOP_COORDINATE_CONFLICT })
    }

    @Test fun planilha_sem_coluna_de_parada_agrupa_por_coordenada() {
        val p = parseCsv("SPX TN,Endereço,Latitude,Longitude,Sequence\nBR1,Rua A 1,-23.4,-46.1,1\nBR2,Rua A 1 apto 2,-23.4,-46.1,2\nBR3,Rua B 5,-23.5,-46.2,3\n")
        assertEquals(GroupingMode.BY_ADDRESS, p.groupingMode)
        assertEquals(2, p.totalStops); assertEquals(3, p.totalPackages)
        assertEquals(setOf("BR1", "BR2"), p.stops[0].packages.map { it.trackingCode }.toSet())
    }

    @Test fun planilha_vazia_e_sem_dados() {
        val p = parseCsv("SPX TN,Stop,Endereço\n")
        assertFalse(p.canConfirm)
        assertTrue(p.errors.any { it.code == IssueCode.EMPTY_FILE })
    }

    @Test fun mapeamento_manual_quando_cabecalhos_sao_desconhecidos() {
        val t = TableReader().read("m.csv", "col1;col2;col3\nBR1;1;Rua A 1\n".toByteArray())
        val auto = HeaderMapper.suggest(t)
        assertFalse("cabeçalhos genéricos não devem ser adivinhados", auto.isComplete)
        assertTrue(Field.TRACKING in auto.missingRequired && Field.ADDRESS in auto.missingRequired)
        val manual = ColumnMapping(mapOf(Field.TRACKING to 0, Field.STOP to 1, Field.ADDRESS to 2))
        val p = ImportParser.parse("m", t, manual)
        assertEquals(1, p.totalPackages); assertEquals("BR1", p.packages[0].trackingCode)
    }

    @Test fun anti_duplicidade_de_importacao() {
        val p = preview()
        val same = ExistingRomaneio(1, "antigo.xlsx", p.contentHash, p.packages.map { it.lookupKey }.toSet())
        assertTrue(DuplicateGuard.check(p, listOf(same)).errors.any { it.code == IssueCode.IDENTICAL_ROMANEIO_ALREADY_IMPORTED })
        val partial = ExistingRomaneio(2, "outro.xlsx", "x", p.packages.take(10).map { it.lookupKey }.toSet())
        val c = DuplicateGuard.check(p, listOf(partial))
        assertTrue(c.canConfirm); assertTrue(c.warnings.any { it.code == IssueCode.PARTIAL_OVERLAP_WITH_IMPORTED })
        val allIn = ExistingRomaneio(3, "maior.xlsx", "y", p.packages.map { it.lookupKey }.toSet() + "OUTRO")
        assertFalse(DuplicateGuard.check(p, listOf(allIn)).canConfirm)
        assertTrue(DuplicateGuard.check(p, emptyList()).canConfirm)
    }

    @Test fun arquivo_binario_desconhecido_e_rejeitado() {
        try { TableReader().read("x.bin", byteArrayOf(1, 0, 2, 0, 3, 0)); fail() } catch (_: UnsupportedFormatException) { }
        try { TableReader().read("x.xls", byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0)); fail() } catch (_: UnsupportedFormatException) { }
    }
}
