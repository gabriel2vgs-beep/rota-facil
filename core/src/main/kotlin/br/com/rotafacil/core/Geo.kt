package br.com.rotafacil.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double) {
    val isValid: Boolean get() = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 &&
        !(Math.abs(lat) < 1e-6 && Math.abs(lng) < 1e-6)
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Distância em linha reta (m). Usada só para proximidade e para exibir "≈ X m"; NÃO para ordenar a rota. */
    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    fun haversineMeters(a: LatLng, b: LatLng) = haversineMeters(a.lat, a.lng, b.lat, b.lng)

    fun formatDistance(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(java.util.Locale.ROOT, meters / 1000)
}
