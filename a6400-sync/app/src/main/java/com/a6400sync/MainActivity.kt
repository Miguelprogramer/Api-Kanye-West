package com.a6400sync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity(), SyncBus.Listener {
    private lateinit var settings: Settings
    private val adapter = ThumbAdapter { show(it) }
    private var current: SyncBus.PhotoEvent? = null
    private val logLines = ArrayDeque<String>()

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun <T : View> v(id: Int): T = findViewById(id)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        v<RecyclerView>(R.id.recent).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@MainActivity.adapter
        }

        v<MaterialButton>(R.id.startStop).setOnClickListener {
            if (SyncBus.state == SyncBus.State.OFF) start() else { stopService(Intent(this, SyncService::class.java)); CameraNetwork.release(this); onState(SyncBus.State.OFF, "Desconectada") }
        }

        // Segurar a prévia mostra o original (antes x depois)
        v<ImageView>(R.id.preview).setOnTouchListener { iv, ev ->
            val e = current ?: return@setOnTouchListener false
            val o = e.original ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> (iv as ImageView).setImageBitmap(o)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> (iv as ImageView).setImageBitmap(e.thumb)
            }
            true
        }

        bindSettings()
    }

    override fun onResume() {
        super.onResume()
        SyncBus.listener = this
        onState(SyncBus.state, SyncBus.stateText)
        adapter.submit(SyncBus.recent.toList())
        updateStats(SyncBus.total, SyncBus.kept)
        SyncBus.recent.firstOrNull()?.let { show(it) }
    }

    override fun onPause() { super.onPause(); if (SyncBus.listener === this) SyncBus.listener = null }

    private val permLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        val needed = res.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
        if (needed.values.all { it }) start()
        else dialog("Permissão necessária",
            "Sem a permissão de Localização / Dispositivos próximos o Android não deixa o app achar o Wi-Fi da câmera.\n\nToque em Abrir, vá em Permissões e permita.",
            "Abrir") { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
    }

    private fun requiredPerms() = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.NEARBY_WIFI_DEVICES); add(Manifest.permission.POST_NOTIFICATIONS) }
    }

    /** Fluxo de conexão passo a passo, com uma explicação pra cada coisa que faltar. */
    private fun start() {
        // 1. Permissões (pedidas ANTES de qualquer coisa)
        val missing = requiredPerms().filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { permLauncher.launch(missing.toTypedArray()); return }

        // 2. Localização ligada (no Xiaomi, sem isso a busca de Wi-Fi não aparece)
        val lm = getSystemService(android.location.LocationManager::class.java)
        if (!lm.isLocationEnabled) {
            dialog("Ligue a Localização", "O Android só procura a rede da câmera com a Localização (GPS) ligada.", "Ligar") {
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }; return
        }

        // 3. Já está no Wi-Fi da câmera? Usa direto.
        CameraNetwork.findExisting(this)?.let { CameraNetwork.use(it); launchService(); return }

        // 4. Pede a rede pelo Android (janela do sistema)
        if (settings.wifiPassword.isBlank()) {
            dialog("Senha do Wi-Fi da câmera", "Digite em Ajustes a senha que aparece na tela da câmera (Ctrl c/ Smartphone).\n\nOu conecte manualmente nas configurações de Wi-Fi.", "Wi-Fi manual") { openWifi() }
            return
        }
        onState(SyncBus.State.SEARCHING, "Escolha a rede DIRECT-… na janela do Android")
        try {
            CameraNetwork.request(this, settings.wifiPassword,
                onReady = { runOnUiThread { launchService() } },
                onFail = { runOnUiThread { manualFallback() } })
        } catch (e: Exception) {
            onLog("ERRO ao pedir rede: ${e.message}"); manualFallback()
        }
    }

    private fun launchService() {
        startForegroundService(Intent(this, SyncService::class.java))
        onState(SyncBus.State.SEARCHING, "Conectando na câmera…")
    }

    private fun manualFallback() {
        onState(SyncBus.State.OFF, "Desconectada")
        dialog("Conecte pelo Wi-Fi do celular",
            "A janela não encontrou a câmera. Faça assim:\n\n1. Toque em Abrir Wi-Fi\n2. Escolha DIRECT-…ILCE-6400 e digite a senha\n3. Se aparecer \"Sem internet\", escolha MANTER conectado\n4. Volte aqui e toque em Iniciar",
            "Abrir Wi-Fi") { openWifi() }
    }

    private fun openWifi() = startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))

    private fun dialog(title: String, msg: String, action: String, onAction: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton(action) { _, _ -> onAction() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun bindSettings() {
        findViewById<android.widget.EditText>(R.id.wifiPass).apply {
            setText(settings.wifiPassword)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { settings.wifiPassword = e.toString().trim() }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }
        v<MaterialSwitch>(R.id.swEdit).apply { isChecked = settings.autoEdit; setOnCheckedChangeListener { _, c -> settings.autoEdit = c } }
        v<MaterialSwitch>(R.id.swDiscard).apply { isChecked = settings.keepDiscarded; setOnCheckedChangeListener { _, c -> settings.keepDiscarded = c } }
        v<MaterialSwitch>(R.id.swDup).apply { isChecked = settings.filterDuplicates; setOnCheckedChangeListener { _, c -> settings.filterDuplicates = c } }

        val lblBlur = v<TextView>(R.id.lblBlur); val lblSat = v<TextView>(R.id.lblSat)
        fun blurText(x: Float) = "Exigência de foco: " + when { x < 34 -> "tolerante"; x < 67 -> "equilibrada"; else -> "exigente" }
        v<Slider>(R.id.slBlur).apply {
            value = settings.blurStrictness; lblBlur.text = blurText(value)
            addOnChangeListener { _, x, _ -> settings.blurStrictness = x; lblBlur.text = blurText(x) }
        }
        v<Slider>(R.id.slSat).apply {
            value = settings.saturationBoost; lblSat.text = "Realce de cor: +${value.toInt()}%"
            addOnChangeListener { _, x, _ -> settings.saturationBoost = x; lblSat.text = "Realce de cor: +${x.toInt()}%" }
        }
        val ids = mapOf("natural" to R.id.stNatural, "quente" to R.id.stWarm, "frio" to R.id.stCool, "pb" to R.id.stMono)
        v<ChipGroup>(R.id.styles).apply {
            check(ids[settings.style] ?: R.id.stNatural)
            setOnCheckedStateChangeListener { _, checked ->
                settings.style = ids.entries.firstOrNull { it.value == checked.firstOrNull() }?.key ?: "natural"
            }
        }
    }

    override fun onState(state: SyncBus.State, text: String) {
        val (c, label) = when (state) {
            SyncBus.State.OFF -> R.color.muted to "Desconectada"
            SyncBus.State.SEARCHING -> R.color.warn to text
            SyncBus.State.CONNECTED -> R.color.ok to "Conectada · pode fotografar"
            SyncBus.State.ERROR -> R.color.bad to "Reconectando…"
        }
        v<View>(R.id.statusDot).backgroundTintList = ColorStateList.valueOf(color(c))
        v<TextView>(R.id.statusText).text = label
        v<MaterialButton>(R.id.startStop).apply {
            val on = state != SyncBus.State.OFF
            this.text = if (on) "Parar sincronização" else "Iniciar sincronização"
            backgroundTintList = ColorStateList.valueOf(color(if (on) R.color.surface2 else R.color.accent))
        }
    }

    override fun onLog(text: String) {
        logLines.addFirst(text); if (logLines.size > 30) logLines.removeLast()
        v<TextView>(R.id.log).text = logLines.joinToString("\n")
    }

    override fun onPhoto(e: SyncBus.PhotoEvent, total: Int, kept: Int) {
        adapter.submit(SyncBus.recent.toList())
        v<RecyclerView>(R.id.recent).scrollToPosition(0)
        updateStats(total, kept); show(e)
    }

    private fun updateStats(total: Int, kept: Int) {
        v<TextView>(R.id.statTotal).text = "$total"
        v<TextView>(R.id.statKept).text = "$kept"
        v<TextView>(R.id.statBad).text = "${total - kept}"
        v<LinearProgressIndicator>(R.id.keepRate).apply {
            visibility = if (total == 0) View.INVISIBLE else View.VISIBLE
            progress = if (total == 0) 0 else kept * 100 / total
        }
    }

    private fun show(e: SyncBus.PhotoEvent) {
        current = e
        v<ImageView>(R.id.preview).setImageBitmap(e.thumb)
        v<View>(R.id.previewEmpty).visibility = View.GONE
        v<TextView>(R.id.previewBadge).apply {
            visibility = View.VISIBLE
            text = if (e.kept) "✓ ${e.name} · aprovada" else "✗ ${e.name} · ${e.reasons.joinToString()}"
            setTextColor(color(if (e.kept) R.color.ok else R.color.bad))
        }
        v<View>(R.id.previewToggle).visibility = if (e.original != null) View.VISIBLE else View.GONE
    }
}

class ThumbAdapter(private val onClick: (SyncBus.PhotoEvent) -> Unit) : RecyclerView.Adapter<ThumbAdapter.VH>() {
    private var items = listOf<SyncBus.PhotoEvent>()
    class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card) { val img: ImageView = card.findViewById(R.id.img) }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<SyncBus.PhotoEvent>) { items = list; notifyDataSetChanged() }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_thumb, p, false) as MaterialCardView)
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val e = items[i]
        h.img.setImageBitmap(e.thumb)
        h.card.strokeColor = ContextCompat.getColor(h.card.context, if (e.kept) R.color.ok else R.color.bad)
        h.card.setOnClickListener { onClick(e) }
    }
}
