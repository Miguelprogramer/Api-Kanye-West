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
        if (intent?.getBooleanExtra("usb", false) == true) {
            cameraJob?.cancel(); cameraJob = scope.launch { runUsb() }
            scope.launch { processLoop() }
            return START_STICKY
        }
        CameraNetwork.listener = { n ->
            cameraJob?.cancel()
            if (n != null) cameraJob = scope.launch { runCamera(n) }
            else status("Conexão caiu. Wi-Fi da câmera perdido")
        }
        scope.launch {
            val n = CameraNetwork.network ?: CameraNetwork.findExisting(this@SyncService) { log(it) }
            if (n != null) { log("Usando rede da câmera (${CameraNetwork.host}). IP do celular: ${CameraNetwork.ipOf(this@SyncService, n)}"); cameraJob = scope.launch { runCamera(n) } }
            else status("Conexão caiu. Câmera não respondeu no Wi-Fi")
        }
        scope.launch { processLoop() }
        return START_STICKY
    }

    /** Modo cabo: PTP pelo USB, mesma lógica Sony do Wi-Fi. */
    private suspend fun runUsb() {
        val um = getSystemService(android.hardware.usb.UsbManager::class.java)
        var fails = 0
        while (scope.isActive) {
            val dev = UsbCamera.find(um)
            if (dev == null) { status("Conexão caiu. Cabo desconectado ou câmera fora do modo PC Remoto"); delay(3000); continue }
            if (!um.hasPermission(dev)) { status("Conexão caiu. Sem permissão do USB — abra o app e toque em Iniciar"); delay(3000); continue }
            try {
                log("Cabo: ${UsbCamera.describe(dev)}")
                status("Conectando na a6400 pelo cabo…")
                val cam = SonyCamera(UsbCamera.open(um, dev)) { log(it) }.also { camera = it }
                cam.connect()
                fails = 0
                status("Conectada pelo cabo! Pode fotografar.")
                cam.listen(onPhoto = { queue.trySend(it) }, onSkipped = { log("Vídeo ignorado: $it") })
            } catch (e: Exception) {
                fails++
                status("Conexão caiu. Tentando de novo…")
                log("ERRO: ${e.javaClass.simpleName}: ${e.message}")
                camera?.close()
                delay(if (fails > 3) 10000 else 3000)
            }
        }
    }

    private suspend fun runCamera(network: Network) {
        var fails = 0
        while (scope.isActive) {
            try {
                if (fails % 3 == 0) {
                    status("Procurando o endereço da câmera…")
                    if (!CameraNetwork.locate(this, network) { log(it) }) {
                        fails++
                        status("Conexão caiu. Câmera não respondeu na porta de controle")
                        if (!diagnosed) { diagnosed = true; Diagnostics.run(network, CameraNetwork.host) { log(it) } }
                        delay(if (fails > 3) 15000 else 5000); continue
                    }
                }
                status("Conectando na a6400 (${CameraNetwork.host})…")
                val cam = SonyCamera(network.socketFactory, Settings(this).deviceId, CameraNetwork.host) { log(it) }.also { camera = it }
                cam.connect()
                fails = 0
                status("Conectada! Pode fotografar.")
                cam.listen(
                    onPhoto = { queue.trySend(it) },
                    onSkipped = { log("Vídeo ignorado: $it") },
                )
            } catch (e: Exception) {
                fails++
                status("Conexão caiu. Tentando de novo…")
                log("ERRO: ${e.javaClass.simpleName}: ${e.message}")
                camera?.close()
                delay(if (fails > 3) 15000 else 4000)
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
