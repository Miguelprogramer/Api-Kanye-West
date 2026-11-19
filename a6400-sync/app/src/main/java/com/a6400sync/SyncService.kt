package com.a6400sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano: fica conectado na câmera e, a cada disparo,
 * baixa -> depura -> edita -> organiza. Download e processamento rodam em
 * paralelo (fila), então você pode continuar fotografando sem esperar.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<SonyCamera.Photo>(capacity = 50)
    private var camera: SonyCamera? = null
    private var count = 0; private var kept = 0

    companion object { var onStatus: ((String) -> Unit)? = null }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("Procurando a câmera…"))
        val cm = getSystemService(ConnectivityManager::class.java)
        // O Wi-Fi da câmera não tem internet: pedimos a rede Wi-Fi explicitamente pra o Android não ignorá-la.
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scope.launch { runCamera(network) } }
        })
        scope.launch { processLoop() }
        return START_STICKY
    }

    private suspend fun runCamera(network: Network) {
        while (scope.isActive) {
            try {
                status("Conectando na a6400…")
                val cam = SonyCamera(network.socketFactory).also { camera = it }
                cam.connect()
                status("Conectada! Pode fotografar.")
                cam.listen(
                    onPhoto = { queue.trySend(it) },
                    onSkipped = { status("Ignorado (não é foto): $it") },
                )
            } catch (e: Exception) {
                status("Conexão caiu (${e.message}). Tentando de novo…")
                camera?.close(); delay(3000)
            }
        }
    }

    private suspend fun processLoop() {
        val org = Organizer(this)
        for (photo in queue) {
            count++
            try {
                if (photo.format == SonyCamera.FMT_ARW) {          // RAW: guarda original (edição de RAW fica pra próxima fase)
                    org.save("Originais", photo.fileName, photo.bytes, "image/x-sony-arw"); continue
                }
                org.save("Originais", photo.fileName, photo.bytes)
                val t = Triage.analyze(photo.bytes)
                if (t.keep) {
                    kept++
                    org.save("Selecionadas", photo.fileName.replace(".JPG", "_edit.jpg", true), AutoEdit.apply(photo.bytes))
                    status("✅ ${photo.fileName} — $kept/$count aprovadas")
                } else {
                    org.save("Descartadas", photo.fileName, photo.bytes)
                    status("🗑 ${photo.fileName}: ${t.reasons.joinToString()} — $kept/$count aprovadas")
                }
            } catch (e: Exception) { status("Erro em ${photo.fileName}: ${e.message}") }
        }
    }

    private fun status(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
        onStatus?.invoke(text)
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("sync", "Sincronização", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "sync").setContentTitle("A6400 Sync").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
    }

    override fun onDestroy() { camera?.close(); scope.cancel(); super.onDestroy() }
}
