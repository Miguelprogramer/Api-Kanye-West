package com.a6400sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano: mantém a conexão pelo cabo e, a cada disparo,
 * baixa → depura → edita → organiza. Download e processamento em paralelo (fila).
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<SonyCamera.Photo>(capacity = 50)
    @Volatile private var camera: SonyCamera? = null
    private var cameraJob: Job? = null
    private var processJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("Conectando pelo cabo…"))
        if (processJob?.isActive != true) processJob = scope.launch { processLoop() }
        if (cameraJob?.isActive != true) cameraJob = scope.launch { runUsb() }
        return START_STICKY
    }

    private suspend fun runUsb() {
        val um = getSystemService(UsbManager::class.java)
        var fails = 0
        var lastProblem = ""
        while (scope.isActive) {
            val dev = UsbCamera.find(um)
            val problem = when {
                dev != null && !um.hasPermission(dev) -> "Sem permissão do USB — abra o app e toque em Iniciar"
                dev != null -> ""
                UsbCamera.sonyWithoutPtp(um) != null -> "Câmera no modo Armazenamento em Massa. ${SonyCamera.PC_REMOTE_HELP}"
                um.deviceList.isEmpty() -> "Cabo desconectado (ou OTG desligado no celular)"
                else -> "Aparelho USB não reconhecido como câmera"
            }
            if (problem.isNotEmpty()) {
                SyncBus.pcRemote = null
                if (problem != lastProblem) { status("Aguardando: $problem"); lastProblem = problem }
                delay(2000); continue
            }
            lastProblem = ""
            try {
                log("Cabo: ${UsbCamera.describe(dev!!)}")
                status("Conectando na câmera…")
                val cam = SonyCamera(UsbCamera.open(um, dev)) { log(it) }.also { camera = it }
                cam.connect()
                cam.onProps = { SyncBus.camera(cam.model, it) }
                SyncBus.camera = cam
                runCatching { cam.refreshProps() }
                SyncBus.pcRemote = true
                fails = 0
                status("Conectada! Pode fotografar.")
                cam.listen(onPhoto = { queue.trySend(it) }, onSkipped = { log("Vídeo ignorado: $it") })
            } catch (e: Exception) {
                fails++
                if (e.message?.contains("PC Remoto") == true) SyncBus.pcRemote = false
                log("ERRO: ${e.message ?: e.javaClass.simpleName}")
                status("Erro. Tentando de novo…")
                camera?.close(); camera = null
                SyncBus.camera = null; SyncBus.camera(null, emptyMap())
                delay(if (fails > 3) 8000 else 2500)
            }
        }
    }

    private suspend fun processLoop() {
        val org = Organizer(this)
        val settings = Settings(this)
        for (photo in queue) {
            settings.applyToEngines()
            try {
                if (photo.format == SonyCamera.FMT_ARW || photo.fileName.endsWith(".ARW", true)) {
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
            } catch (e: Throwable) { log("Erro em ${photo.fileName}: ${e.message}") }
        }
    }

    private fun status(text: String) {
        val st = when {
            text.startsWith("Conectada") -> SyncBus.State.CONNECTED
            text.startsWith("Erro") || text.startsWith("Aguardando") -> SyncBus.State.ERROR
            else -> SyncBus.State.SEARCHING
        }
        SyncBus.state(st, text); SyncBus.log(text); notifyText(text)
    }

    private fun log(text: String) = SyncBus.log(text)
    private fun notifyText(text: String) = getSystemService(NotificationManager::class.java).notify(1, notification(text))

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync", "Sincronização", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "sync").setContentTitle("A6400 Sync").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
    }

    override fun onDestroy() {
        SyncBus.state(SyncBus.State.OFF, "Desconectada")
        SyncBus.camera = null; SyncBus.camera(null, emptyMap())
        camera?.close(); scope.cancel(); super.onDestroy()
    }
}
