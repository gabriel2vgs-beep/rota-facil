package br.com.rotafacil.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class RouteTest {

    /** Provedor falso baseado em tempo de direção "sintético": linha reta × fator assimétrico por par. */
    private class FakeMatrix(val factor: (Int, Int) -> Double = { _, _ -> 1.0 }, val speed: Double = 8.0) : MatrixProvider {
        var calls = 0
        override fun matrix(points: List<LatLng>): MatrixData {
            calls++
            val n = points.size
            val d = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else Geo.haversineMeters(points[i], points[j]) * factor(i, j) / speed } }
            return MatrixData(d)
        }
    }

    private fun randomCost(n: Int, seed: Long): Array<DoubleArray> {
        val r = Random(seed)
        return Array(n + 1) { i -> DoubleArray(n + 1) { j -> if (i == j) 0.0 else 1 + r.nextInt(100).toDouble() } }
    }

    private fun bruteForce(cost: Array<DoubleArray>): Double {
        val n = cost.size - 1
        var best = Double.MAX_VALUE
        fun rec(cur: Int, left: List<Int>, acc: Double) {
            if (acc >= best) return
            if (left.isEmpty()) { best = acc; return }
            for (x in left) rec(x, left - x, acc + cost[cur][x])
        }
        rec(0, (1..n).toList(), 0.0)
        return best
    }

    @Test fun exato_bate_com_forca_bruta_em_matrizes_assimetricas() {
        for (seed in 1L..40L) for (n in 2..7) {
            val c = randomCost(n, seed * 31 + n)
            val order = RouteOptimizer.solve(c)
            assertEquals(n, order.toSet().size)
            assertEquals("seed=$seed n=$n", bruteForce(c), RouteOptimizer.pathCost(c, order), 1e-9)
        }
    }

    @Test fun heuristica_devolve_permutacao_valida_e_nao_pior_que_vizinho_mais_proximo() {
        for (seed in 1L..10L) for (n in intArrayOf(12, 25, 60, 171)) {
            val c = randomCost(n, seed * 7 + n)
            val order = RouteOptimizer.solve(c)
            assertEquals("todas as paradas exatamente uma vez", (1..n).toList(), order.sorted())
            val nn = RouteOptimizer.pathCost(c, RouteOptimizer.nearestNeighbour(c))
            assertTrue("n=$n seed=$seed: ${RouteOptimizer.pathCost(c, order)} > $nn", RouteOptimizer.pathCost(c, order) <= nn + 1e-9)
        }
    }

    @Test fun heuristica_fica_perto_do_otimo_em_instancias_de_12_a_14_paradas() {
        // compara a HEURÍSTICA (não o solver exato) com o ótimo exato, em matrizes aleatórias assimétricas (casos difíceis)
        var worst = 1.0; var sum = 0.0; var count = 0
        for (n in intArrayOf(12, 13, 14)) for (seed in 1L..12L) {
            val c = randomCost(n, seed * 13 + n)
            val h = RouteOptimizer.pathCost(c, RouteOptimizer.heuristic(c))
            val opt = RouteOptimizer.pathCost(c, RouteOptimizer.exact(c))
            assertTrue(h >= opt - 1e-9)
            worst = maxOf(worst, h / opt); sum += h / opt; count++
        }
        println("   [heurística] razão heurística/ótimo: pior=%.3f média=%.3f (%d instâncias)".format(worst, sum / count, count))
        assertTrue("pior razão heurística/ótimo = $worst", worst < 1.15)
    }

    @Test fun solve_usa_exato_ate_o_limite() {
        for (seed in 1L..5L) {
            val c = randomCost(13, seed)
            assertEquals(RouteOptimizer.pathCost(c, RouteOptimizer.exact(c)), RouteOptimizer.pathCost(c, RouteOptimizer.solve(c)), 1e-9)
        }
    }

    @Test fun rota_segue_o_custo_da_malha_viaria_e_nao_a_linha_reta() {
        // Início em (0,0). A fica em linha reta MUITO perto, mas chegar nela exige um desvio enorme (mão única).
        val start = LatLng(-23.50, -46.10)
        val a = RouteStop(10, LatLng(-23.5005, -46.10))     // ~55 m em linha reta
        val b = RouteStop(20, LatLng(-23.5100, -46.10))     // ~1,1 km em linha reta
        // índices na matriz: 0=start, 1=A, 2=B. Início→A custa 30 min (desvio); Início→B 3 min; B→A 1 min.
        val provider = MatrixProvider {
            MatrixData(arrayOf(
                doubleArrayOf(0.0, 1800.0, 180.0),
                doubleArrayOf(600.0, 0.0, 700.0),
                doubleArrayOf(60.0, 90.0, 0.0),
            ))
        }
        val straightLineFirst = Geo.haversineMeters(start, a.location!!) < Geo.haversineMeters(start, b.location!!)
        assertTrue(straightLineFirst)   // confirma a "armadilha"
        val route = RouteOptimizer.optimize(start, listOf(a, b), provider)
        assertEquals("B primeiro (mais rápido pelas ruas), depois A", listOf(20, 10), route.orderedStops)
        assertEquals(180.0 + 90.0, route.totalSeconds, 1e-9)
    }

    @Test fun assimetria_ida_e_volta_e_respeitada() {
        val start = LatLng(-23.50, -46.10)
        val s1 = RouteStop(1, LatLng(-23.501, -46.10)); val s2 = RouteStop(2, LatLng(-23.502, -46.10)); val s3 = RouteStop(3, LatLng(-23.503, -46.10))
        // rua de mão única: só é rápido ir 3 -> 2 -> 1
        val cheap = 10.0; val dear = 1000.0
        val m = arrayOf(
            doubleArrayOf(0.0, dear, dear, cheap),
            doubleArrayOf(dear, 0.0, dear, dear),
            doubleArrayOf(dear, cheap, 0.0, dear),
            doubleArrayOf(dear, dear, cheap, 0.0),
        )
        val r = RouteOptimizer.optimize(start, listOf(s1, s2, s3), { MatrixData(m) })
        assertEquals(listOf(3, 2, 1), r.orderedStops)
    }

    @Test fun paradas_sem_coordenada_vao_para_o_fim_sem_sumir() {
        val start = LatLng(-23.5, -46.1)
        val stops = listOf(RouteStop(5, null), RouteStop(2, LatLng(-23.51, -46.1)), RouteStop(9, LatLng(-23.52, -46.1)), RouteStop(1, null))
        val r = RouteOptimizer.optimize(start, stops, FakeMatrix())
        assertEquals(listOf(1, 5), r.unlocatedStops)
        assertEquals(setOf(1, 2, 5, 9), r.orderedStops.toSet()); assertEquals(4, r.orderedStops.size)
        assertEquals(listOf(1, 5), r.orderedStops.takeLast(2))
    }

    @Test fun todas_sem_coordenada_nao_chama_o_servico() {
        val fm = FakeMatrix()
        val r = RouteOptimizer.optimize(LatLng(-23.5, -46.1), listOf(RouteStop(3, null), RouteStop(1, null)), fm)
        assertEquals(0, fm.calls); assertEquals(listOf(1, 3), r.orderedStops)
    }

    @Test fun ponto_inicial_invalido_e_recusado() {
        try { RouteOptimizer.optimize(LatLng(0.0, 0.0), emptyList(), FakeMatrix()); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun servico_fora_do_ar_propaga_erro_e_nada_e_reordenado() {
        val p = MatrixProvider { throw RoutingException("sem internet") }
        try { RouteOptimizer.optimize(LatLng(-23.5, -46.1), listOf(RouteStop(1, LatLng(-23.51, -46.1))), p); fail() }
        catch (e: RoutingException) { assertEquals("sem internet", e.message) }
    }

    // --- com os dados reais ---------------------------------------------------------

    private fun realStops(): List<StopRecord> {
        val pv = ImportTest.preview()
        return InMemoryDeliveryStore().load(1, pv).stops.values.toList()
    }

    @Test fun rota_real_com_171_paradas_inclui_todas_uma_unica_vez_e_preserva_numeros() {
        val stops = realStops()
        val start = LatLng(-23.4845, -46.1300)   // ponto inicial de teste, em Mogi das Cruzes
        val t0 = System.nanoTime()
        val route = RouteOptimizer.optimize(start, stops.map { RouteStop(it.stopNumber, it.location) }, FakeMatrix({ i, j -> if ((i * 7 + j) % 5 == 0) 1.6 else 1.0 }))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(171, route.orderedStops.size)
        assertEquals((1..171).toList(), route.orderedStops.sorted())
        assertTrue("levou ${ms}ms", ms < 20_000)
        val orders = RoutePlan.assignOrders(stops, route)
        assertEquals((1..171).toList(), orders.values.sorted())
        assertEquals((1..171).toSet(), orders.keys)
        println("   [rota real] 171 paradas otimizadas em ${ms}ms; tempo total estimado (modelo sintético) = ${(route.totalSeconds / 60).toInt()} min")
    }

    @Test fun pacotes_continuam_agrupados_apos_otimizar() {
        val pv = ImportTest.preview()
        val store = InMemoryDeliveryStore().load(1, pv)
        val route = RouteOptimizer.optimize(LatLng(-23.4845, -46.1300), store.stops.values.map { RouteStop(it.stopNumber, it.location) }, FakeMatrix())
        val orders = RoutePlan.assignOrders(store.stops.values.toList(), route)
        store.stops.keys.toList().forEach { k -> store.stops[k] = store.stops.getValue(k).copy(optimizedOrder = orders[k.second]) }
        // a otimização só mexe na ordem das paradas; pacotes seguem apontando para o mesmo Stop original
        val rows = ImportTest.table().rows
        for (p in store.packages.values) {
            val row = rows[p.sequence - 1]
            assertEquals(row[3], p.trackingCode); assertEquals(row[2].toInt(), p.stopNumber)
        }
        assertEquals(171, store.stops.values.map { it.optimizedOrder }.toSet().size)
        assertEquals(200, store.packages.size)
    }

    @Test fun replanejar_a_partir_da_posicao_atual_mantem_concluidas_e_reordena_pendentes() {
        val pv = ImportTest.preview()
        val store = InMemoryDeliveryStore().load(1, pv); val engine = DeliveryEngine(store)
        val first = RouteOptimizer.optimize(LatLng(-23.4845, -46.1300), store.stops.values.map { RouteStop(it.stopNumber, it.location) }, FakeMatrix())
        var orders = RoutePlan.assignOrders(store.stops.values.toList(), first)
        store.stops.keys.toList().forEach { k -> store.stops[k] = store.stops.getValue(k).copy(optimizedOrder = orders[k.second]) }

        // motorista conclui as 25 primeiras paradas da rota
        val doneStops = first.orderedStops.take(25)
        doneStops.forEach { sn -> engine.markStopDelivered(1, sn, 1) }
        val stopsNow = store.stops.values.toList()
        assertEquals(25, stopsNow.count { it.status == StopStatus.COMPLETED })

        // posição nova: no meio da cidade, longe do começo. Replaneja só as pendentes.
        val here = LatLng(-23.5290, -46.1700)
        val pending = stopsNow.filter { it.status != StopStatus.COMPLETED }
        val second = RouteOptimizer.optimize(here, pending.map { RouteStop(it.stopNumber, it.location) }, FakeMatrix())
        assertEquals(pending.size, second.orderedStops.size)
        orders = RoutePlan.assignOrders(stopsNow, second)
        assertEquals("concluídas mantêm as posições 1..25 na ordem original", doneStops, orders.entries.filter { it.value <= 25 }.sortedBy { it.value }.map { it.key })
        assertEquals((1..171).toList(), orders.values.sorted())
        assertEquals(second.orderedStops, orders.entries.filter { it.value > 25 }.sortedBy { it.value }.map { it.key })
    }

    @Test fun proximidade_detecta_parada_aberta_a_menos_de_100m() {
        val stops = realStops()
        val target = stops.first { it.stopNumber == 10 }
        val near = LatLng(target.latitude!! + 0.0003, target.longitude!!)      // ~33 m
        val hit = Proximity.nearestOpenStop(near, stops)
        assertNotNull(hit); assertEquals(10, hit!!.stop.stopNumber); assertTrue(hit.meters < 50)
        assertNull(Proximity.nearestOpenStop(LatLng(target.latitude!! + 0.01, target.longitude!!), stops))
        val done = stops.map { if (it.stopNumber == 10) it.copy(status = StopStatus.COMPLETED) else it }
        assertTrue(Proximity.nearestOpenStop(near, done)?.stop?.stopNumber != 10)
    }

    // --- OSRM (resposta simulada) ----------------------------------------------------------

    /** Servidor OSRM falso: interpreta a URL como o OSRM e responde com haversine. */
    private class FakeOsrm(val nullPairs: Set<Pair<Int, Int>> = emptySet(), var code: String = "Ok") : HttpGet {
        val urls = ArrayList<String>()
        override fun get(url: String): String {
            urls += url
            val path = url.substringAfter("/table/v1/driving/").substringBefore("?")
            val query = url.substringAfter("?").split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
            val coords = path.split(";").map { val (lng, lat) = it.split(","); LatLng(lat.toDouble(), lng.toDouble()) }
            val src = query["sources"]?.split(";")?.map { it.toInt() } ?: coords.indices.toList()
            val dst = query["destinations"]?.split(";")?.map { it.toInt() } ?: coords.indices.toList()
            if (code != "Ok") return """{"code":"$code","message":"falhou"}"""
            fun cell(i: Int, j: Int, scale: Double) = if ((i to j) in nullPairs) "null" else (Geo.haversineMeters(coords[i], coords[j]) * scale).toString()
            val dur = src.joinToString(",", "[", "]") { i -> dst.joinToString(",", "[", "]") { j -> cell(i, j, 0.1) } }
            val dis = src.joinToString(",", "[", "]") { i -> dst.joinToString(",", "[", "]") { j -> cell(i, j, 1.0) } }
            return """{"code":"Ok","durations":$dur,"distances":$dis}"""
        }
    }

    private fun pts(n: Int) = (0 until n).map { LatLng(-23.48 - it * 0.0007 + (it % 7) * 0.0003, -46.13 - it * 0.0005 + (it % 5) * 0.0004) }

    @Test fun osrm_url_usa_lng_lat_e_anotacoes() {
        val fake = FakeOsrm()
        OsrmMatrixProvider(fake).matrix(listOf(LatLng(-23.5, -46.1), LatLng(-23.6, -46.2)))
        assertEquals(1, fake.urls.size)
        assertTrue(fake.urls[0], fake.urls[0].startsWith("https://router.project-osrm.org/table/v1/driving/-46.100000,-23.500000;-46.200000,-23.600000?annotations=duration,distance"))
    }

    @Test fun osrm_matriz_pequena_em_uma_unica_requisicao() {
        val fake = FakeOsrm(); val p = pts(30)
        val m = OsrmMatrixProvider(fake).matrix(p)
        assertEquals(1, fake.urls.size)
        assertEquals(Geo.haversineMeters(p[3], p[17]) * 0.1, m.durations[3][17], 1e-6)
        assertEquals(Geo.haversineMeters(p[3], p[17]), m.distances!![3][17], 1e-6)
    }

    @Test fun osrm_matriz_grande_e_dividida_em_blocos_e_montada_corretamente() {
        val fake = FakeOsrm(); val p = pts(172)   // 1 início + 171 paradas
        val m = OsrmMatrixProvider(fake, maxCoordsPerRequest = 90).matrix(p)
        assertTrue(fake.urls.size > 1)
        assertTrue("nenhuma requisição passa de 90 coordenadas", fake.urls.all { it.substringAfter("/driving/").substringBefore("?").split(";").size <= 90 })
        for (i in 0 until 172) for (j in 0 until 172) {
            assertFalse("($i,$j) ficou sem valor", m.durations[i][j].isNaN())
            assertEquals("($i,$j)", Geo.haversineMeters(p[i], p[j]) * 0.1, m.durations[i][j], 1e-6)
        }
        println("   [osrm simulado] 172 pontos montados com ${fake.urls.size} requisições")
    }

    @Test fun osrm_pares_sem_caminho_viram_custo_alto_e_a_rota_ainda_sai() {
        val fake = FakeOsrm(nullPairs = setOf(0 to 1)); val p = pts(6)
        val route = RouteOptimizer.optimize(p[0], p.drop(1).mapIndexed { i, l -> RouteStop(i + 1, l) }, OsrmMatrixProvider(fake))
        assertEquals((1..5).toList(), route.orderedStops.sorted())
        assertNotEquals("não começa pelo par inalcançável", 1, route.orderedStops.first())
    }

    @Test fun osrm_erro_do_servico_vira_RoutingException() {
        try { OsrmMatrixProvider(FakeOsrm(code = "TooBig")).matrix(pts(4)); fail() } catch (e: RoutingException) { assertTrue(e.message!!.contains("TooBig")) }
        try { OsrmMatrixProvider(HttpGet { "isto não é json" }).matrix(pts(3)); fail() } catch (_: RoutingException) { }
        try { OsrmMatrixProvider(HttpGet { throw RoutingException("offline") }).matrix(pts(3)); fail() } catch (e: RoutingException) { assertEquals("offline", e.message) }
    }

    @Test fun osrm_geometria_da_rota() {
        val body = """{"code":"Ok","routes":[{"geometry":{"type":"LineString","coordinates":[[-46.1,-23.5],[-46.11,-23.51],[-46.12,-23.52]]}}]}"""
        var url = ""
        val g = OsrmRouteGeometry(HttpGet { url = it; body }).path(listOf(LatLng(-23.5, -46.1), LatLng(-23.52, -46.12)))
        assertEquals(3, g.size); assertEquals(LatLng(-23.51, -46.11), g[1])
        assertTrue(url.contains("/route/v1/driving/-46.100000,-23.500000;-46.120000,-23.520000?overview=full&geometries=geojson"))
    }

    @Test fun json_basico() {
        val v = Json.parse("""{"a":[1,2.5,null,"x\"yé"],"b":{"c":true},"d":-1e2}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, null, "x\"yé"), v["a"]); assertEquals(true, (v["b"] as Map<*, *>)["c"]); assertEquals(-100.0, v["d"])
        try { Json.parse("{"); fail() } catch (_: Exception) { }
    }
}
