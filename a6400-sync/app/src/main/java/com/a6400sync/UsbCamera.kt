package com.a6400sync

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/** Acha a câmera Sony no cabo e abre o canal PTP. */
object UsbCamera {
    const val SONY_VENDOR = 0x054C
    const val ACTION_PERMISSION = "com.a6400sync.USB_PERMISSION"

    fun find(um: UsbManager): UsbDevice? =
        um.deviceList.values.firstOrNull { it.vendorId == SONY_VENDOR && ptpInterface(it) != null }
            ?: um.deviceList.values.firstOrNull { ptpInterface(it)?.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }

    fun describe(d: UsbDevice) = "${d.manufacturerName ?: "?"} ${d.productName ?: "?"} (VID %04X PID %04X)".format(d.vendorId, d.productId)

    /** Interface com bulk-in, bulk-out e interrupção (classe 6 = imagem; Sony às vezes usa 0xFF). */
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
        val intf = ptpInterface(d) ?: throw PtpIpClient.PtpException("A câmera não está no modo PC Remoto (sem interface PTP)")
        val conn: UsbDeviceConnection = um.openDevice(d) ?: throw PtpIpClient.PtpException("Sem permissão pra usar o cabo USB")
        val (bin, bout, intr) = endpoints(intf)
        return UsbPtpTransport(object : UsbPtpTransport.BulkIo {
            override fun claim() { if (!conn.claimInterface(intf, true)) throw PtpIpClient.PtpException("Não consegui assumir a interface USB") }
            override fun write(buf: ByteArray, off: Int, len: Int, timeoutMs: Int) = conn.bulkTransfer(bout, buf, off, len, timeoutMs)
            override fun read(buf: ByteArray, timeoutMs: Int) = conn.bulkTransfer(bin, buf, buf.size, timeoutMs)
            override fun readEvent(buf: ByteArray, timeoutMs: Int) = conn.bulkTransfer(intr, buf, buf.size, timeoutMs)
            override fun release() { runCatching { conn.releaseInterface(intf) }; conn.close() }
        })
    }
}
