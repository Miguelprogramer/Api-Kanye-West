package com.a6400sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.net.SocketFactory
import kotlin.concurrent.thread

private fun ByteBuffer.s(v: Int): ByteBuffer = putShort(v.toShort())
private fun ByteBuffer.s(v: Short): ByteBuffer = putShort(v)

/**
 * Câmera a6400 simulada (PTP/IP + extensões Sony), usada pra testar o app inteiro sem a câmera.
 * Regras imitadas do firmware real (libgphoto2):
 *  - 0xFFFFC001 só pode ser lido quando 0xD215 >= 0x8000; senão a "câmera trava" (teste falha).
 *  - Dados chegam em vários pacotes; há probes no canal de eventos.
 */
class FakeA6400(private val shots: List<Pair<String, Int>>, private val failInit: Int? = null) {
    val server = ServerSocket(0)
    val ops = mutableListOf<String>()
    @Volatile var crashed = false
    val payload = ByteArray(300_000) { (it % 251).toByte() }
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Int>>()

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun send(o: OutputStream, type: Int, p: ByteArray) = synchronized(o) { o.write(le(8).putInt(8 + p.size).putInt(type).array() + p); o.flush() }
    private fun read(i: DataInputStream): Pair<Int, ByteArray> {
        val h = ByteArray(8).also { i.readFully(it) }; val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val len = b.int; val t = b.int; return t to ByteArray(len - 8).also { i.readFully(it) }
    }

    /** Dataset 0x9209 com algumas propriedades de verdade + 0xD215. */
    private fun props(): ByteArray {
        val pending = if (queue.isEmpty()) 0 else 0x8000 + queue.size
        val b = le(512)
        b.putInt(3).putInt(0)
        // 0x5007 F-number UINT16, enum com 2 valores (formato v2)
        b.s(0x5007).s(4).put(1).put(1).s(0).s(280).put(2).s(2).s(280).s(400)
        // 0xD20D string (nome) — testa tipo string
        b.s(0xD20D.toShort()).s(0xFFFF.toShort()).put(0).put(1).put(0)
        val s = "a6400\u0000".toByteArray(Charsets.UTF_16LE); b.put(6).put(s).put(0)
        // 0xD215 ObjectInMemory UINT16, range
        b.s(0xD215.toShort()).s(4).put(0).put(1).s(0).s(pending.toShort()).put(1).s(0).s(0xFFFF.toShort()).s(1)
        return b.array().copyOf(b.position())
    }

    fun start() = thread(isDaemon = true) {
        val cmd = server.accept(); val ci = DataInputStream(cmd.getInputStream()); val co = cmd.getOutputStream()
        val (t, initReq) = read(ci); check(t == 1)
        check(ByteBuffer.wrap(initReq, initReq.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int == 0x00010000)
        if (failInit != null) { send(co, 5, le(4).putInt(failInit).array()); return@thread }
        send(co, 2, le(4 + 16 + 4 + 4).putInt(7).put(ByteArray(16)).s(0).s(0).putInt(0x00010000).array())
        val evt = server.accept(); val ei = DataInputStream(evt.getInputStream()); val eo = evt.getOutputStream()
        val (et, ep) = read(ei); check(et == 3 && ByteBuffer.wrap(ep).order(ByteOrder.LITTLE_ENDIAN).int == 7)
        send(eo, 4, ByteArray(0))
        while (true) {
            val (ot, op) = runCatching { read(ci) }.getOrNull() ?: break
            check(ot == 6) { "esperava OperationRequest, veio $ot" }
            val b = ByteBuffer.wrap(op).order(ByteOrder.LITTLE_ENDIAN); val phase = b.int
            val code = b.short.toInt() and 0xFFFF; val tid = b.int
            val params = buildList { while (b.remaining() >= 4) add(b.int) }
            ops += "%04X".format(code)
            if (phase == 2) { read(ci); read(ci) }   // Start Data + End Data vindos do app
            fun data(d: ByteArray) {
                send(co, 9, le(12).putInt(tid).putLong(d.size.toLong()).array())
                val half = d.size / 2
                send(co, 10, le(4).putInt(tid).array() + d.copyOfRange(0, half))
                send(co, 12, le(4).putInt(tid).array() + d.copyOfRange(half, d.size))
            }
            var rc = 0x2001
            when (code) {
                0x9201 -> data(ByteArray(0))
                0x9202 -> if (params.firstOrNull() == 0x12C) data(le(6).s(0xC8).s(0x1008).s(0x9209).array()) else data(ByteArray(2))
                0x9209 -> data(props())
                0x1008, 0x1009 -> {
                    if (queue.isEmpty()) { crashed = true; rc = 0x2009 }
                    else if (code == 0x1008) {
                        val (name, fmt) = queue.peek()
                        val n = (name + "\u0000").toByteArray(Charsets.UTF_16LE)
                        data(le(52).putInt(0x10001).s(fmt.toShort()).array() + byteArrayOf((name.length + 1).toByte()) + n + ByteArray(8))
                    } else { queue.poll(); data(payload) }
                }
            }
            send(co, 7, le(6).s(rc.toShort()).putInt(tid).array())
            if (code == 0x9205) thread(isDaemon = true) {
                send(eo, 13, ByteArray(0))
                for (f in shots) {
                    Thread.sleep(200); queue.add(f)
                    send(eo, 8, le(10).s(0xC201.toShort()).putInt(0).putInt(0xFFFFC001.toInt()).array())
                }
            }
        }
    }
}

class FakeCameraTest {
    private fun factory(port: Int) = object : SocketFactory() {
        override fun createSocket() = object : Socket() {
            override fun connect(a: SocketAddress, t: Int) = super.connect(InetSocketAddress("127.0.0.1", port), t)
        }
        override fun createSocket(h: String?, p: Int) = Socket("127.0.0.1", port)
        override fun createSocket(h: String?, p: Int, l: java.net.InetAddress?, lp: Int) = Socket("127.0.0.1", port)
        override fun createSocket(h: java.net.InetAddress?, p: Int) = Socket("127.0.0.1", port)
        override fun createSocket(h: java.net.InetAddress?, p: Int, l: java.net.InetAddress?, lp: Int) = Socket("127.0.0.1", port)
    }

    @Test fun fluxoCompleto_recebeFotos_ignoraVideo_naoTravaCamera() {
        val cam = FakeA6400(listOf("DSC00001.JPG" to 0x3801, "C0001.MP4" to 0xB982, "DSC00002.ARW" to 0xB101)); cam.start()
        val got = mutableListOf<SonyCamera.Photo>(); val skipped = mutableListOf<String>(); val log = mutableListOf<String>()
        val sc = SonyCamera(factory(cam.server.localPort), UUID.randomUUID()) { log += it }
        sc.connect()
        assertEquals(0xC8, sc.protocolVersion)
        thread(isDaemon = true) { runCatching { sc.listen({ got += it }, { skipped += it }) } }
        val end = System.currentTimeMillis() + 8000
        while (got.size + skipped.size < 3 && System.currentTimeMillis() < end) Thread.sleep(50)
        sc.close()
        println("LOG: $log\nOPS: ${cam.ops}")
        assertEquals(listOf("DSC00001.JPG", "DSC00002.ARW"), got.map { it.fileName })
        assertEquals(listOf("C0001.MP4"), skipped)
        assertTrue("bytes íntegros", got.all { it.bytes.contentEquals(cam.payload) })
        assertTrue("acessou 0xFFFFC001 sem foto pendente (travaria a câmera)", !cam.crashed)
        assertEquals(listOf("1002", "9201", "9201", "9202", "9201", "9205"), cam.ops.take(6))
    }

    @Test fun initFail_mostraMotivo() {
        val cam = FakeA6400(emptyList(), failInit = 0x2019); cam.start()
        val e = runCatching { SonyCamera(factory(cam.server.localPort), UUID.randomUUID()).connect() }.exceptionOrNull()
        println("ERRO: ${e?.message}")
        assertTrue(e?.message.orEmpty(), e?.message?.contains("0x00002019") == true)
    }

    @Test fun parserDePropriedades_semFotoRetornaZero() {
        val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(1).putInt(0).s(0xD215.toShort()).s(4).put(0).put(1).s(0).s(0).put(0)
        assertEquals(0L, SonyProps.find(b.array(), 0xD215, 0xC8))
    }
}
