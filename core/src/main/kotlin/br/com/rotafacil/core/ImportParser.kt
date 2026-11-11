package br.com.rotafacil.core

import java.security.MessageDigest
import java.util.Locale

data class ImportOptions(
    /** Se true, códigos repetidos na planilha viram aviso (e a bipagem passa a devolver "ambíguo"), em vez de erro. */
    val allowDuplicateCodes: Boolean = false,
    /** Distância máxima (m) entre coordenadas de pacotes da mesma parada antes de avisar. */
    val coordConflictMeters: Double = 100.0,
)

object CodeNormalizer {
    private fun isJunk(c: Char) = c.isWhitespace() || c == ' ' || c == '﻿' || c == '​' || c.isISOControl()

    /** Só tira espaços/caracteres invisíveis das pontas. Nunca altera o conteúdo do código. */
    fun clean(raw: String): String = raw.trim { isJunk(it) }

    /** Chave de busca: o código limpo em maiúsculas. Comparação sempre EXATA — nada de busca aproximada. */
    fun key(raw: String): String = clean(raw).uppercase(Locale.ROOT)
}

object ImportParser {

    fun parse(
        fileName: String,
        table: RawTable,
        mapping: ColumnMapping,
        options: ImportOptions = ImportOptions(),
    ): ImportPreview {
        val issues = ArrayList<Issue>()
        if (table.rows.isEmpty()) {
            issues += Issue(Severity.ERROR, IssueCode.EMPTY_FILE, "A planilha não tem nenhuma linha de dados.")
            return ImportPreview(fileName, mapping, GroupingMode.BY_STOP_COLUMN, emptyList(), emptyList(), issues, hash(emptyList()))
        }
        val trackingCol = mapping.indexOf(Field.TRACKING)
        val addressCol = mapping.indexOf(Field.ADDRESS)
        require(trackingCol != null && addressCol != null) { "Mapeamento incompleto: código e endereço são obrigatórios." }
        val hasStopColumn = mapping.has(Field.STOP)
        val mode = if (hasStopColumn) GroupingMode.BY_STOP_COLUMN else GroupingMode.BY_ADDRESS

        fun cell(row: List<String>, f: Field): String = mapping.indexOf(f)?.let { row.getOrNull(it) }?.trim() ?: ""

        class Draft(
            val sourceRow: Int, val code: String, val key: String, val address: String, val neighborhood: String,
            val city: String, val zip: String, val lat: Double?, val lng: Double?, var stop: Int?, val sequence: Int, val batch: String,
        )

        val drafts = ArrayList<Draft>()
        val seenSequences = HashMap<Int, Int>()

        table.rows.forEachIndexed { i, row ->
            val sourceRow = i + 2 // linha 1 = cabeçalho
            val rawCode = row.getOrNull(trackingCol) ?: ""
            val code = CodeNormalizer.clean(rawCode)
            if (code.isEmpty()) {
                issues += Issue(Severity.ERROR, IssueCode.MISSING_TRACKING, "Linha $sourceRow: pacote sem código de rastreamento.", row = sourceRow)
                return@forEachIndexed
            }
            if (code != rawCode) issues += Issue(Severity.INFO, IssueCode.TRACKING_TRIMMED, "Linha $sourceRow: removi espaços nas pontas do código $code.", row = sourceRow)
            if (code.any { !it.isLetterOrDigit() }) issues += Issue(Severity.WARNING, IssueCode.SUSPICIOUS_CODE, "Linha $sourceRow: o código \"$code\" tem caracteres incomuns. Confira.", row = sourceRow)

            val address = (row.getOrNull(addressCol) ?: "").trim()

            val latText = cell(row, Field.LATITUDE); val lngText = cell(row, Field.LONGITUDE)
            var lat = parseCoord(latText); var lng = parseCoord(lngText)
            val coordsPresent = latText.isNotEmpty() || lngText.isNotEmpty()
            if (lat != null && lng != null && !validCoord(lat, lng)) { lat = null; lng = null }
            else if ((lat == null) != (lng == null)) { lat = null; lng = null }
            if (lat == null && coordsPresent) {
                issues += Issue(Severity.WARNING, IssueCode.INVALID_COORDINATES, "Linha $sourceRow ($code): coordenadas ausentes ou inválidas (\"$latText\", \"$lngText\").", row = sourceRow)
            }

            if (address.isEmpty()) {
                val sev = if (lat != null) Severity.WARNING else Severity.ERROR
                issues += Issue(sev, IssueCode.MISSING_ADDRESS, "Linha $sourceRow ($code): endereço vazio.", row = sourceRow)
            }

            var stop: Int? = null
            if (hasStopColumn) {
                val stopText = cell(row, Field.STOP)
                if (stopText.isEmpty()) {
                    issues += Issue(Severity.ERROR, IssueCode.MISSING_STOP, "Linha $sourceRow ($code): pacote sem número de parada.", row = sourceRow)
                } else {
                    stop = parseWholeNumber(stopText)
                    if (stop == null || stop < 1) {
                        stop = null
                        issues += Issue(Severity.ERROR, IssueCode.INVALID_STOP, "Linha $sourceRow ($code): parada inválida \"$stopText\".", row = sourceRow)
                    }
                }
            }

            var seq = parseWholeNumber(cell(row, Field.SEQUENCE))
            if (mapping.has(Field.SEQUENCE) && seq == null) {
                issues += Issue(Severity.WARNING, IssueCode.INVALID_SEQUENCE, "Linha $sourceRow ($code): ordem (Sequence) inválida; usei a posição da linha.", row = sourceRow)
            }
            if (seq == null) seq = sourceRow - 1
            seenSequences.merge(seq, 1, Int::plus)

            drafts += Draft(sourceRow, code, CodeNormalizer.key(code), address, cell(row, Field.NEIGHBORHOOD), cell(row, Field.CITY),
                cell(row, Field.ZIP), lat, lng, stop, seq, cell(row, Field.BATCH))
        }

        seenSequences.filter { it.value > 1 }.forEach { (seq, n) ->
            issues += Issue(Severity.WARNING, IssueCode.DUPLICATE_SEQUENCE, "A ordem (Sequence) $seq aparece $n vezes.")
        }

        // Códigos repetidos: nunca descartar, nunca escolher um deles no escuro.
        drafts.groupBy { it.key }.filter { it.value.size > 1 }.forEach { (key, list) ->
            val where = list.joinToString { "linha ${it.sourceRow}" }
            issues += Issue(
                if (options.allowDuplicateCodes) Severity.WARNING else Severity.ERROR,
                IssueCode.DUPLICATE_CODE_IN_FILE,
                "O código $key aparece ${list.size} vezes ($where).",
            )
        }

        // Agrupamento
        if (mode == GroupingMode.BY_ADDRESS) {
            val keyToStop = LinkedHashMap<String, Int>()
            drafts.sortedBy { it.sequence }.forEach { d ->
                val k = if (d.lat != null && d.lng != null) "%.5f|%.5f".format(Locale.ROOT, d.lat, d.lng)
                else "addr|" + Text.normalize(d.address.split(',').take(2).joinToString(","))
                d.stop = keyToStop.getOrPut(k) { keyToStop.size + 1 }
            }
            issues += Issue(Severity.INFO, IssueCode.GROUPED_BY_ADDRESS,
                "A planilha não tem coluna de parada. Agrupei pelos endereços/coordenadas iguais e numerei as paradas ${keyToStop.size} na ordem da planilha.")
        }

        val grouped = drafts.filter { it.stop != null }.groupBy { it.stop!! }.toSortedMap()
        val stops = ArrayList<ParsedStop>()
        val allPackages = ArrayList<ParsedPackage>()

        for ((stopNumber, list) in grouped) {
            val ordered = list.sortedWith(compareBy({ it.sequence }, { it.sourceRow }))
            val (base, complements, mismatch) = splitAddresses(ordered.map { it.address })
            if (mismatch) issues += Issue(Severity.WARNING, IssueCode.STOP_ADDRESS_MISMATCH,
                "Parada $stopNumber: os pacotes têm endereços diferentes (${ordered.map { it.address }.distinct().joinToString(" | ")}).", stop = stopNumber)

            val located = ordered.filter { it.lat != null && it.lng != null }
            val first = located.firstOrNull()
            if (first == null) {
                issues += Issue(Severity.WARNING, IssueCode.STOP_NO_COORDINATES, "Parada $stopNumber sem coordenadas: ficará no fim da rota otimizada.", stop = stopNumber)
            } else if (located.any { Geo.haversineMeters(first.lat!!, first.lng!!, it.lat!!, it.lng!!) > options.coordConflictMeters }) {
                issues += Issue(Severity.WARNING, IssueCode.STOP_COORDINATE_CONFLICT, "Parada $stopNumber: pacotes com coordenadas distantes entre si (> ${options.coordConflictMeters.toInt()} m).", stop = stopNumber)
            }

            fun common(f: (Draft) -> String, name: String): String {
                val values = ordered.map(f).filter { it.isNotEmpty() }
                val distinct = values.map { Text.normalize(it) }.distinct()
                if (distinct.size > 1) issues += Issue(Severity.WARNING, IssueCode.STOP_FIELD_CONFLICT, "Parada $stopNumber: $name diferente entre pacotes (${values.distinct().joinToString(" | ")}).", stop = stopNumber)
                return values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
            }
            val neighborhood = common({ it.neighborhood }, "bairro")
            val city = common({ it.city }, "cidade")
            val zip = ordered.map { it.zip }.firstOrNull { it.isNotEmpty() } ?: ""

            val packages = ordered.mapIndexed { idx, d ->
                ParsedPackage(d.sourceRow, d.code, d.key, d.address, complements[idx], d.neighborhood, d.city, d.zip, d.lat, d.lng, stopNumber, d.sequence, d.batch)
            }
            allPackages += packages
            stops += ParsedStop(stopNumber, base, neighborhood, city, zip, first?.lat, first?.lng, packages)
        }

        val sortedPackages = allPackages.sortedWith(compareBy({ it.sequence }, { it.sourceRow }))
        return ImportPreview(fileName, mapping, mode, sortedPackages, stops, issues, hash(sortedPackages))
    }

    // --- helpers -----------------------------------------------------------

    private fun parseWholeNumber(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { return it }
        val d = t.replace(',', '.').toDoubleOrNull() ?: return null
        return if (d == Math.floor(d) && d >= 0 && d < Int.MAX_VALUE) d.toInt() else null
    }

    private fun parseCoord(s: String): Double? =
        s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun validCoord(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0 && !(Math.abs(lat) < 1e-6 && Math.abs(lng) < 1e-6)

    private data class Split(val base: String, val complements: List<String>, val mismatch: Boolean)

    /** Divide endereços da mesma parada em endereço-base (rua + número) e complemento (bloco/apto...). Sem perda: o original continua em cada pacote. */
    private fun splitAddresses(addresses: List<String>): Split {
        val firstNonEmpty = addresses.firstOrNull { it.isNotEmpty() } ?: ""
        if (addresses.size == 1) return Split(firstNonEmpty, listOf(""), false)
        val tokens = addresses.map { a -> a.split(',').map { it.trim() } }
        val normTokens = tokens.map { t -> t.map { Text.normalize(it) } }
        if (normTokens.distinct().size == 1) return Split(firstNonEmpty, addresses.map { "" }, false)
        var prefix = 0
        val minLen = normTokens.minOf { it.size }
        while (prefix < minLen && normTokens.all { it[prefix] == normTokens[0][prefix] }) prefix++
        return if (prefix >= 2) {
            val base = tokens[0].take(prefix).joinToString(", ")
            Split(base, tokens.map { it.drop(prefix).joinToString(", ") }, false)
        } else {
            Split(firstNonEmpty, addresses.map { if (Text.normalize(it) == Text.normalize(firstNonEmpty)) "" else it }, true)
        }
    }

    private fun hash(packages: List<ParsedPackage>): String {
        val lines = packages.map { "${it.lookupKey}|${it.stop}|${Text.normalize(it.fullAddress)}" }.sorted()
        val md = MessageDigest.getInstance("SHA-256")
        lines.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update('\n'.code.toByte()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Dados mínimos de romaneios já salvos no aparelho, para evitar importação duplicada. */
data class ExistingRomaneio(val id: Long, val fileName: String, val contentHash: String, val codeKeys: Set<String>)

object DuplicateGuard {
    fun check(preview: ImportPreview, existing: List<ExistingRomaneio>): ImportPreview {
        if (preview.packages.isEmpty()) return preview
        val extra = ArrayList<Issue>()
        val newKeys = preview.packages.map { it.lookupKey }.toSet()
        existing.firstOrNull { it.contentHash == preview.contentHash }?.let {
            extra += Issue(Severity.ERROR, IssueCode.IDENTICAL_ROMANEIO_ALREADY_IMPORTED,
                "Este romaneio já foi importado (${it.fileName}). Importar de novo duplicaria as encomendas.")
        } ?: run {
            for (e in existing) {
                val overlap = newKeys.intersect(e.codeKeys)
                if (overlap.isEmpty()) continue
                if (overlap.size == newKeys.size) {
                    extra += Issue(Severity.ERROR, IssueCode.ALL_CODES_ALREADY_IMPORTED,
                        "Todos os ${newKeys.size} códigos desta planilha já existem no romaneio \"${e.fileName}\".")
                } else {
                    extra += Issue(Severity.WARNING, IssueCode.PARTIAL_OVERLAP_WITH_IMPORTED,
                        "${overlap.size} códigos desta planilha já existem no romaneio \"${e.fileName}\".")
                }
            }
        }
        return if (extra.isEmpty()) preview else preview.copy(issues = preview.issues + extra)
    }
}
