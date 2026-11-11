package br.com.rotafacil

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import br.com.rotafacil.core.HttpGet
import br.com.rotafacil.core.LatLng
import br.com.rotafacil.core.OsrmMatrixProvider
import br.com.rotafacil.core.RawTable
import br.com.rotafacil.core.RoutingException
import br.com.rotafacil.core.XlsReader
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

/** Preferências simples (tema, app de navegação, servidor de rotas, ponto inicial manual). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("rotafacil", Context.MODE_PRIVATE)

    /** 0 = seguir o sistema, 1 = claro, 2 = escuro. */
    val themeMode = MutableStateFlow(sp.getInt("theme", 0))
    /** "maps" ou "waze". */
    val navApp = MutableStateFlow(sp.getString("nav", "maps") ?: "maps")
    /** Se ligado, ENTREGUE só é aceito depois de uma foto de prova. */
    val requirePhoto = MutableStateFlow(sp.getBoolean("requirePhoto", false))
    val osrmUrl = MutableStateFlow(sp.getString("osrm", OsrmMatrixProvider.DEFAULT_URL) ?: OsrmMatrixProvider.DEFAULT_URL)
    val manualStart = MutableStateFlow(
        sp.getString("start", null)?.split(",")?.takeIf { it.size == 2 }?.let { p ->
            val la = p[0].toDoubleOrNull(); val ln = p[1].toDoubleOrNull()
            if (la != null && ln != null) LatLng(la, ln).takeIf { it.isValid } else null
        }
    )

    fun setTheme(v: Int) { sp.edit().putInt("theme", v).apply(); themeMode.value = v }
    fun setNavApp(v: String) { sp.edit().putString("nav", v).apply(); navApp.value = v }
    fun setRequirePhoto(v: Boolean) { sp.edit().putBoolean("requirePhoto", v).apply(); requirePhoto.value = v }
    fun setOsrmUrl(v: String) {
        val clean = v.trim().ifEmpty { OsrmMatrixProvider.DEFAULT_URL }
        sp.edit().putString("osrm", clean).apply(); osrmUrl.value = clean
    }
    fun setManualStart(p: LatLng?) {
        sp.edit().apply { if (p == null) remove("start") else putString("start", "${p.lat},${p.lng}") }.apply()
        manualStart.value = p
    }
}

/** Aceita "-23.4833, -46.1307" (ponto decimal), como copiado do Google Maps. */
fun parseLatLng(text: String): LatLng? {
    val parts = text.trim().split(',', ';').map { it.trim() }
    if (parts.size != 2) return null
    val la = parts[0].toDoubleOrNull() ?: return null
    val ln = parts[1].toDoubleOrNull() ?: return null
    return LatLng(la, ln).takeIf { it.isValid }
}

object Http : HttpGet {
    override fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 40_000
            c.setRequestProperty("User-Agent", "RotaFacil/1.0 (Android)")
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RoutingException("O serviço de rotas respondeu HTTP $code.")
            return body
        } catch (e: IOException) {
            throw RoutingException("Sem conexão com o serviço de rotas.", e)
        } finally {
            c.disconnect()
        }
    }
}

enum class GpsStatus(val text: String) {
    NO_PERMISSION("GPS sem permissão"),
    DISABLED("GPS desligado"),
    SEARCHING("Buscando sinal de GPS…"),
    OK("GPS ativo"),
}

/** Localização do motorista usando o LocationManager do Android (GPS + rede). Sem dependência de Google Play Services. */
class LocationTracker(private val ctx: Context) : LocationListener {
    val location = MutableStateFlow<LatLng?>(null)
    val status = MutableStateFlow(GpsStatus.NO_PERMISSION)
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        stop()
        if (!hasPermission()) { status.value = GpsStatus.NO_PERMISSION; return }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            try { lm.isProviderEnabled(it) } catch (_: Exception) { false }
        }
        if (providers.isEmpty()) { status.value = GpsStatus.DISABLED; return }
        status.value = GpsStatus.SEARCHING
        try {
            providers.forEach { lm.requestLocationUpdates(it, 3_000L, 5f, this, Looper.getMainLooper()) }
            running = true
            providers.mapNotNull { lm.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }
                .maxByOrNull { it.time }?.let { publish(it) }
        } catch (_: SecurityException) {
            status.value = GpsStatus.NO_PERMISSION
        }
    }

    fun stop() {
        if (running) { try { lm.removeUpdates(this) } catch (_: Exception) { } }
        running = false
    }

    private fun publish(l: Location) {
        val p = LatLng(l.latitude, l.longitude)
        if (p.isValid) { location.value = p; status.value = GpsStatus.OK }
    }

    override fun onLocationChanged(l: Location) = publish(l)
    override fun onProviderEnabled(provider: String) { start() }
    override fun onProviderDisabled(provider: String) { start() }
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}

/** Leitor de .xls (Excel 97-2003) com a biblioteca JXL. Não foi possível testar aqui; veja o README. */
class JxlXlsReader : XlsReader {
    override fun read(bytes: ByteArray): RawTable {
        val wb = try { jxl.Workbook.getWorkbook(ByteArrayInputStream(bytes)) } catch (e: Exception) {
            throw br.com.rotafacil.core.UnsupportedFormatException("Não consegui abrir o .xls (${e.message}). Salve como .xlsx ou .csv e tente de novo.")
        }
        try {
            val sheet = wb.getSheet(0)
            val rows = (0 until sheet.rows).map { r ->
                (0 until sheet.columns).map { c ->
                    val cell = sheet.getCell(c, r)
                    if (cell.type == jxl.CellType.NUMBER && cell is jxl.NumberCell) {
                        // valor puro, sem formatação regional (evita "-23,48" ou arredondamentos)
                        BigDecimal(cell.value.toString()).stripTrailingZeros().toPlainString()
                    } else cell.contents
                }
            }.filter { row -> row.any { it.isNotBlank() } }
            if (rows.isEmpty()) return RawTable(emptyList(), emptyList(), sheet.name)
            return RawTable(rows.first().map { it.trim() }, rows.drop(1), sheet.name)
        } finally {
            wb.close()
        }
    }
}

/** Abre Google Maps ou Waze com a rota até a parada. */
fun openNavigation(ctx: Context, app: String, lat: Double, lng: Double) {
    val intents = if (app == "waze") listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("waze://?ll=$lat,$lng&navigate=yes")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?ll=$lat,$lng&navigate=yes")),
    ) else listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=d")).setPackage("com.google.android.apps.maps"),
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng")),
    )
    for (i in intents) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: ActivityNotFoundException) { }
    }
}
