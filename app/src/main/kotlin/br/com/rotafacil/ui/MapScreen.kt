package br.com.rotafacil.ui

import android.graphics.Color as AColor
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.rotafacil.MainViewModel
import br.com.rotafacil.core.StopStatus
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Mapa OpenStreetMap (sem chave de API). Os blocos de mapa já vistos ficam em cache; sem internet o mapa pode ficar vazio, mas a lista de paradas continua funcionando. */
@Composable
fun MapScreen(vm: MainViewModel, onOpen: (Int) -> Unit) {
    val ctx = LocalContext.current
    val stops by vm.orderedStops.collectAsStateWithLifecycle()
    val loc by vm.location.collectAsStateWithLifecycle()
    val path by vm.routePath.collectAsStateWithLifecycle()
    val busy by vm.routeBusy.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            load(ctx, ctx.getSharedPreferences("osmdroid", 0))
            userAgentValue = ctx.packageName
        }
    }
    LaunchedEffect(stops.size) { if (path.isEmpty() && stops.isNotEmpty()) vm.loadRoutePath() }

    Column(Modifier.fillMaxSize()) {
        Title("Mapa", "Paradas abertas numeradas pela ordem da rota")
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c -> MapView(c).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(14.0) } },
                update = { map ->
                    map.overlays.clear()
                    val open = stops.filter { it.status != StopStatus.COMPLETED && it.location != null }
                    val pts = ArrayList<GeoPoint>()
                    loc?.let { l ->
                        pts += GeoPoint(l.lat, l.lng)
                        map.overlays.add(Marker(map).apply { position = GeoPoint(l.lat, l.lng); title = "Você está aqui"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER) })
                    }
                    if (path.size >= 2) map.overlays.add(Polyline().apply { setPoints(path.map { GeoPoint(it.lat, it.lng) }); outlinePaint.color = AColor.parseColor("#3B82F6"); outlinePaint.strokeWidth = 10f })
                    open.forEach { s ->
                        val gp = GeoPoint(s.location!!.lat, s.location!!.lng); pts += gp
                        map.overlays.add(Marker(map).apply {
                            position = gp
                            title = "Parada ${s.stopNumber}" + (s.optimizedOrder?.let { " • rota nº $it" } ?: "")
                            snippet = "${s.address} • ${s.packageCount} pacote(s)"
                            setOnMarkerClickListener { m, _ -> if (m.isInfoWindowShown) { onOpen(s.stopNumber); true } else { m.showInfoWindow(); true } }
                        })
                    }
                    if (pts.size >= 2) map.post { try { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), false, 80) } catch (_: Exception) { } }
                    else if (pts.size == 1) map.controller.setCenter(pts[0])
                    map.invalidate()
                },
            )
        }
        Row(Modifier.padding(16.dp)) {
            BigButton(if (busy) "RECALCULANDO…" else "RECALCULAR A PARTIR DE AQUI", { vm.organizeRoute() }, Modifier.fillMaxWidth(), enabled = !busy && stops.isNotEmpty())
        }
    }
}
