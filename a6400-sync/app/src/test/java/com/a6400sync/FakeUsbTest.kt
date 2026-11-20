package com.a6400sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** a6400 simulada no modo "PC Remoto" pelo cabo USB (PTP sobre USB). */
class FakeUsbA6400(private val shots: List<Pair<String, Int>>) : UsbPtpTransport.BulkIo {
    val ops = mutableListOf<String>()
    @Volatile var crashed = false
    val payload = ByteArray(700_000) { (it * 7 % 253).toByte() }
    private val inbound = LinkedBlockingQueue<ByteArray>()
    private val events = LinkedBlockingQueue<ByteArray>()
    private val memory = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Int>>()
    private var pendingOut: Pair<Int, Int>? = null

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun container(type: Int, code: Int, tid: Int, body: ByteArray) =
        le(12).putInt(12 + body.size).putShort(type.toShort()).putShort(code.toShort()).putInt(tid).array() + body

    /** Divide como o USB real: leituras de no máximo 16 KB. */
    private fun push(b: ByteArray) { var o = 0; while (o < b.size) { val e = minOf(o + 16384, b.size); inbound.add(b.copyOfRange(o, e)); o = e } }

    private fun props(): ByteArray {
        val pending = if (memory.isEmpty()) 0 else 0x8000 + memory.size
        val b = le(64); b.putInt(1).putInt(0)
        b.putShort(0xD215.toShort()).putShort(4).put(0).put(1).putShort(0).putShort(pending.toShort()).put(0)
        return b.array().copyOf(b.position())
    }

    override fun claim() {}
    override fun release() {}
    override fun readEvent(buf: ByteArray, timeoutMs: Int): Int {
        val e = events.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return -1
        System.arraycopy(e, 0, buf, 0, e.size); return e.size
    }
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val c = inbound.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return -1
        System.arraycopy(c, 0, buf, 0, c.size); return c.size
    }

    override fun write(buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        if (len == 0) return 0
        val b = ByteBuffer.wrap(buf, off, len).order(ByteOrder.LITTLE_ENDIAN)
        b.int; val type = b.short.toInt(); val code = b.short.toInt() and 0xFFFF; val tid = b.int
        if (type == 2) { pendingOut = null; respond(code, tid); return len }
        ops += "%04X".format(code)
        if (code == 0x9205) { pendingOut = code to tid; return len }   // espera a fase de dados
        respond(code, tid); return len
    }

    private fun respond(code: Int, tid: Int) {
        var rc = 0x2001
        when (code) {
            0x9201 -> push(container(2, code, tid, ByteArray(0)))
            0x9202 -> push(container(2, code, tid, le(2).putShort(0xC8).array()))
            0x9209 -> push(container(2, code, tid, props()))
            0x1008, 0x1009 -> {
                if (memory.isEmpty()) { crashed = true; rc = 0x2009 }
                else if (code == 0x1008) {
                    val (name, fmt) = memory.peek()
                    val n = (name + "\u0000").toByteArray(Charsets.UTF_16LE)
                    push(container(2, code, tid, le(52).putInt(0x10001).putShort(fmt.toShort()).array() + byteArrayOf((name.length + 1).toByte()) + n))
                } else { memory.poll(); push(container(2, code, tid, payload)) }
            }
        }
        push(container(3, rc, tid, ByteArray(0)))
        if (code == 0x9205) thread(isDaemon = true) {
            for (s in shots) {
                Thread.sleep(200); memory.add(s)
                events.add(container(4, 0xC201, 0, le(4).putInt(0xFFFFC001.toInt()).array()))
            }
        }
    }
}

class FakeUsbTest {
    @Test fun cabo_fluxoCompleto() {
        val cam = FakeUsbA6400(listOf("DSC00010.JPG" to 0x3801, "C0002.MP4" to 0xB982, "DSC00011.ARW" to 0xB101))
        val got = mutableListOf<SonyCamera.Photo>(); val skipped = mutableListOf<String>(); val log = mutableListOf<String>()
        val sc = SonyCamera(UsbPtpTransport(cam)) { log += it }
        sc.connect()
        thread(isDaemon = true) { runCatching { sc.listen({ got += it }, { skipped += it }) } }
        val end = System.currentTimeMillis() + 8000
        while (got.size + skipped.size < 3 && System.currentTimeMillis() < end) Thread.sleep(50)
        sc.close()
        println("USB LOG: $log\nUSB OPS: ${cam.ops}")
        assertEquals(listOf("DSC00010.JPG", "DSC00011.ARW"), got.map { it.fileName })
        assertEquals(listOf("C0002.MP4"), skipped)
        assertTrue("bytes íntegros (700 KB em vários pacotes)", got.all { it.bytes.contentEquals(cam.payload) })
        assertTrue("não acessou foto inexistente", !cam.crashed)
        assertEquals(listOf("1002", "9201", "9201", "9202", "9201", "9205"), cam.ops.take(6))
    }
}
