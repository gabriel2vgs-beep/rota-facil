package br.com.rotafacil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import br.com.rotafacil.core.ProofRules
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Fotos de prova ficam no armazenamento PRIVADO do app (filesDir/proofs/<romaneio>/), funcionam offline
 * e só aparecem na galeria se você compartilhar. Cada foto é reduzida (lado maior 1600 px, JPEG 80%) para ocupar ~300 KB.
 */
object PhotoStorage {
    private fun pendingDir(ctx: Context) = File(ctx.cacheDir, "proofs_pending").apply { mkdirs() }
    fun rawTempFile(ctx: Context): File = File(pendingDir(ctx), "raw_${System.nanoTime()}.jpg")
    fun proofDir(ctx: Context, romaneioId: Long) = File(ctx.filesDir, "proofs/$romaneioId")

    /** Corrige a rotação (EXIF), reduz e comprime. Devolve um arquivo temporário pronto para revisar/gravar. Apaga o original. */
    fun prepare(ctx: Context, raw: File): File {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Imagem inválida." }
            val opts = BitmapFactory.Options().apply { inSampleSize = ProofRules.sampleSize(bounds.outWidth, bounds.outHeight) }
            var bmp = BitmapFactory.decodeFile(raw.path, opts) ?: error("Não consegui ler a foto.")

            val orientation = ExifInterface(raw.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            }
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)

            val (w, h) = ProofRules.scaledSize(bmp.width, bmp.height)
            if (w != bmp.width || h != bmp.height) bmp = Bitmap.createScaledBitmap(bmp, w, h, true)

            val out = File(pendingDir(ctx), "ready_${System.nanoTime()}.jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, ProofRules.JPEG_QUALITY, it) }
            return out
        } finally {
            raw.delete()
        }
    }

    data class Stored(val file: File, val sha256: String)

    /** Move a foto preparada para a pasta definitiva do romaneio. Nunca sobrescreve outra foto. */
    fun commit(ctx: Context, prepared: File, romaneioId: Long, baseName: String): Stored {
        val dir = proofDir(ctx, romaneioId).apply { mkdirs() }
        var dest = File(dir, baseName); var n = 1
        while (dest.exists()) { dest = File(dir, baseName.removeSuffix(".jpg") + "_$n.jpg"); n++ }
        prepared.copyTo(dest, overwrite = false)
        val sha = sha256(dest)
        prepared.delete()
        return Stored(dest, sha)
    }

    fun discard(file: File?) { try { file?.delete() } catch (_: Exception) { } }
    fun deleteAll(ctx: Context, romaneioId: Long) { try { proofDir(ctx, romaneioId).deleteRecursively() } catch (_: Exception) { } }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val buf = ByteArray(8192); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Miniatura leve para listas. */
    fun thumbnail(path: String, targetPx: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        if (b.outWidth <= 0) return null
        var s = 1
        while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= targetPx) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }
}
