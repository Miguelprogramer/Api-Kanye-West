package com.a6400sync

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.SocketFactory

/**
 * Camada Sony sobre PTP/IP: handshake "SDIO" e recebimento automático das fotos.
 *
 * Quando a câmera tira uma foto no modo "Ctrl c/ Smartphone", ela dispara o evento
 * 0xC201 (ObjectAdded) com o handle especial 0xFFFFC001 -> baixamos com GetObject.
 */
class SonyCamera(socketFactory: SocketFactory, guid: java.util.UUID, private val log: (String) -> Unit = {}) {
    private val ptp = PtpIpClient(socketFactory = socketFactory, guid = guid)

    data class Photo(val fileName: String, val format: Int, val bytes: ByteArray)

    companion object {
        const val OP_OPEN_SESSION = 0x1002
        const val OP_GET_OBJECT_INFO = 0x1008
        const val OP_GET_OBJECT = 0x1009
        const val OP_SDIO_CONNECT = 0x9201
        const val OP_SDIO_GET_EXT_DEVICE_INFO = 0x9202
        const val EVT_OBJECT_ADDED = 0xC201
        const val EVT_PTP_OBJECT_ADDED = 0x4002
        const val SHOT_HANDLE = 0xFFFFC001.toInt()

        const val FMT_JPEG = 0x3801
        const val FMT_ARW = 0xB101
        /** Só aceitamos foto. Vídeo (MP4/XAVC/AVCHD/etc.) é ignorado. */
        val ACCEPTED_FORMATS = setOf(FMT_JPEG, FMT_ARW)
        val ACCEPTED_EXT = setOf("jpg", "jpeg", "arw")
    }

    fun connect() {
        log("1/3 Abrindo canal PTP/IP (porta 15740)…")
        ptp.connect()
        log("2/3 Abrindo sessão…")
        ptp.operation(OP_OPEN_SESSION, 1)
        log("3/3 Autenticando com a Sony…")
        step("SDIO 1") { ptp.operation(OP_SDIO_CONNECT, 1, 0, 0) }
        step("SDIO 2") { ptp.operation(OP_SDIO_CONNECT, 2, 0, 0) }
        step("ExtDeviceInfo") { ptp.operation(OP_SDIO_GET_EXT_DEVICE_INFO, 0xC8) }
        step("SDIO 3") { ptp.operation(OP_SDIO_CONNECT, 3, 0, 0) }
    }

    /** Passos da Sony podem variar por firmware: registra a falha e segue. */
    private fun step(name: String, block: () -> Unit) =
        try { block() } catch (e: PtpIpClient.PtpException) { log("Aviso em $name: ${e.message}") }

    /** Loop infinito: entrega cada foto nova assim que ela é tirada. */
    fun listen(onPhoto: (Photo) -> Unit, onSkipped: (String) -> Unit) {
        while (true) {
            val ev = ptp.nextEvent()
            val handle = when (ev.code) {
                EVT_OBJECT_ADDED, EVT_PTP_OBJECT_ADDED -> ev.params.firstOrNull() ?: SHOT_HANDLE
                else -> continue
            }
            val info = ptp.operation(OP_GET_OBJECT_INFO, handle).data
            val (format, name) = parseObjectInfo(info)
            val ext = name.substringAfterLast('.', "").lowercase()
            if (format !in ACCEPTED_FORMATS && ext !in ACCEPTED_EXT) {
                onSkipped(name); continue          // vídeo ou outro arquivo: não baixa
            }
            val bytes = ptp.operation(OP_GET_OBJECT, handle).data
            onPhoto(Photo(name, format, bytes))
        }
    }

    fun close() = ptp.close()

    /** ObjectInfo do PTP: formato no offset 4, nome do arquivo (string PTP) no offset 52. */
    private fun parseObjectInfo(d: ByteArray): Pair<Int, String> {
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val format = bb.getShort(4).toInt() and 0xFFFF
        val chars = d.getOrElse(52) { 0 }.toInt() and 0xFF
        val name = if (chars > 0) String(d, 53, (chars - 1) * 2, Charsets.UTF_16LE) else "DSC_${System.currentTimeMillis()}.JPG"
        return format to name
    }
}
