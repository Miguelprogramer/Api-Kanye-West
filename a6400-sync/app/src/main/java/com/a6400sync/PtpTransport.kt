package com.a6400sync

class PtpException(msg: String, val code: Int = 0) : Exception(msg)
data class PtpResponse(val code: Int, val params: List<Int>, val data: ByteArray)
data class PtpEvent(val code: Int, val params: List<Int>)

/** Canal PTP (aqui: cabo USB). */
interface PtpTransport {
    fun open()
    fun operation(code: Int, vararg params: Int): PtpResponse
    fun operationOut(code: Int, data: ByteArray, vararg params: Int): PtpResponse
    fun nextEvent(): PtpEvent
    fun close()
}
