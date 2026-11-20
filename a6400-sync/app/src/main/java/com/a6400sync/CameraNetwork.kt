package com.a6400sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher

/**
 * Cuida da rede Wi-Fi da câmera. Fica na Activity (primeiro plano) porque no
 * HyperOS/MIUI a janela de escolha de rede não abre se pedida pelo serviço.
 */
object CameraNetwork {
        var network: Network? = null; private set
    var listener: ((Network?) -> Unit)? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    private fun cm(ctx: Context) = ctx.getSystemService(ConnectivityManager::class.java)

    var host: String = "192.168.122.1"; private set

    /**
     * Plano B: o celular já está no Wi-Fi da câmera (conectado nas Configurações).
     * Não confia no número do IP: testa de verdade se a câmera responde, em toda rede Wi-Fi,
     * usando o gateway da rede (que é a própria câmera) e o endereço padrão da Sony.
     * Faz rede: chamar FORA da thread principal.
     */
    fun findExisting(ctx: Context, log: (String) -> Unit = {}): Network? {
        val cm = cm(ctx)
        @Suppress("DEPRECATION")
        val wifis = cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        if (wifis.isEmpty()) log("Nenhuma rede Wi-Fi ativa no celular")
        return wifis.firstOrNull { locate(ctx, it, log) }
    }

    /**
     * Descobre o endereço da câmera NESSA rede: testa o gateway, o servidor DHCP,
     * o ".1" da sub-rede do celular e o padrão da Sony (192.168.122.1).
     * Prefere quem tem a porta PTP/IP (15740) aberta. Faz rede: fora da thread principal.
     */
    fun locate(ctx: Context, n: Network, log: (String) -> Unit): Boolean {
        val cm = cm(ctx)
        val lp = cm.getLinkProperties(n)
        val gw = lp?.routes?.firstOrNull { it.gateway != null && !it.gateway!!.isAnyLocalAddress }?.gateway?.hostAddress
        val dhcp = if (android.os.Build.VERSION.SDK_INT >= 30) lp?.dhcpServerAddress?.hostAddress else null
        val myIps = lp?.linkAddresses?.mapNotNull { it.address.hostAddress }?.filter { it.count { c -> c == '.' } == 3 }.orEmpty()
        val subnetOne = myIps.map { it.substringBeforeLast('.') + ".1" }
        val internet = cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        log("Wi-Fi: IP ${myIps.joinToString()} · gateway ${gw ?: "?"} · DHCP ${dhcp ?: "?"} · ${if (internet) "com internet" else "sem internet"}")
        val candidates = (listOfNotNull(gw, dhcp) + subnetOne + "192.168.122.1").distinct()
        var fallback: String? = null
        for (h in candidates) {
            val open = listOf(15740, 64321, 8080, 10000).filter { port ->
                runCatching { n.socketFactory.createSocket().use { it.connect(java.net.InetSocketAddress(h, port), 900) } }.isSuccess
            }
            log("  $h → portas abertas: ${if (open.isEmpty()) "nenhuma" else open.joinToString()}")
            if (15740 in open) { host = h; log("Câmera encontrada em $h"); return true }
            if (open.isNotEmpty() && fallback == null) fallback = h
        }
        if (fallback != null) {
            host = fallback
            log("Achei a câmera em $fallback, mas a porta 15740 (controle) está fechada. Na câmera use Menu → Rede → Ctrl c/ Smartphone e deixe na tela de conexão.")
        }
        return false
    }

    fun ipOf(ctx: Context, n: Network) =
        cm(ctx).getLinkProperties(n)?.linkAddresses?.joinToString { it.address.hostAddress ?: "" } ?: "?"

    fun use(n: Network?) { network = n; listener?.invoke(n) }

    /** Plano A: o Android mostra a janela com a rede DIRECT-… da câmera. */
    fun request(ctx: Context, password: String, onReady: (Network) -> Unit, onFail: () -> Unit) {
        release(ctx)
        val spec = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher("DIRECT-", PatternMatcher.PATTERN_PREFIX))
            .setWpa2Passphrase(password)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { use(n); onReady(n) }
            override fun onUnavailable() { onFail() }
            override fun onLost(n: Network) { if (network == n) use(null) }
        }
        callback = cb
        cm(ctx).requestNetwork(req, cb, 90_000)
    }

    fun release(ctx: Context) {
        callback?.let { runCatching { cm(ctx).unregisterNetworkCallback(it) } }
        callback = null
    }
}
