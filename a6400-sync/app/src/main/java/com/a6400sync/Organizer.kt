package com.a6400sync

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Organiza na galeria:
 *   Pictures/A6400/2026-11-19/Originais      (todas, intactas)
 *   Pictures/A6400/2026-11-19/Selecionadas   (aprovadas + editadas)
 *   Pictures/A6400/2026-11-19/Descartadas    (cópia das reprovadas; nada é apagado)
 */
class Organizer(private val ctx: Context) {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun save(folder: String, fileName: String, bytes: ByteArray, mime: String = "image/jpeg") {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/A6400/$day/$folder")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
