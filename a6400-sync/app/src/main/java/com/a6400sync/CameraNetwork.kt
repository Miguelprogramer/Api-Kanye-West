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
    const val CAMERA_PREFIX = "192.168.122."
    var network: Network? = null; private set
    var listener: ((Network?) -> Unit)? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    private fun cm(ctx: Context) = ctx.getSystemService(ConnectivityManager::class.java)

    /** Plano B: celular já conectado na câmera pelas Configurações → usamos essa rede. */
    fun findExisting(ctx: Context): Network? {
        val cm = cm(ctx)
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                cm.getLinkProperties(n)?.linkAddresses?.any { it.address.hostAddress?.startsWith(CAMERA_PREFIX) == true } == true
        }
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
