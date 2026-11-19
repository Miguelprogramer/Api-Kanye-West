package com.a6400sync

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * Edição automática do JPEG: balanço de branco (gray-world), níveis automáticos
 * (estica o histograma entre os percentis 0,5% e 99,5%) e um leve ganho de saturação.
 * O original fica guardado; a editada é salva como cópia.
 */
object AutoEdit {
    var saturation = 1.12f
    var maxSide = 4096   // limite pra não estourar memória do celular

    fun apply(jpeg: ByteArray): ByteArray {
        val src = Triage.decodeSmall(jpeg, maxSide)
        val sample = Bitmap.createScaledBitmap(src, 256, 256 * src.height / src.width, true)
        val px = IntArray(sample.width * sample.height).also { sample.getPixels(it, 0, sample.width, 0, 0, sample.width, sample.height) }

        var r = 0.0; var g = 0.0; var b = 0.0
        val lum = IntArray(256)
        for (c in px) {
            val cr = c shr 16 and 255; val cg = c shr 8 and 255; val cb = c and 255
            r += cr; g += cg; b += cb; lum[(cr * 299 + cg * 587 + cb * 114) / 1000]++
        }
        val avg = (r + g + b) / 3
        val (kr, kg, kb) = listOf(avg / r, avg / g, avg / b).map { it.toFloat().coerceIn(0.8f, 1.25f) }
        val lo = percentile(lum, px.size, 0.005); val hi = percentile(lum, px.size, 0.995)
        val scale = 255f / (hi - lo).coerceAtLeast(64); val off = -lo * scale

        val m = ColorMatrix(floatArrayOf(
            kr * scale, 0f, 0f, 0f, off,
            0f, kg * scale, 0f, 0f, off,
            0f, 0f, kb * scale, 0f, off,
            0f, 0f, 0f, 1f, 0f))
        m.postConcat(ColorMatrix().apply { setSaturation(saturation) })

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 93, it) }.toByteArray()
        src.recycle(); sample.recycle(); out.recycle()
        return bytes
    }

    private fun percentile(h: IntArray, total: Int, p: Double): Int {
        var acc = 0; val target = total * p
        h.forEachIndexed { i, v -> acc += v; if (acc >= target) return i }
        return 255
    }

}
