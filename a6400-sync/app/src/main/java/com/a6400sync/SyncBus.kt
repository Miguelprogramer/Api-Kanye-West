package com.a6400sync

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

/** Ponte entre o serviço e a tela. Tudo é entregue na thread da interface. */
object SyncBus {
    enum class State { OFF, SEARCHING, CONNECTED, ERROR }
    data class PhotoEvent(val name: String, val thumb: Bitmap, val original: Bitmap?, val kept: Boolean, val reasons: List<String>)

    interface Listener {
        fun onState(state: State, text: String)
        fun onLog(text: String)
        fun onPhoto(e: PhotoEvent, total: Int, kept: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    var listener: Listener? = null
    var state = State.OFF; private set
    var stateText = "Desconectada"; private set
    var total = 0; var kept = 0
    /** null = ainda não sabe; true = câmera aceitou PC Remoto; false = modo errado */
    @Volatile var pcRemote: Boolean? = null
    val recent = ArrayDeque<PhotoEvent>()

    fun state(s: State, text: String) { state = s; stateText = text; main.post { listener?.onState(s, text) } }
    fun log(text: String) = main.post { listener?.onLog(text) }
    fun photo(e: PhotoEvent) = main.post {
        total++; if (e.kept) kept++
        recent.addFirst(e); if (recent.size > 30) recent.removeLast()
        listener?.onPhoto(e, total, kept)
    }
}
