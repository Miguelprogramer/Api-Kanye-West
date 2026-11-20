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
    private var diagnosed = false
    private var cameraJob: kotlinx.coroutines.Job? = null
    private var count = 0; private var kept = 0


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("Procurando a câmera…"))
        CameraNetwork.listener = { n ->
            cameraJob?.cancel()
            if (n != null) cameraJob = scope.launch { runCamera(n) }
            else status("Conexão caiu. Wi-Fi da câmera perdido")
        }
        val n = CameraNetwork.network ?: CameraNetwork.findExisting(this)
        if (n != null) { log("Usando rede da câmera. IP do celular: ${CameraNetwork.ipOf(this, n)}"); cameraJob = scope.launch { runCamera(n) } }
        else status("Conexão caiu. Celular não está no Wi-Fi da câmera")
        scope.launch { processLoop() }
        return START_STICKY
    }

    private suspend fun runCamera(network: Network) {
        while (scope.isActive) {
            try {
                status("Conectando na a6400…")
                val cam = SonyCamera(network.socketFactory, Settings(this).deviceId) { log(it) }.also { camera = it }
                cam.connect()
                status("Conectada! Pode fotografar.")
                cam.listen(
                    onPhoto = { queue.trySend(it) },
                    onSkipped = { log("Vídeo ignorado: $it") },
                )
            } catch (e: Exception) {
                status("Conexão caiu. Tentando de novo…")
                log("ERRO: ${e.javaClass.simpleName}: ${e.message}")
                camera?.close()
                if (!diagnosed) { diagnosed = true; Diagnostics.run(network) { log(it) } }
                delay(4000)
            }
        }
    }

    private suspend fun processLoop() {
        val org = Organizer(this)
        val settings = Settings(this)
        for (photo in queue) {
            settings.applyToEngines()
            try {
                if (photo.format == SonyCamera.FMT_ARW) {          // RAW: guarda original (edição de RAW fica pra próxima fase)
                    org.save("Originais", photo.fileName, photo.bytes, "image/x-sony-arw")
                    log("RAW salvo: ${photo.fileName}"); continue
                }
                org.save("Originais", photo.fileName, photo.bytes)
                val t = Triage.analyze(photo.bytes)
                val original = Triage.decodeSmall(photo.bytes, 900)
                if (t.keep) {
                    val out = if (settings.autoEdit) AutoEdit.apply(photo.bytes) else photo.bytes
                    org.save("Selecionadas", photo.fileName.replace(".JPG", "_edit.jpg", true), out)
                    SyncBus.photo(SyncBus.PhotoEvent(photo.fileName, Triage.decodeSmall(out, 900), original, true, emptyList()))
                    log("✓ ${photo.fileName} aprovada")
                } else {
                    if (settings.keepDiscarded) org.save("Descartadas", photo.fileName, photo.bytes)
                    SyncBus.photo(SyncBus.PhotoEvent(photo.fileName, original, null, false, t.reasons))
                    log("✗ ${photo.fileName}: ${t.reasons.joinToString()}")
                }
                notifyText("${SyncBus.kept}/${SyncBus.total} aprovadas")
            } catch (e: Exception) { log("Erro em ${photo.fileName}: ${e.message}") }
        }
    }

    private fun status(text: String) {
        val st = when {
            text.startsWith("Conectada") -> SyncBus.State.CONNECTED
            text.startsWith("Conexão caiu") -> SyncBus.State.ERROR
            else -> SyncBus.State.SEARCHING
        }
        SyncBus.state(st, text); SyncBus.log(text); notifyText(text)
    }
    private fun log(text: String) = SyncBus.log(text)
    private fun notifyText(text: String) = getSystemService(NotificationManager::class.java).notify(1, notification(text))

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("sync", "Sincronização", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "sync").setContentTitle("A6400 Sync").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
    }

    override fun onDestroy() { CameraNetwork.listener = null; CameraNetwork.release(this); SyncBus.state(SyncBus.State.OFF, "Desconectada"); camera?.close(); scope.cancel(); super.onDestroy() }
}
