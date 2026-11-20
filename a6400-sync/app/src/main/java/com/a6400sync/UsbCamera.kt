package com.a6400sync

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/** Acha a câmera no cabo e abre o canal PTP. */
object UsbCamera {
    const val SONY_VENDOR = 0x054C
    const val ACTION_PERMISSION = "com.a6400sync.USB_PERMISSION"

    /** Câmera com interface PTP (modo PC Remoto/MTP). */
    fun find(um: UsbManager): UsbDevice? {
        val all = um.deviceList.values
        return all.firstOrNull { it.vendorId == SONY_VENDOR && ptpInterface(it) != null }
            ?: all.firstOrNull { ptpInterface(it) != null }
    }

    /** Sony plugada, mas sem PTP (ex.: modo Armazenamento em Massa). */
    fun sonyWithoutPtp(um: UsbManager): UsbDevice? =
        um.deviceList.values.firstOrNull { it.vendorId == SONY_VENDOR && ptpInterface(it) == null }

    fun describe(d: UsbDevice) =
        "${runCatching { d.manufacturerName }.getOrNull() ?: "?"} ${runCatching { d.productName }.getOrNull() ?: "?"} (VID %04X PID %04X)".format(d.vendorId, d.productId)

    fun ptpInterface(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { i ->
        (i.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE || i.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) &&
            endpoints(i).let { (a, b, c) -> a != null && b != null && c != null }
    }

    private fun endpoints(i: UsbInterface): Triple<UsbEndpoint?, UsbEndpoint?, UsbEndpoint?> {
        val eps = (0 until i.endpointCount).map { i.getEndpoint(it) }
        val bulkIn = eps.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        val bulkOut = eps.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
        val intr = eps.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == UsbConstants.USB_DIR_IN }
        return Triple(bulkIn, bulkOut, intr)
    }

    fun open(um: UsbManager, d: UsbDevice): UsbPtpTransport {
        val intf = ptpInterface(d) ?: throw PtpException("A câmera não está no modo PC Remoto")
        val conn = um.openDevice(d) ?: throw PtpException("Sem permissão pra usar o cabo USB")
        val (bin, bout, intr) = endpoints(intf)
        return UsbPtpTransport(object : UsbPtpTransport.BulkIo {
            override fun claim() {
                if (!conn.claimInterface(intf, true)) { conn.close(); throw PtpException("Outro app está usando a câmera (feche apps de foto/galeria e tente de novo)") }
            }
            override fun reset() {
                // Still Image class: Device Reset Request (0x66)
                conn.controlTransfer(0x21, 0x66, 0, intf.id, null, 0, 1000)
                Thread.sleep(300)
            }
            override fun write(buf: ByteArray, off: Int, len: Int, timeoutMs: Int) = conn.bulkTransfer(bout, buf, off, len, timeoutMs)
            override fun read(buf: ByteArray, timeoutMs: Int) = conn.bulkTransfer(bin, buf, buf.size, timeoutMs)
            override fun readEvent(buf: ByteArray, timeoutMs: Int) = conn.bulkTransfer(intr, buf, buf.size, timeoutMs)
            override fun release() { runCatching { conn.releaseInterface(intf) }; conn.close() }
        })
    }
}
