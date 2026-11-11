package br.com.rotafacil.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Foto de prova de entrega.
 * [packageId] == null → foto da PARADA inteira (vale para todos os pacotes dela).
 * [packageId] != null → foto de um pacote específico.
 */
data class ProofPhotoRecord(
    val id: Long,
    val romaneioId: Long,
    val stopNumber: Int,
    val packageId: Long?,
    val filePath: String,
    val takenAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val sha256: String,
)

object ProofRules {
    const val MAX_SIDE_PX = 1600
    const val JPEG_QUALITY = 80

    /** A foto cobre o pacote? Mesma parada do mesmo romaneio, e ou é foto da parada ou é foto daquele pacote. */
    fun covers(photo: ProofPhotoRecord, pkg: PackageRecord): Boolean =
        photo.romaneioId == pkg.romaneioId && photo.stopNumber == pkg.stopNumber &&
            (photo.packageId == null || photo.packageId == pkg.id)

    fun hasProof(pkg: PackageRecord, photos: List<ProofPhotoRecord>): Boolean = photos.any { covers(it, pkg) }

    /** Pacotes AINDA PENDENTES que não têm foto que os cubra. */
    fun pendingWithoutProof(stopPackages: List<PackageRecord>, photos: List<ProofPhotoRecord>): List<PackageRecord> =
        stopPackages.filter { it.status == PackageStatus.PENDING && !hasProof(it, photos) }

    /** Antes de entregar [targets], precisa abrir a câmera? Só se o ajuste estiver ligado e algum alvo estiver sem prova. */
    fun needsPhoto(required: Boolean, targets: List<PackageRecord>, photos: List<ProofPhotoRecord>): Boolean =
        required && targets.any { !hasProof(it, photos) }

    /** Reduz mantendo a proporção para que o lado maior seja no máximo [max]. Nunca aumenta a imagem. */
    fun scaledSize(width: Int, height: Int, max: Int = MAX_SIDE_PX): Pair<Int, Int> {
        require(width > 0 && height > 0 && max > 0) { "Dimensões inválidas." }
        val longest = maxOf(width, height)
        if (longest <= max) return width to height
        val f = max.toDouble() / longest
        return maxOf(1, Math.round(width * f).toInt()) to maxOf(1, Math.round(height * f).toInt())
    }

    /** Potência de 2 para BitmapFactory.inSampleSize: maior valor que ainda deixa o lado maior >= [max]. */
    fun sampleSize(width: Int, height: Int, max: Int = MAX_SIDE_PX): Int {
        var s = 1
        val longest = maxOf(width, height)
        while (longest / (s * 2) >= max) s *= 2
        return s
    }

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Nome de arquivo seguro (só letras, números, "-" e "_"): nunca contém "/" ou "..", mesmo com código estranho. */
    fun fileName(stopNumber: Int, trackingCode: String?, takenAt: Long, zone: ZoneId, unique: Int = 0): String {
        val code = trackingCode?.filter { it.isLetterOrDigit() && it.code < 128 }?.take(32).orEmpty()
        val time = stamp.format(Instant.ofEpochMilli(takenAt).atZone(zone))
        val suffix = if (unique > 0) "_$unique" else ""
        return "parada-%03d%s_%s%s.jpg".format(java.util.Locale.ROOT, stopNumber, if (code.isEmpty()) "" else "_$code", time, suffix)
    }
}
