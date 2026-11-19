package com.a6400sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Depuração automática: foco, exposição e duplicatas de rajada. */
object Triage {
    data class Result(val keep: Boolean, val reasons: List<String>, val sharpness: Double, val hash: Long)

    var blurThreshold = 60.0        // variância do Laplaciano abaixo disso = desfocada/tremida
    var duplicateDistance = 6       // diferença de hash <= isso = praticamente igual à anterior
    private val recentHashes = ArrayDeque<Pair<Long, Double>>()

    fun analyze(jpeg: ByteArray): Result {
        val bmp = decodeSmall(jpeg, 512)
        val gray = toGray(bmp)
        val w = bmp.width; val h = bmp.height
        val reasons = mutableListOf<String>()

        val sharp = laplacianVariance(gray, w, h)
        if (sharp < blurThreshold) reasons += "desfocada"

        val hist = IntArray(256).also { hst -> gray.forEach { hst[it]++ } }
        val total = gray.size.toDouble()
        val dark = hist.take(16).sum() / total
        val bright = hist.takeLast(6).sum() / total
        if (dark > 0.55) reasons += "subexposta"
        if (bright > 0.30) reasons += "estourada"

        val hash = dHash(bmp)
        val dup = recentHashes.firstOrNull { java.lang.Long.bitCount(it.first xor hash) <= duplicateDistance }
        if (dup != null && dup.second >= sharp) reasons += "duplicada"
        recentHashes.addFirst(hash to sharp); if (recentHashes.size > 10) recentHashes.removeLast()

        bmp.recycle()
        return Result(reasons.isEmpty(), reasons, sharp, hash)
    }

    fun decodeSmall(bytes: ByteArray, maxSide: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun toGray(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000 }
    }

    private fun laplacianVariance(g: IntArray, w: Int, h: Int): Double {
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble()
            sum += l; sq += l * l; n++
        }
        val m = sum / n; return sq / n - m * m
    }

    /** Hash perceptual (dHash 8x8) pra detectar fotos repetidas. */
    private fun dHash(b: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(b, 9, 8, true)
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val a = s.getPixel(x, y) and 255; val c = s.getPixel(x + 1, y) and 255
            hash = (hash shl 1) or if (a > c) 1 else 0
        }
        s.recycle(); return hash
    }

}
