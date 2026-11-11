package br.com.rotafacil.core

import java.util.Locale

fun interface HttpGet {
    /** Devolve o corpo da resposta ou lança [RoutingException]. */
    fun get(url: String): String
}

/** JSON mínimo (suficiente para respostas do OSRM). Números viram Double; null vira null. */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text); val v = p.value(); p.ws()
        if (p.i != text.length) throw IllegalArgumentException("JSON com lixo no final")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalArgumentException("JSON incompleto")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("JSON inválido em $i")
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw IllegalArgumentException("JSON inválido em $i"); i += w.length; return v }
        fun num(): Double { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; return s.substring(st, i).toDouble() }
        fun str(): String {
            val sb = StringBuilder(); i++
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("string não fechada")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> { when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e) } }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            val out = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return out }
            while (true) { out.add(value()); ws(); if (s[i] == ',') { i++; continue }; if (s[i] == ']') { i++; return out }; throw IllegalArgumentException("array inválido em $i") }
        }
        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = str(); ws(); if (s[i++] != ':') throw IllegalArgumentException("esperava ':'")
                out[k] = value(); ws()
                if (s[i] == ',') { i++; continue }; if (s[i] == '}') { i++; return out }; throw IllegalArgumentException("objeto inválido em $i")
            }
        }
    }
}

/**
 * Matriz de tempos/distâncias pelo OSRM (malha viária do OpenStreetMap, respeita mão única).
 * O servidor público (router.project-osrm.org) é uma demonstração, sem garantia de disponibilidade;
 * para uso diário configure seu próprio servidor OSRM em [baseUrl].
 */
class OsrmMatrixProvider(
    private val http: HttpGet,
    private val baseUrl: String = DEFAULT_URL,
    private val maxCoordsPerRequest: Int = 90,
    private val profile: String = "driving",
) : MatrixProvider {

    override fun matrix(points: List<LatLng>): MatrixData {
        val n = points.size
        val dur = Array(n) { DoubleArray(n) { Double.NaN } }
        val dist = Array(n) { DoubleArray(n) { Double.NaN } }
        if (n <= maxCoordsPerRequest) {
            fill(points, (0 until n).toList(), (0 until n).toList(), null, null, dur, dist)
        } else {
            val block = maxCoordsPerRequest / 2
            val ranges = (0 until n step block).map { it until minOf(n, it + block) }
            for (si in ranges) for (di in ranges) {
                val src = si.toList(); val dst = di.toList()
                if (si == di) fill(points, src, dst, null, null, dur, dist)
                else {
                    val idx = src + dst
                    fill(points, src, dst, src.indices.toList(), (src.size until idx.size).toList(), dur, dist, idx)
                }
            }
        }
        return MatrixData(dur, dist)
    }

    private fun fill(
        points: List<LatLng>, src: List<Int>, dst: List<Int>,
        srcIdx: List<Int>?, dstIdx: List<Int>?,
        dur: Array<DoubleArray>, dist: Array<DoubleArray>, coordIdx: List<Int>? = null,
    ) {
        val coords = (coordIdx ?: src).map { points[it] }
        val url = buildUrl(coords, srcIdx, dstIdx)
        val root = try { Json.parse(http.get(url)) as? Map<*, *> } catch (e: IllegalArgumentException) { throw RoutingException("Resposta inválida do serviço de rotas.", e) }
            ?: throw RoutingException("Resposta inválida do serviço de rotas.")
        if (root["code"] != "Ok") throw RoutingException("Serviço de rotas respondeu: ${root["code"]} ${root["message"] ?: ""}".trim())
        val d = root["durations"] as? List<*> ?: throw RoutingException("Resposta sem tempos de viagem.")
        val m = root["distances"] as? List<*>
        if (d.size != src.size) throw RoutingException("Resposta com tamanho inesperado.")
        for (a in src.indices) {
            val rowD = d[a] as? List<*> ?: throw RoutingException("Resposta com tamanho inesperado.")
            if (rowD.size != dst.size) throw RoutingException("Resposta com tamanho inesperado.")
            val rowM = m?.get(a) as? List<*>
            for (b in dst.indices) {
                dur[src[a]][dst[b]] = (rowD[b] as? Double) ?: Double.NaN
                if (rowM != null) dist[src[a]][dst[b]] = (rowM[b] as? Double) ?: Double.NaN
            }
        }
    }

    internal fun buildUrl(coords: List<LatLng>, sources: List<Int>?, destinations: List<Int>?): String {
        val c = coords.joinToString(";") { "%.6f,%.6f".format(Locale.ROOT, it.lng, it.lat) }   // OSRM: lng,lat
        val sb = StringBuilder("${baseUrl.trimEnd('/')}/table/v1/$profile/$c?annotations=duration,distance")
        if (sources != null) sb.append("&sources=").append(sources.joinToString(";"))
        if (destinations != null) sb.append("&destinations=").append(destinations.joinToString(";"))
        return sb.toString()
    }

    companion object { const val DEFAULT_URL = "https://router.project-osrm.org" }
}

/** Geometria real das ruas para desenhar a rota no mapa (trechos de até ~25 paradas por requisição). */
class OsrmRouteGeometry(
    private val http: HttpGet,
    private val baseUrl: String = OsrmMatrixProvider.DEFAULT_URL,
    private val profile: String = "driving",
) {
    fun path(waypoints: List<LatLng>): List<LatLng> {
        if (waypoints.size < 2) return waypoints
        val c = waypoints.joinToString(";") { "%.6f,%.6f".format(Locale.ROOT, it.lng, it.lat) }
        val url = "${baseUrl.trimEnd('/')}/route/v1/$profile/$c?overview=full&geometries=geojson&steps=false"
        val root = try { Json.parse(http.get(url)) as? Map<*, *> } catch (e: IllegalArgumentException) { throw RoutingException("Resposta inválida do serviço de rotas.", e) }
            ?: throw RoutingException("Resposta inválida do serviço de rotas.")
        if (root["code"] != "Ok") throw RoutingException("Serviço de rotas respondeu: ${root["code"]}")
        val route = (root["routes"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: throw RoutingException("Sem rota na resposta.")
        val coords = (route["geometry"] as? Map<*, *>)?.get("coordinates") as? List<*> ?: throw RoutingException("Sem geometria na resposta.")
        return coords.mapNotNull { p -> (p as? List<*>)?.let { LatLng((it[1] as Double), (it[0] as Double)) } }
    }
}
