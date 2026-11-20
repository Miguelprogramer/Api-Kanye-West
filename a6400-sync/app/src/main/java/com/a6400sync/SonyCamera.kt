package com.a6400sync

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Protocolo Sony "PC Remoto", seguindo o libgphoto2 (camlibs/ptp2):
 *  OpenSession → GetDeviceInfo → SDIO_Connect(1,2) → SDIO_GetExtDeviceInfo → SDIO_Connect(3) → prioridade.
 * Foto nova: lê a propriedade 0xD215; quando >= 0x8000 há foto no handle 0xFFFFC001.
 * Se essa propriedade não puder ser lida, usa o evento 0xC201 como plano B.
 */
class SonyCamera(private val ptp: PtpTransport, private val log: (String) -> Unit = {}) {
    @Volatile private var running = true
    private val wake = Object()
    private val eventsSeen = AtomicInteger(0)
    var protocolVersion = 0xC8; private set
    var model: String? = null; private set

    data class Photo(val fileName: String, val format: Int, val bytes: ByteArray)
    data class DeviceInfo(val manufacturer: String, val model: String, val ops: Set<Int>)

    companion object {
        const val OP_GET_DEVICE_INFO = 0x1001
        const val OP_OPEN_SESSION = 0x1002
        const val OP_GET_OBJECT_INFO = 0x1008
        const val OP_GET_OBJECT = 0x1009
        const val OP_SDIO_CONNECT = 0x9201
        const val OP_SDIO_GET_EXT_DEVICE_INFO = 0x9202
        const val OP_SET_EXT_DEVICE_PROP = 0x9205
        const val OP_GET_ALL_EXT_DEVICE_PROP_INFO = 0x9209
        const val PROP_OBJECT_IN_MEMORY = 0xD215
        const val PROP_PRIORITY_KEY = 0xD25A
        const val SHOT_HANDLE = 0xFFFFC001.toInt()
        const val RC_SESSION_ALREADY_OPEN = 0x201E
        const val OP_CONTROL_DEVICE = 0x9207
        const val LIVE_HANDLE = 0xFFFFC002.toInt()
        const val BTN_S1 = 0xD2C1          // meio-clique (foco)
        const val BTN_S2 = 0xD2C2          // disparo
        const val PROP_FOCUS_INDICATOR = 0xD213
        const val PROP_FOCUS_MODE = 0x500A

        /** Live view: bytes 0–3 = posição do JPEG; senão procura FF D8 … FF D9. */
        fun extractJpeg(d: ByteArray): ByteArray? {
            if (d.size < 4) return null
            val off = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).int
            var start = if (off in 0..d.size - 2 && d[off] == 0xFF.toByte() && d[off + 1] == 0xD8.toByte()) off else -1
            if (start < 0) start = (0 until d.size - 1).firstOrNull { d[it] == 0xFF.toByte() && d[it + 1] == 0xD8.toByte() } ?: return null
            var end = d.size
            for (i in d.size - 2 downTo start + 2) if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD9.toByte()) { end = i + 2; break }
            return d.copyOfRange(start, end)
        }

        const val FMT_JPEG = 0x3801
        const val FMT_ARW = 0xB101
        val ACCEPTED_FORMATS = setOf(FMT_JPEG, FMT_ARW)
        val ACCEPTED_EXT = setOf("jpg", "jpeg", "arw")
        const val PC_REMOTE_HELP = "Na câmera: MENU → Configuração (maleta) → Conexão USB → PC Remoto. Depois desplugue e plugue o cabo."
    }

    fun connect() {
        log("1/5 Abrindo o canal USB…")
        ptp.open()
        log("2/5 Abrindo sessão…")
        try { ptp.operation(OP_OPEN_SESSION, 1) }
        catch (e: PtpException) {
            if (e.code == RC_SESSION_ALREADY_OPEN) log("Sessão anterior ainda aberta — reaproveitando") else throw e
        }
        log("3/5 Identificando a câmera…")
        val info = runCatching { parseDeviceInfo(ptp.operation(OP_GET_DEVICE_INFO).data) }.getOrNull()
        if (info != null) {
            model = info.model
            log("Câmera: ${info.manufacturer} ${info.model}")
            if (OP_SDIO_CONNECT !in info.ops)
                throw PtpException("A câmera está no modo MTP/arquivos, não no PC Remoto. $PC_REMOTE_HELP")
        }
        log("4/5 Autenticando com a Sony…")
        ptp.operation(OP_SDIO_CONNECT, 1, 0, 0)
        ptp.operation(OP_SDIO_CONNECT, 2, 0, 0)
        var ext = ByteArray(0)
        for (i in 1..20) {
            ext = runCatching { ptp.operation(OP_SDIO_GET_EXT_DEVICE_INFO, 0x12C, 1).data }
                .recoverCatching { ptp.operation(OP_SDIO_GET_EXT_DEVICE_INFO, 0xC8).data }
                .getOrDefault(ByteArray(0))
            if (ext.size >= 2) break
            Thread.sleep(100)
        }
        if (ext.size >= 2) protocolVersion = ByteBuffer.wrap(ext).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        log("Protocolo Sony: 0x%X".format(protocolVersion))
        Thread.sleep(500)
        ptp.operation(OP_SDIO_CONNECT, 3, 0, 0)
        log("5/5 Assumindo controle…")
        runCatching { ptp.operationOut(OP_SET_EXT_DEVICE_PROP, byteArrayOf(1), PROP_PRIORITY_KEY) }
            .onFailure { log("Aviso: prioridade não definida (${it.message})") }
    }

    /** Últimas propriedades lidas da câmera (bateria, ISO, velocidade…). */
    @Volatile var lastProps: Map<Int, SonyProps.Prop> = emptyMap(); private set
    var onProps: ((Map<Int, SonyProps.Prop>) -> Unit)? = null

    /** Lê todas as propriedades (0x9209) e avisa a tela. */
    fun refreshProps(): Map<Int, SonyProps.Prop> {
        val p = SonyProps.parseAll(ptp.operation(OP_GET_ALL_EXT_DEVICE_PROP_INFO).data, protocolVersion)
        if (p.isNotEmpty()) { lastProps = p; onProps?.invoke(p) }
        return p
    }

    /** Loop: entrega cada foto nova assim que ela aparece na memória da câmera. */
    fun listen(onPhoto: (Photo) -> Unit, onSkipped: (String) -> Unit) {
        thread(isDaemon = true, name = "ptp-events") {
            try {
                while (running) {
                    val ev = ptp.nextEvent()
                    if (ev.code == 0xC201 || ev.code == 0x4002) eventsSeen.incrementAndGet()
                    synchronized(wake) { wake.notifyAll() }
                }
            } catch (_: Exception) {}
        }
        var propWarned = false
        while (running) {
            val props = runCatching { refreshProps() }.getOrElse { emptyMap() }
            val pending = props[PROP_OBJECT_IN_MEMORY]?.value?.toInt() ?: -1
            val ready = when {
                // 0x8000 | quantidade. Valores estranhos (ex.: 0xFFFB, visto numa a6400) são ignorados.
                pending in 0x8001..0x80FF -> true
                pending < 0 && eventsSeen.get() > 0 -> {           // plano B: confia no evento
                    if (!propWarned) { propWarned = true; log("Aviso: usando eventos da câmera pra detectar fotos") }
                    eventsSeen.decrementAndGet(); Thread.sleep(300); true
                }
                else -> false
            }
            if (ready) {
                downloadOne(onPhoto, onSkipped)
                Thread.sleep(300)   // a 0xD215 demora ~0,2 s pra atualizar; evita pedir foto que não existe
                continue
            }
            synchronized(wake) { wake.wait(400) }
        }
    }

    private fun downloadOne(onPhoto: (Photo) -> Unit, onSkipped: (String) -> Unit) {
        val info = ptp.operation(OP_GET_OBJECT_INFO, SHOT_HANDLE).data
        if (info.size < 53) { log("Aviso: câmera anunciou foto mas não mandou os dados (ignorado)"); return }
        val (format, name) = parseObjectInfo(info)
        val bytes = ptp.operation(OP_GET_OBJECT, SHOT_HANDLE).data   // sempre baixa pra esvaziar a memória
        val ext = name.substringAfterLast('.', "").lowercase()
        if (format in ACCEPTED_FORMATS || ext in ACCEPTED_EXT) onPhoto(Photo(name, format, bytes)) else onSkipped(name)
    }

    fun objectInMemory(): Int = refreshProps()[PROP_OBJECT_IN_MEMORY]?.value?.toInt() ?: -1

    // ───────────── Controle remoto (PC Remoto) ─────────────

    private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    /** Botão virtual da câmera: 0x9207, UINT16, 2 = aperta, 1 = solta. */
    fun button(code: Int, pressed: Boolean) { ptp.operationOut(OP_CONTROL_DEVICE, le16(if (pressed) 2 else 1), code) }

    /** Ajuste por passos (protocolo 0xC8: velocidade, abertura, ISO, compensação). */
    fun step(code: Int, delta: Int) {
        try { ptp.operationOut(OP_CONTROL_DEVICE, byteArrayOf(delta.toByte()), code) }          // libgphoto2: 1 byte
        catch (e: PtpException) { ptp.operationOut(OP_CONTROL_DEVICE, le16(delta), code) }       // INDIGO: 2 bytes
    }

    /** Valor exato (0x9205), no tamanho do tipo da propriedade. */
    fun setValue(code: Int, value: Long) {
        val type = lastProps[code]?.type ?: 4
        val size = when (type) { 1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; else -> 8 }
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        when (size) { 1 -> b.put(value.toByte()); 2 -> b.putShort(value.toShort()); 4 -> b.putInt(value.toInt()); else -> b.putLong(value) }
        ptp.operationOut(OP_SET_EXT_DEVICE_PROP, b.array(), code)
    }

    /** Espera a propriedade mudar (a câmera aplica com atraso). */
    fun waitChange(code: Int, old: Long?, timeoutMs: Long = 3000): Long? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val v = runCatching { refreshProps()[code]?.value }.getOrNull()
            if (v != old) return v
            Thread.sleep(200)
        }
        return old
    }

    /** Foca (meio-clique). Retorna true se achou foco. */
    fun autofocus(): Boolean {
        button(BTN_S1, true)
        val ok = waitFocus(1500)
        button(BTN_S1, false)
        return ok
    }

    private fun waitFocus(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            when (runCatching { refreshProps()[PROP_FOCUS_INDICATOR]?.value?.toInt() }.getOrNull()) { 2, 6 -> return true; 3 -> return false }
            Thread.sleep(100)
        }
        return false
    }

    /** Disparo remoto (sequência do libgphoto2). A foto chega pelo loop normal de download. */
    fun capture() {
        val manual = lastProps[PROP_FOCUS_MODE]?.value == 1L
        button(BTN_S1, true)
        button(BTN_S2, true)
        if (!manual) waitFocus(1000) else Thread.sleep(100)
        button(BTN_S2, false)
        button(BTN_S1, false)
        synchronized(wake) { wake.notifyAll() }
    }

    /** Um quadro da visualização ao vivo (JPEG) ou null se a câmera ainda não liberou. */
    fun liveViewJpeg(): ByteArray? {
        try {
            ptp.operation(OP_GET_OBJECT_INFO, LIVE_HANDLE)
            return extractJpeg(ptp.operation(OP_GET_OBJECT, LIVE_HANDLE).data)
        } catch (e: PtpException) {
            if (e.code == 0x2009 || e.code == 0x200F || e.code == 0x2019) return null   // ainda não pronto / ocupado
            throw e
        }
    }

    @Volatile private var liveOn = false
    fun startLive(onFrame: (ByteArray) -> Unit) {
        if (liveOn) return
        liveOn = true
        thread(isDaemon = true, name = "live-view") {
            var errors = 0
            while (liveOn && running) {
                val f = runCatching { liveViewJpeg() }.onFailure { errors++ }.getOrNull()
                if (f != null) { errors = 0; onFrame(f) }
                if (errors > 20) { log("Ao vivo parou: câmera não mandou imagem"); liveOn = false; break }
                Thread.sleep(if (f == null) 150 else 60)
            }
        }
    }
    fun stopLive() { liveOn = false }
    val isLive get() = liveOn

    fun close() { running = false; synchronized(wake) { wake.notifyAll() }; ptp.close() }

    private fun parseObjectInfo(d: ByteArray): Pair<Int, String> {
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val format = if (d.size >= 6) bb.getShort(4).toInt() and 0xFFFF else 0
        val chars = d.getOrElse(52) { 0 }.toInt() and 0xFF
        val name = if (chars > 1 && d.size >= 53 + (chars - 1) * 2) String(d, 53, (chars - 1) * 2, Charsets.UTF_16LE)
                   else "DSC_${System.currentTimeMillis()}.JPG"
        return format to name
    }
}

/** DeviceInfo do PTP: só o que precisamos (fabricante, modelo, operações suportadas). */
fun parseDeviceInfo(d: ByteArray): SonyCamera.DeviceInfo {
    val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
    fun str(): String { val n = b.get().toInt() and 0xFF; if (n == 0) return ""; val s = ByteArray(n * 2).also { b.get(it) }; return String(s, Charsets.UTF_16LE).trimEnd('\u0000') }
    fun arr(): Set<Int> { val n = b.int; return (0 until n).map { b.short.toInt() and 0xFFFF }.toSet() }
    b.short; b.int; b.short; str(); b.short
    val ops = arr(); arr(); arr(); arr(); arr()
    val manu = str(); val model = str()
    return SonyCamera.DeviceInfo(manu, model, ops)
}

/** Parser do dataset de propriedades Sony (formato do libgphoto2: ptp_unpack_Sony_DPD). */
object SonyProps {
    data class Prop(
        val code: Int, val type: Int, val getset: Int, val enabled: Int,
        val value: Long?, val text: String?,
        val options: List<Long>, val range: Triple<Long, Long, Long>?,
    )

    /** Lê TODAS as propriedades. Para no primeiro erro e devolve o que conseguiu. */
    fun parseAll(d: ByteArray, version: Int): Map<Int, Prop> {
        val out = LinkedHashMap<Int, Prop>()
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (d.size < 8) return out
        val count = b.int; b.int
        try {
            repeat(count) {
                if (b.remaining() < 6) return out
                val code = b.short.toInt() and 0xFFFF
                val type = b.short.toInt() and 0xFFFF
                val getset = b.get().toInt(); val enabled = b.get().toInt()
                skipValue(b, type)
                var text: String? = null
                val value = if (type == 0xFFFF) { text = readString(b); null } else readValue(b, type)
                var options = emptyList<Long>(); var range: Triple<Long, Long, Long>? = null
                when (b.get().toInt()) {
                    1 -> range = Triple(readValue(b, type) ?: 0, readValue(b, type) ?: 0, readValue(b, type) ?: 0)
                    2 -> {
                        val n = b.short.toInt() and 0xFFFF
                        val first = (0 until n).map { readValue(b, type) ?: 0 }
                        options = first
                        if (b.remaining() >= 2 && (b.getShort(b.position()).toInt() and 0xFFFF) < 0x200) {
                            val m = b.short.toInt() and 0xFFFF
                            val second = (0 until m).map { readValue(b, type) ?: 0 }
                            if (second.isNotEmpty()) options = second                     // 2ª lista = valores aceitos agora
                        }
                    }
                }
                out[code] = Prop(code, type, getset, enabled, value, text, options, range)
            }
        } catch (_: Exception) {}
        return out
    }

    fun find(d: ByteArray, wanted: Int, version: Int): Long? = parseAll(d, version)[wanted]?.value

    private fun size(type: Int) = when (type) { 1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; 7, 8 -> 8; 9, 10 -> 16; else -> -1 }

    /** Valores com sinal (INT8/16/32) viram negativos corretamente. */
    private fun readValue(b: ByteBuffer, type: Int): Long? = when (type) {
        1 -> b.get().toLong()
        2 -> (b.get().toLong() and 0xFF)
        3 -> b.short.toLong()
        4 -> (b.short.toLong() and 0xFFFF)
        5 -> b.int.toLong()
        6 -> (b.int.toLong() and 0xFFFFFFFFL)
        7, 8 -> b.long
        else -> { skipValue(b, type); null }
    }

    private fun readString(b: ByteBuffer): String {
        val n = b.get().toInt() and 0xFF
        if (n == 0) return ""
        val bytes = ByteArray(n * 2).also { b.get(it) }
        return String(bytes, Charsets.UTF_16LE).trimEnd('\u0000')
    }

    private fun skipValue(b: ByteBuffer, type: Int) {
        val s = size(type)
        when {
            s > 0 -> b.position(b.position() + s)
            type == 0xFFFF -> { val n = b.get().toInt() and 0xFF; b.position(b.position() + n * 2) }
            type and 0x4000 != 0 -> { val n = b.int; b.position(b.position() + n * size(type and 0xFF).coerceAtLeast(1)) }
            else -> throw IllegalStateException("tipo desconhecido 0x%X".format(type))
        }
    }
}
