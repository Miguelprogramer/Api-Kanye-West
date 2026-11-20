package com.a6400sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import java.io.File
import java.util.Calendar

/**
 * Modo "Armazenamento em Massa": a câmera aparece pro Android como um pendrive.
 * O Android monta o cartão; o app lê a pasta DCIM (com permissão dada uma vez por cartão),
 * acha as fotos novas e importa.
 */
object CardImporter {
    private const val AUTHORITY = "com.android.externalstorage.documents"

    data class CardPhoto(val docId: String, val uri: Uri, val name: String, val size: Long, val modified: Long)

    // ───── Regras puras (testadas no JVM) ─────

    private val PHOTO_EXT = setOf("jpg", "jpeg", "arw")

    /** Só foto: JPEG e RAW da Sony. Vídeo (MP4, MTS, XML…) e arquivos ocultos ficam de fora. */
    fun isPhoto(name: String): Boolean =
        !name.startsWith(".") && name.substringAfterLast('.', "").lowercase() in PHOTO_EXT

    /** Identidade da foto no cartão. Tamanho + data evitam confusão quando a numeração da câmera recomeça. */
    fun key(docId: String, size: Long, modified: Long) = "$docId|$size|$modified"

    fun startOfToday(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    /** Ordem de importação: mais antigas primeiro (mesma ordem em que foram tiradas). */
    fun order(list: List<CardPhoto>) = list.sortedWith(compareBy({ it.modified }, { it.name }))

    // ───── Android ─────

    /** Cartões/pendrives montados agora (não inclui a memória interna do celular). */
    fun mountedCards(ctx: Context): List<StorageVolume> =
        ctx.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.isRemovable && !it.isPrimary && it.state == android.os.Environment.MEDIA_MOUNTED && it.uuid != null }

    fun label(v: StorageVolume, ctx: Context) = "${v.getDescription(ctx)} (${v.uuid})"

    /** Permissão salva pra esse cartão (por UUID do cartão), se ainda válida. */
    fun savedTree(ctx: Context, uuid: String): Uri? {
        val s = prefs(ctx).getString("tree_$uuid", null) ?: return null
        val uri = Uri.parse(s)
        val ok = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (ok) uri else null
    }

    fun saveTree(ctx: Context, uuid: String, tree: Uri) {
        ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(ctx).edit().putString("tree_$uuid", tree.toString()).apply()
    }

    /** Pede acesso já abrindo na pasta DCIM do cartão. */
    fun permissionIntent(v: StorageVolume): Intent =
        v.createOpenDocumentTreeIntent().apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTHORITY, "${v.uuid}:DCIM"))
        }

    /** O usuário escolheu a pasta certa (DCIM ou a raiz do cartão)? */
    fun treeLooksRight(tree: Uri, uuid: String): Boolean {
        val id = DocumentsContract.getTreeDocumentId(tree)
        return id == "$uuid:" || id == "$uuid:DCIM" || id.startsWith("$uuid:DCIM/")
    }

    /** Lista todas as fotos dentro da DCIM (ou da pasta escolhida), recursivo. */
    fun listPhotos(ctx: Context, tree: Uri): List<CardPhoto> {
        val out = mutableListOf<CardPhoto>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        // Se deram acesso à raiz do cartão, começa pela DCIM
        val startId = if (rootId.endsWith(":")) rootId + "DCIM" else rootId
        val stack = ArrayDeque<String>().apply { add(startId) }
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (stack.isNotEmpty()) {
            val parent = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (!name.startsWith(".")) stack.add(id); continue }
                    if (!isPhoto(name)) continue
                    out += CardPhoto(id, DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(3), c.getLong(4))
                }
            }
        }
        return order(out)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("card", Context.MODE_PRIVATE)
}

/** Lista do que já foi importado (arquivo de texto, uma linha por foto). */
class ImportedStore(private val file: File) {
    constructor(ctx: Context) : this(File(ctx.filesDir, "importadas.txt"))

    private val keys: MutableSet<String> by lazy { if (file.exists()) file.readLines().toMutableSet() else mutableSetOf() }

    @Synchronized fun contains(k: String) = k in keys
    @Synchronized fun add(k: String) { if (keys.add(k)) file.appendText(k + "\n") }
    @Synchronized fun addAll(ks: Collection<String>) { val new = ks.filter { keys.add(it) }; if (new.isNotEmpty()) file.appendText(new.joinToString("\n", postfix = "\n")) }
    /** Já importou alguma coisa desse cartão? */
    @Synchronized fun knowsCard(uuid: String) = keys.any { it.startsWith("$uuid:") }
}
