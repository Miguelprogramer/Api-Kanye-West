package com.a6400sync

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.net.SocketFactory
import kotlin.concurrent.thread

/**
 * Camada Sony sobre PTP/IP, seguindo a implementação do libgphoto2 (camlibs/ptp2):
 *  1. OpenSession
 *  2. SDIO_Connect(1), SDIO_Connect(2)
 *  3. SDIO_GetExtDeviceInfo(0x12C, 1) — repete até vir dado; câmeras pré-2020 (a6400) respondem versão 0xC8
 *  4. SDIO_Connect(3)
 *  5. Prioridade de controle = aplicativo
 * Fotos novas: NÃO se confia só no evento. Lê a propriedade 0xD215 (ObjectInMemory);
 * só quando ela for >= 0x8000 existe foto no handle 0xFFFFC001. Acessar esse handle
 * sem foto pendente pode travar o firmware da câmera.
 */
class SonyCamera(socketFactory: SocketFactory, guid: UUID, host: String = "192.168.122.1", private val log: (String) -> Unit = {}) {
    private val ptp = PtpIpClient(host = host, socketFactory = socketFactory, guid = guid)
    @Volatile private var running = true
    private val wake = Object()
    var protocolVersion = 0xC8; private set

    data class Photo(val fileName: String, val format: Int, val bytes: ByteArray)

    companion object {
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

        const val FMT_JPEG = 0x3801
        const val FMT_ARW = 0xB101
        val ACCEPTED_FORMATS = setOf(FMT_JPEG, FMT_ARW)
        val ACCEPTED_EXT = setOf("jpg", "jpeg", "arw")
    }

    fun connect() {
        log("1/4 Abrindo canal PTP/IP com a câmera…")
        ptp.connect()
        log("2/4 Abrindo sessão…")
        ptp.operation(OP_OPEN_SESSION, 1)
        log("3/4 Autenticando com a Sony…")
        ptp.operation(OP_SDIO_CONNECT, 1, 0, 0)
        ptp.operation(OP_SDIO_CONNECT, 2, 0, 0)
        var info = ByteArray(0)
        for (i in 1..20) {
            info = runCatching { ptp.operation(OP_SDIO_GET_EXT_DEVICE_INFO, 0x12C, 1).data }
                .recoverCatching { ptp.operation(OP_SDIO_GET_EXT_DEVICE_INFO, 0xC8).data }
                .getOrDefault(ByteArray(0))
            if (info.size >= 2) break
            Thread.sleep(100)
        }
        if (info.size >= 2) protocolVersion = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        log("Protocolo Sony: 0x%X".format(protocolVersion))
        ptp.operation(OP_SDIO_CONNECT, 3, 0, 0)
        log("4/4 Assumindo controle…")
        runCatching { ptp.operationOut(OP_SET_EXT_DEVICE_PROP, byteArrayOf(1), PROP_PRIORITY_KEY) }
            .onFailure { log("Aviso: prioridade não definida (${it.message})") }
    }

    /** Loop: entrega cada foto nova assim que ela aparece na memória da câmera. */
    fun listen(onPhoto: (Photo) -> Unit, onSkipped: (String) -> Unit) {
        // Canal de eventos só acorda o loop mais cedo; a fonte da verdade é a 0xD215.
        thread(isDaemon = true, name = "ptp-events") {
            try { while (running) { ptp.nextEvent(); synchronized(wake) { wake.notifyAll() } } } catch (_: Exception) {}
        }
        var lastLogged = -1
        while (running) {
            val pending = objectInMemory()
            if (pending != lastLogged && pending >= 0) { lastLogged = pending }
            if (pending >= 0x8000) {
                val info = ptp.operation(OP_GET_OBJECT_INFO, SHOT_HANDLE).data
                val (format, name) = parseObjectInfo(info)
                // Sempre baixa pra esvaziar a memória da câmera; só repassa se for foto.
                val bytes = ptp.operation(OP_GET_OBJECT, SHOT_HANDLE).data
                val ext = name.substringAfterLast('.', "").lowercase()
                if (format in ACCEPTED_FORMATS || ext in ACCEPTED_EXT) onPhoto(Photo(name, format, bytes)) else onSkipped(name)
                continue
            }
            synchronized(wake) { wake.wait(400) }
        }
    }

    /** Lê 0xD215 em GetAllExtDevicePropInfo. Retorna -1 se não achar. */
    fun objectInMemory(): Int {
        val d = ptp.operation(OP_GET_ALL_EXT_DEVICE_PROP_INFO).data
        return SonyProps.find(d, PROP_OBJECT_IN_MEMORY, protocolVersion)?.toInt() ?: -1
    }

    fun close() { running = false; ptp.close() }

    /** ObjectInfo do PTP: formato no offset 4, nome do arquivo (string PTP) no offset 52. */
    private fun parseObjectInfo(d: ByteArray): Pair<Int, String> {
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val format = if (d.size >= 6) bb.getShort(4).toInt() and 0xFFFF else 0
        val chars = d.getOrElse(52) { 0 }.toInt() and 0xFF
        val name = if (chars > 1 && d.size >= 53 + (chars - 1) * 2) String(d, 53, (chars - 1) * 2, Charsets.UTF_16LE)
                   else "DSC_${System.currentTimeMillis()}.JPG"
        return format to name
    }
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
            b.get(); b.get()                       // getset, isEnabled
            skipValue(b, type)                     // valor padrão
            val current = readValue(b, type)
            if (code == wanted) return current
            when (b.get().toInt()) {               // form flag
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
