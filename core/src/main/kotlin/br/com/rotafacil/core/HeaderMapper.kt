package br.com.rotafacil.core

import java.text.Normalizer

object Text {
    /** minúsculas, sem acentos, só letras e números: "Zipcode/Postal code" -> "zipcodepostalcode". */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .filter { it.isLetterOrDigit() && it.code < 128 }
            .lowercase()
}

object HeaderMapper {

    private class Rule(val exact: Set<String>, val startsWith: List<String> = emptyList(), val contains: List<String> = emptyList())

    private val rules: LinkedHashMap<Field, Rule> = linkedMapOf(
        Field.TRACKING to Rule(
            exact = setOf("spxtn", "spx", "tracking", "trackingnumber", "trackingno", "trackingid", "spxtracking", "tn",
                "codigoderastreamento", "codigorastreamento", "rastreamento", "rastreio", "codigo", "barcode", "codigodebarras"),
            startsWith = listOf("spx", "tracking", "rastre"),
            contains = listOf("tracking", "rastre"),
        ),
        Field.STOP to Rule(
            exact = setOf("stop", "parada", "stopnumber", "stopno", "numerodaparada", "nparada", "numparada"),
            startsWith = listOf("stop", "parada"),
        ),
        Field.SEQUENCE to Rule(
            exact = setOf("sequence", "sequencia", "seq", "sequenceno", "seqno", "ordem", "ordemdeentrega", "ordementrega"),
            startsWith = listOf("sequen", "ordem"),
        ),
        Field.ADDRESS to Rule(
            exact = setOf("destinationaddress", "endereco", "enderecodeentrega", "enderecocompleto", "address", "deliveryaddress", "logradouro"),
            startsWith = listOf("endereco", "address", "destinationaddr"),
            contains = listOf("address", "endereco"),
        ),
        Field.LATITUDE to Rule(exact = setOf("latitude", "lat", "latitud"), startsWith = listOf("latitude")),
        Field.LONGITUDE to Rule(exact = setOf("longitude", "lng", "lon", "long", "longitud"), startsWith = listOf("longitude")),
        Field.ZIP to Rule(
            exact = setOf("zipcodepostalcode", "zipcode", "postalcode", "cep", "zip", "postcode"),
            startsWith = listOf("zip", "cep"),
            contains = listOf("postal"),
        ),
        Field.NEIGHBORHOOD to Rule(exact = setOf("bairro", "neighborhood", "neighbourhood", "district", "suburb")),
        Field.CITY to Rule(exact = setOf("city", "cidade", "municipio")),
        Field.BATCH to Rule(exact = setOf("atid", "idromaneio", "romaneio", "batch", "batchid", "lote")),
    )

    private val trackingShape = Regex("^[A-Za-z]{2}[A-Za-z0-9]{10,}$")

    fun suggest(table: RawTable): MappingResult {
        val norm = table.headers.map { Text.normalize(it) }
        val used = HashSet<Int>()
        val columns = LinkedHashMap<Field, Int>()
        val confidence = HashMap<Field, Confidence>()

        fun assign(field: Field, idx: Int, c: Confidence) {
            columns[field] = idx; used.add(idx); confidence[field] = c
        }

        for ((field, rule) in rules) {
            var idx = norm.indices.firstOrNull { it !in used && norm[it] in rule.exact }
            if (idx != null) { assign(field, idx, Confidence.HIGH); continue }
            idx = norm.indices.firstOrNull { i -> i !in used && norm[i].isNotEmpty() && rule.startsWith.any { norm[i].startsWith(it) } }
            if (idx != null) { assign(field, idx, Confidence.MEDIUM); continue }
            idx = norm.indices.firstOrNull { i -> i !in used && norm[i].isNotEmpty() && rule.contains.any { norm[i].contains(it) } }
            if (idx != null) assign(field, idx, Confidence.LOW)
        }

        // Plano B para o código de rastreamento: olhar o conteúdo das colunas.
        if (Field.TRACKING !in columns) {
            val best = table.headers.indices.filter { it !in used }.map { col ->
                val values = table.rows.mapNotNull { it.getOrNull(col)?.trim() }.filter { it.isNotEmpty() }
                col to (if (values.isEmpty()) 0.0 else values.count { trackingShape.matches(it) }.toDouble() / values.size)
            }.maxByOrNull { it.second }
            if (best != null && best.second >= 0.8) assign(Field.TRACKING, best.first, Confidence.LOW)
        }

        val missing = Field.values().filter { it.required && it !in columns }
        return MappingResult(ColumnMapping(columns), confidence, missing)
    }
}
