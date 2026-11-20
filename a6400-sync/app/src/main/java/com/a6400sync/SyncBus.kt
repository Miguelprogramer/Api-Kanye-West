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
        fun onCamera(model: String?, props: Map<Int, SonyProps.Prop>) {}
        fun onLiveFrame(frame: Bitmap) {}
    }

    private val main = Handler(Looper.getMainLooper())
    var listener: Listener? = null
    var state = State.OFF; private set
    var stateText = "Desconectada"; private set
    var total = 0; var kept = 0
    /** null = ainda não sabe; true = câmera aceitou PC Remoto; false = modo errado */
    @Volatile var pcRemote: Boolean? = null
    val recent = ArrayDeque<PhotoEvent>()

    /** Câmera conectada agora (pra disparo, ajustes e ao vivo). null = desconectada. */
    @Volatile var camera: SonyCamera? = null
    @Volatile var props: Map<Int, SonyProps.Prop> = emptyMap(); private set
    @Volatile var model: String? = null; private set

    fun camera(model: String?, props: Map<Int, SonyProps.Prop>) {
        this.model = model; this.props = props
        main.post { listener?.onCamera(model, props) }
    }
    fun liveFrame(b: Bitmap) = main.post { listener?.onLiveFrame(b) }

    fun state(s: State, text: String) { state = s; stateText = text; main.post { listener?.onState(s, text) } }
    fun log(text: String) = main.post { listener?.onLog(text) }
    fun photo(e: PhotoEvent) = main.post {
        total++; if (e.kept) kept++
        recent.addFirst(e); if (recent.size > 30) recent.removeLast()
        listener?.onPhoto(e, total, kept)
    }
}
