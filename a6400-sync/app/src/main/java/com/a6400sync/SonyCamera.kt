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
        ptp.operation(OP_SDIO_CONNECT, 3, 0, 0)
        log("5/5 Assumindo controle…")
        runCatching { ptp.operationOut(OP_SET_EXT_DEVICE_PROP, byteArrayOf(1), PROP_PRIORITY_KEY) }
            .onFailure { log("Aviso: prioridade não definida (${it.message})") }
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
            val pending = runCatching { objectInMemory() }.getOrElse { -1 }
            val ready = when {
                pending >= 0x8000 -> true
                pending < 0 && eventsSeen.get() > 0 -> {           // plano B: confia no evento
                    if (!propWarned) { propWarned = true; log("Aviso: usando eventos da câmera pra detectar fotos") }
                    eventsSeen.decrementAndGet(); Thread.sleep(300); true
                }
                else -> false
            }
            if (ready) {
                val info = ptp.operation(OP_GET_OBJECT_INFO, SHOT_HANDLE).data
                val (format, name) = parseObjectInfo(info)
                val bytes = ptp.operation(OP_GET_OBJECT, SHOT_HANDLE).data   // sempre baixa pra esvaziar a memória
                val ext = name.substringAfterLast('.', "").lowercase()
                if (format in ACCEPTED_FORMATS || ext in ACCEPTED_EXT) onPhoto(Photo(name, format, bytes)) else onSkipped(name)
                continue
            }
            synchronized(wake) { wake.wait(400) }
        }
    }

    fun objectInMemory(): Int {
        val d = ptp.operation(OP_GET_ALL_EXT_DEVICE_PROP_INFO).data
        return SonyProps.find(d, PROP_OBJECT_IN_MEMORY, protocolVersion)?.toInt() ?: -1
    }

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
    fun find(d: ByteArray, wanted: Int, version: Int): Long? {
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (d.size < 8) return null
        val count = b.int; b.int
        repeat(count) {
            if (b.remaining() < 6) return null
            val code = b.short.toInt() and 0xFFFF
            val type = b.short.toInt() and 0xFFFF
            b.get(); b.get()
            skipValue(b, type)
            val current = readValue(b, type)
            if (code == wanted) return current
            when (b.get().toInt()) {
                1 -> repeat(3) { skipValue(b, type) }
                2 -> {
                    repeat(b.short.toInt() and 0xFFFF) { skipValue(b, type) }
                    if (version >= 0x12C) repeat(b.short.toInt() and 0xFFFF) { skipValue(b, type) }
                }
            }
        }
        return null
    }

    private fun size(type: Int) = when (type) { 1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; 7, 8 -> 8; 9, 10 -> 16; else -> -1 }

    private fun readValue(b: ByteBuffer, type: Int): Long? = when (size(type)) {
        1 -> (b.get().toLong() and 0xFF)
        2 -> (b.short.toLong() and 0xFFFF)
        4 -> (b.int.toLong() and 0xFFFFFFFFL)
        8 -> b.long
        else -> { skipValue(b, type); null }
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
