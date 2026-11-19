package com.a6400sync

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.net.SocketFactory

/**
 * Cliente PTP/IP mínimo (ISO 15740 sobre TCP, porta 15740).
 * É o mesmo protocolo que o Imaging Edge Mobile usa com a a6400 no modo "Ctrl c/ Smartphone".
 */
class PtpIpClient(
    private val host: String = "192.168.122.1",
    private val port: Int = 15740,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
) {
    private lateinit var cmd: Socket
    private lateinit var evt: Socket
    private var transactionId = 0

    data class Response(val code: Int, val params: List<Int>, val data: ByteArray)
    data class Event(val code: Int, val params: List<Int>)

    fun connect(name: String = "A6400Sync") {
        cmd = socketFactory.createSocket().apply { connect(InetSocketAddress(host, port), 5000); tcpNoDelay = true }
        val guid = UUID.randomUUID().let {
            ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array()
        }
        val nameBytes = (name + "\u0000").toByteArray(Charsets.UTF_16LE)
        writePacket(cmd.getOutputStream(), 1, le(16 + nameBytes.size + 4).put(guid).put(nameBytes).putInt(0x00010000).array())
        val (type, ack) = readPacket(DataInputStream(cmd.getInputStream()))
        require(type == 2) { "Câmera recusou a conexão (tipo $type)" }
        val connNumber = le(ack).int

        evt = socketFactory.createSocket().apply { connect(InetSocketAddress(host, port), 5000) }
        writePacket(evt.getOutputStream(), 3, le(4).putInt(connNumber).array())
        val (evType, _) = readPacket(DataInputStream(evt.getInputStream()))
        require(evType == 4) { "Canal de eventos recusado (tipo $evType)" }
    }

    @Synchronized
    fun operation(code: Int, vararg params: Int): Response {
        val tid = ++transactionId
        val body = le(4 + 2 + 4 + params.size * 4).putInt(1).putShort(code.toShort()).putInt(tid)
        params.forEach { body.putInt(it) }
        writePacket(cmd.getOutputStream(), 6, body.array())

        val input = DataInputStream(cmd.getInputStream())
        var data = ByteArray(0)
        var offset = 0
        while (true) {
            val (type, payload) = readPacket(input)
            when (type) {
                9 -> { data = ByteArray(le(payload).apply { position(4) }.long.toInt()); offset = 0 }   // Start Data
                10, 12 -> {                                                                              // Data / End Data
                    System.arraycopy(payload, 4, data, offset, payload.size - 4); offset += payload.size - 4
                }
                7 -> {                                                                                   // Operation Response
                    val bb = le(payload)
                    val rc = bb.short.toInt() and 0xFFFF
                    bb.int
                    val rp = buildList { while (bb.remaining() >= 4) add(bb.int) }
                    return Response(rc, rp, data)
                }
                else -> error("Pacote PTP/IP inesperado: $type")
            }
        }
    }

    /** Bloqueia até chegar um evento da câmera (ex.: foto nova). */
    fun nextEvent(): Event {
        while (true) {
            val (type, payload) = readPacket(DataInputStream(evt.getInputStream()))
            if (type == 13) { writePacket(evt.getOutputStream(), 14, ByteArray(0)); continue } // Probe -> responde
            if (type != 8) continue
            val bb = le(payload)
            val code = bb.short.toInt() and 0xFFFF
            bb.int
            return Event(code, buildList { while (bb.remaining() >= 4) add(bb.int) })
        }
    }

    fun close() { runCatching { cmd.close() }; runCatching { evt.close() } }

    private fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
        out.write(le(8 + payload.size).putInt(8 + payload.size).putInt(type).array().copyOf(8) + payload)
        out.flush()
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val header = ByteArray(8).also { input.readFully(it) }
        val bb = le(header)
        val len = bb.int
        val type = bb.int
        return type to ByteArray(len - 8).also { input.readFully(it) }
    }

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
