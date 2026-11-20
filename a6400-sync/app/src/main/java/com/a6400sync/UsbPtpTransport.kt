package com.a6400sync

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP sobre USB (classe Still Image). Container: [len u32][tipo u16][código u16][transação u32][payload].
 * Tipos: 1 comando, 2 dados, 3 resposta, 4 evento.
 * O hardware fica atrás de [BulkIo] pra poder testar sem celular.
 */
class UsbPtpTransport(private val io: BulkIo) : PtpTransport {
    interface BulkIo {
        fun claim()
        /** Reset da classe Still Image + limpeza de dados velhos no cabo. */
        fun reset()
        fun write(buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int
        fun read(buf: ByteArray, timeoutMs: Int): Int
        fun readEvent(buf: ByteArray, timeoutMs: Int): Int
        fun release()
    }

    @Volatile private var closed = false
    private var tid = 0
    private val chunk = ByteArray(256 * 1024)

    override fun open() {
        io.claim()
        io.reset()
        // descarta qualquer resto de transferência anterior
        repeat(50) { if (io.read(chunk, 100) <= 0) return }
    }

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    private fun header(len: Int, type: Int, code: Int, t: Int) =
        le(12).putInt(len).putShort(type.toShort()).putShort(code.toShort()).putInt(t).array()

    private fun writeAll(b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = io.write(b, off, minOf(b.size - off, 64 * 1024), 5000)
            if (n < 0) throw PtpException("Falha ao enviar pelo cabo USB (cabo solto?)")
            off += n
        }
        if (b.size % 512 == 0) io.write(ByteArray(0), 0, 0, 1000)   // pacote vazio final (ZLP)
    }

    private fun sendCommand(code: Int, params: IntArray): Int {
        // Padrão PTP: OpenSession usa a transação 0; depois 1, 2, 3…
        val t = if (code == 0x1002) { tid = 0; 0 } else ++tid
        val body = le(params.size * 4).also { b -> params.forEach { b.putInt(it) } }.array()
        writeAll(header(12 + body.size, 1, code, t) + body)
        return t
    }

    private class Container(val type: Int, val code: Int, val payload: ByteArray)

    private fun readContainer(timeoutMs: Int = 15000): Container {
        var n: Int
        var tries = 0
        do { n = io.read(chunk, timeoutMs); tries++ } while (n == 0 && tries < 5)   // ignora ZLP
        if (n < 12) throw PtpException(if (n < 0) "Câmera não respondeu pelo cabo (tempo esgotado)" else "Resposta curta da câmera ($n bytes)")
        val h = ByteBuffer.wrap(chunk, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val len = h.int; val type = h.short.toInt() and 0xFFFF; val code = h.short.toInt() and 0xFFFF
        if (len < 12 || len > 200_000_000) throw PtpException("Pacote inválido da câmera (tamanho $len)")
        val out = ByteArray(len - 12)
        var got = minOf(n - 12, out.size)
        System.arraycopy(chunk, 12, out, 0, got)
        while (got < out.size) {
            val m = io.read(chunk, timeoutMs)
            if (m < 0) throw PtpException("Transferência interrompida ($got/${out.size} bytes)")
            val c = minOf(m, out.size - got)
            System.arraycopy(chunk, 0, out, got, c); got += c
        }
        return Container(type, code, out)
    }

    private fun readResponse(code: Int): PtpResponse {
        var d = ByteArray(0)
        while (true) {
            val c = readContainer()
            when (c.type) {
                2 -> d = c.payload
                3 -> {
                    if (c.code != 0x2001) throw PtpException("Operação 0x%04X recusada pela câmera (código 0x%04X)".format(code, c.code), c.code)
                    val b = ByteBuffer.wrap(c.payload).order(ByteOrder.LITTLE_ENDIAN)
                    return PtpResponse(c.code, buildList { while (b.remaining() >= 4) add(b.int) }, d)
                }
            }
        }
    }

    @Synchronized
    override fun operation(code: Int, vararg params: Int): PtpResponse {
        sendCommand(code, params)
        return readResponse(code)
    }

    @Synchronized
    override fun operationOut(code: Int, data: ByteArray, vararg params: Int): PtpResponse {
        val t = sendCommand(code, params)
        writeAll(header(12 + data.size, 2, code, t) + data)
        return readResponse(code)
    }

    override fun nextEvent(): PtpEvent {
        val buf = ByteArray(64)
        while (!closed) {
            val n = io.readEvent(buf, 1000)
            if (n >= 12) {
                val b = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                b.int; val type = b.short.toInt() and 0xFFFF; val code = b.short.toInt() and 0xFFFF; b.int
                if (type == 4) return PtpEvent(code, buildList { while (b.remaining() >= 4) add(b.int) })
            }
        }
        throw PtpException("fechado")
    }

    override fun close() { closed = true; runCatching { io.release() } }
}
