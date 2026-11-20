package com.a6400sync

/** Canal PTP genérico: Wi-Fi (PTP/IP) ou cabo USB. A lógica Sony é a mesma nos dois. */
interface PtpTransport {
    fun open()
    fun operation(code: Int, vararg params: Int): PtpIpClient.Response
    fun operationOut(code: Int, data: ByteArray, vararg params: Int): PtpIpClient.Response
    fun nextEvent(): PtpIpClient.Event
    fun close()
}
