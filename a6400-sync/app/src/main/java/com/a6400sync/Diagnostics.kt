package com.a6400sync

import android.net.Network
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

/** Testa o que a câmera expõe na rede pra descobrir qual protocolo ela fala nesse modo. */
object Diagnostics {
    private val PORTS = listOf(15740 to "PTP/IP", 8080 to "Camera Remote API", 10000 to "Camera Remote API (alt)", 64321 to "Descrição UPnP", 60151 to "outro")

    fun run(network: Network, host: String, log: (String) -> Unit) {
        val HOST = host
        log("── Diagnóstico ──")
        for ((port, label) in PORTS) {
            val open = try {
                network.socketFactory.createSocket().use { it.connect(InetSocketAddress(HOST, port), 1500) }; true
            } catch (_: Exception) { false }
            log("Porta $port ($label): ${if (open) "ABERTA" else "fechada"}")
        }
        for (path in listOf("http://$HOST:64321/DmsDescPush.xml", "http://$HOST:64321/dd.xml", "http://$HOST:8080/sony/camera")) {
            try {
                val c = network.openConnection(URL(path)) as HttpURLConnection
                c.connectTimeout = 2000; c.readTimeout = 2000
                val code = c.responseCode
                val body = runCatching { c.inputStream.bufferedReader().readText() }.getOrDefault("")
                val model = Regex("<modelName>(.*?)</modelName>").find(body)?.groupValues?.get(1)
                log("GET ${path.substringAfter(HOST)} → HTTP $code${model?.let { " · modelo $it" } ?: ""}")
            } catch (e: Exception) { log("GET ${path.substringAfter(HOST)} → ${e.javaClass.simpleName}") }
        }
        log("── Fim do diagnóstico: mande um print disso ──")
    }
}
