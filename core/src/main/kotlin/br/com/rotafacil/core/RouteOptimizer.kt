package br.com.rotafacil.core

class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Matriz de custos entre pontos pela malha viária. Índice 0 = ponto inicial; 1..n = paradas.
 * Assimétrica de propósito: ruas de mão única fazem ida != volta.
 */
class MatrixData(val durations: Array<DoubleArray>, val distances: Array<DoubleArray>? = null) {
    val size: Int get() = durations.size
}

fun interface MatrixProvider {
    /** Devolve a matriz para os pontos, ou lança [RoutingException] se o serviço de rotas estiver indisponível. */
    fun matrix(points: List<LatLng>): MatrixData
}

data class RouteStop(val stopNumber: Int, val location: LatLng?)

data class PlannedRoute(
    /** Números Stop originais, na ordem otimizada. Paradas sem coordenada vão no fim, na ordem original. */
    val orderedStops: List<Int>,
    val unlocatedStops: List<Int>,
    val totalSeconds: Double,
    val totalMeters: Double?,
    /** Tempo (s) de cada trecho: início→1ª parada, 1ª→2ª... só para paradas com coordenada. */
    val legSeconds: List<Double>,
)

object RouteOptimizer {
    private const val UNREACHABLE = 1.0e7        // custo para pares sem caminho (OSRM devolve null)
    private const val EXACT_LIMIT = 13           // até 13 paradas resolve de forma exata (Held-Karp)
    private const val MAX_PASSES = 100
    private const val ILS_ITERATIONS = 150

    /**
     * Ordena PARADAS (nunca pacotes) a partir de [start], caminho aberto (não volta ao início).
     * Custos vêm de [provider] (tempo de viagem pela malha viária, respeitando mão única).
     */
    fun optimize(start: LatLng, stops: List<RouteStop>, provider: MatrixProvider): PlannedRoute {
        require(start.isValid) { "Ponto inicial inválido." }
        val located = stops.filter { it.location?.isValid == true }.sortedBy { it.stopNumber }
        val unlocated = stops.filter { it.location?.isValid != true }.map { it.stopNumber }.sorted()
        if (located.isEmpty()) return PlannedRoute(unlocated, unlocated, 0.0, null, emptyList())

        val points = listOf(start) + located.map { it.location!! }
        val m = provider.matrix(points)
        if (m.size != points.size || m.durations.any { it.size != points.size }) {
            throw RoutingException("O serviço de rotas devolveu uma matriz de tamanho inesperado.")
        }
        val cost = Array(points.size) { i -> DoubleArray(points.size) { j -> m.durations[i][j].let { c -> if (c.isFinite() && c >= 0) c else UNREACHABLE } } }

        val order = solve(cost)                      // índices 1..n em ordem de visita
        val path = listOf(0) + order
        val legs = (0 until path.size - 1).map { cost[path[it]][path[it + 1]] }
        val meters = m.distances?.let { d -> (0 until path.size - 1).sumOf { d[path[it]][path[it + 1]].let { x -> if (x.isFinite() && x >= 0) x else 0.0 } } }
        return PlannedRoute(order.map { located[it - 1].stopNumber } + unlocated, unlocated, legs.sum(), meters, legs)
    }

    /** Custo total do caminho 0 -> order[0] -> order[1] ... */
    fun pathCost(cost: Array<DoubleArray>, order: List<Int>): Double {
        var total = 0.0; var prev = 0
        for (n in order) { total += cost[prev][n]; prev = n }
        return total
    }

    /** Devolve a ordem de visita dos nós 1..n (0 é a origem). */
    fun solve(cost: Array<DoubleArray>): List<Int> {
        val n = cost.size - 1
        if (n <= 0) return emptyList()
        if (n == 1) return listOf(1)
        if (n <= EXACT_LIMIT) return exact(cost)
        return heuristic(cost)
    }

    /**
     * Vizinho mais próximo + 2-opt + or-opt, refinado por busca local iterada (perturbação "double-bridge").
     * Determinístico (semente fixa): a mesma entrada gera sempre a mesma rota.
     */
    fun heuristic(cost: Array<DoubleArray>, iterations: Int = ILS_ITERATIONS): List<Int> {
        val n = cost.size - 1
        if (n <= 3) return if (n <= 0) emptyList() else exact(cost)
        var cur = localSearch(cost, nearestNeighbour(cost))
        var curCost = pathCost(cost, cur)
        val rnd = java.util.Random(20261109L)
        repeat(iterations) {
            val cand = localSearch(cost, perturb(cur, rnd))
            val c = pathCost(cost, cand)
            if (c < curCost - 1e-9) { cur = cand; curCost = c }
        }
        return cur
    }

    private fun localSearch(cost: Array<DoubleArray>, start: List<Int>): List<Int> {
        var order = start
        var best = pathCost(cost, order)
        var passes = 0
        while (passes++ < MAX_PASSES) {
            val improved = twoOpt(cost, order) ?: orOpt(cost, order) ?: break
            val c = pathCost(cost, improved)
            if (c >= best - 1e-9) break
            order = improved; best = c
        }
        return order
    }

    /** Double-bridge: troca dois trechos vizinhos do caminho. Mantém todas as paradas, uma vez cada. */
    private fun perturb(order: List<Int>, rnd: java.util.Random): List<Int> {
        val n = order.size
        val cuts = generateSequence { 1 + rnd.nextInt(n - 1) }.distinct().take(3).toList().sorted()
        val (a, b, c) = cuts
        return order.subList(0, a) + order.subList(b, c) + order.subList(a, b) + order.subList(c, n)
    }

    fun nearestNeighbour(cost: Array<DoubleArray>): List<Int> {
        val n = cost.size - 1
        val left = (1..n).toMutableList(); val out = ArrayList<Int>(n); var cur = 0
        while (left.isNotEmpty()) {
            var bi = 0
            for (i in 1 until left.size) if (cost[cur][left[i]] < cost[cur][left[bi]]) bi = i
            cur = left.removeAt(bi); out.add(cur)
        }
        return out
    }

    /** Held-Karp para caminho aberto com origem fixa em 0. */
    fun exact(cost: Array<DoubleArray>): List<Int> {
        val n = cost.size - 1
        val full = (1 shl n) - 1
        val dp = Array(1 shl n) { DoubleArray(n) { Double.MAX_VALUE } }
        val parent = Array(1 shl n) { IntArray(n) { -1 } }
        for (j in 0 until n) dp[1 shl j][j] = cost[0][j + 1]
        for (mask in 1..full) for (j in 0 until n) {
            if (mask and (1 shl j) == 0 || dp[mask][j] == Double.MAX_VALUE) continue
            for (k in 0 until n) {
                if (mask and (1 shl k) != 0) continue
                val nm = mask or (1 shl k); val c = dp[mask][j] + cost[j + 1][k + 1]
                if (c < dp[nm][k]) { dp[nm][k] = c; parent[nm][k] = j }
            }
        }
        var last = 0
        for (j in 1 until n) if (dp[full][j] < dp[full][last]) last = j
        val out = ArrayList<Int>(); var mask = full; var cur = last
        while (cur != -1) { out.add(cur + 1); val p = parent[mask][cur]; mask = mask xor (1 shl cur); cur = p }
        return out.reversed()
    }

    /** 2-opt para custos assimétricos: inverter o trecho i..j muda também o custo interno. */
    private fun twoOpt(cost: Array<DoubleArray>, order: List<Int>): List<Int>? {
        val p = listOf(0) + order; val n = order.size
        for (i in 1..n) {
            var fwd = 0.0; var rev = 0.0
            for (j in i..n) {
                if (j > i) { fwd += cost[p[j - 1]][p[j]]; rev += cost[p[j]][p[j - 1]] }
                val before = cost[p[i - 1]][p[i]] + fwd + (if (j < n) cost[p[j]][p[j + 1]] else 0.0)
                val after = cost[p[i - 1]][p[j]] + rev + (if (j < n) cost[p[i]][p[j + 1]] else 0.0)
                if (after < before - 1e-9) {
                    val np = p.toMutableList()
                    np.subList(i, j + 1).reverse()
                    return np.drop(1)
                }
            }
        }
        return null
    }

    /** Or-opt: move um trecho de 1 a 3 paradas para outro ponto do caminho. */
    private fun orOpt(cost: Array<DoubleArray>, order: List<Int>): List<Int>? {
        val n = order.size
        val p = listOf(0) + order                       // p[0] = origem
        for (len in 1..3) {
            for (s in 0..n - len) {
                val a = s + 1; val b = s + len          // trecho = p[a..b]
                val first = p[a]; val last = p[b]
                val prev = p[a - 1]; val next = p.getOrNull(b + 1)
                val removeDelta = (if (next != null) cost[prev][next] else 0.0) -
                    cost[prev][first] - (if (next != null) cost[last][next] else 0.0)
                val restP = p.subList(0, a) + p.subList(b + 1, p.size)   // caminho sem o trecho, com 0 na frente
                for (pos in 0 until restP.size) {
                    if (pos == a - 1) continue          // mesma posição de antes
                    val u = restP[pos]; val v = restP.getOrNull(pos + 1)
                    val insertDelta = cost[u][first] + (if (v != null) cost[last][v] - cost[u][v] else 0.0)
                    if (removeDelta + insertDelta < -1e-9) {
                        val seg = p.subList(a, b + 1)
                        return (restP.subList(0, pos + 1) + seg + restP.subList(pos + 1, restP.size)).drop(1)
                    }
                }
            }
        }
        return null
    }
}

object Proximity {
    data class Nearby(val stop: StopRecord, val meters: Double)

    /** Parada ainda não concluída mais próxima, se estiver a até [thresholdMeters] do motorista. */
    fun nearestOpenStop(location: LatLng, stops: List<StopRecord>, thresholdMeters: Double = 100.0): Nearby? =
        stops.filter { it.status != StopStatus.COMPLETED }
            .mapNotNull { s -> s.location?.let { Nearby(s, Geo.haversineMeters(location, it)) } }
            .filter { it.meters <= thresholdMeters }
            .minByOrNull { it.meters }
}

/** Reaplica a ordem otimizada preservando o número Stop original de cada parada. */
object RoutePlan {
    /**
     * Paradas concluídas mantêm suas posições anteriores; as pendentes são reordenadas por [route] logo depois.
     * Retorna stopNumber -> nova ordem (1..N).
     */
    fun assignOrders(stops: List<StopRecord>, route: PlannedRoute): Map<Int, Int> {
        val done = stops.filter { it.status == StopStatus.COMPLETED }
            .sortedWith(compareBy({ it.optimizedOrder ?: Int.MAX_VALUE }, { it.stopNumber })).map { it.stopNumber }
        val pendingSet = stops.filter { it.status != StopStatus.COMPLETED }.map { it.stopNumber }.toSet()
        val pending = route.orderedStops.filter { it in pendingSet }
        val missing = pendingSet - pending.toSet()   // segurança: nenhuma parada pode sumir da ordem
        require(missing.isEmpty()) { "A rota calculada não inclui as paradas $missing." }
        return (done + pending).mapIndexed { i, n -> n to i + 1 }.toMap()
    }
}
