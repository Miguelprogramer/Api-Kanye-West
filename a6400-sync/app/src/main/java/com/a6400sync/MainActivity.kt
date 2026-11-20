package com.a6400sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.usb.UsbManager
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
    private val usbManager by lazy { getSystemService(UsbManager::class.java) }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun <T : View> v(id: Int): T = findViewById(id)

    /** Permissão do USB, cabo plugado e cabo solto. */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbCamera.ACTION_PERMISSION ->
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) start()
                    else onLog("Permissão do cabo USB negada. Toque em Iniciar e aceite.")
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> { onLog("Cabo plugado"); if (SyncBus.state == SyncBus.State.OFF) start() }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onLog("Cabo desplugado")
            }
            refreshChecklist()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        val pi = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        v<TextView>(R.id.version).text = "Versão ${pi.versionName} (build ${pi.versionCode}) · modo cabo USB"

        v<RecyclerView>(R.id.recent).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@MainActivity.adapter
        }
        v<MaterialButton>(R.id.startStop).setOnClickListener {
            if (SyncBus.state == SyncBus.State.OFF) start()
            else { stopService(Intent(this, SyncService::class.java)); onState(SyncBus.State.OFF, "Desconectada") }
        }
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
        buildCameraPanel()

        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter().apply {
            addAction(UsbCamera.ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && SyncBus.state == SyncBus.State.OFF) start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && SyncBus.state == SyncBus.State.OFF) start()
    }

    override fun onDestroy() { runCatching { unregisterReceiver(usbReceiver) }; super.onDestroy() }

    override fun onResume() {
        super.onResume()
        SyncBus.listener = this
        onState(SyncBus.state, SyncBus.stateText)
        adapter.submit(SyncBus.recent.toList())
        updateStats(SyncBus.total, SyncBus.kept)
        SyncBus.recent.firstOrNull()?.let { show(it) }
        refreshChecklist()
        onCamera(SyncBus.model, SyncBus.props)
    }

    override fun onPause() {
        super.onPause()
        SyncBus.camera?.stopLive()
        if (SyncBus.listener === this) SyncBus.listener = null
    }

    // ───────────── Painel da câmera ─────────────

    private val valueViews = HashMap<Int, TextView>()
    private var stepDialog: Pair<Int, TextView>? = null

    /** Roda um comando na câmera fora da thread da tela e mostra o erro, se houver. */
    private fun cmd(what: String, block: (SonyCamera) -> Unit) {
        val cam = SyncBus.camera ?: run { onLog("Câmera não conectada"); return }
        Thread {
            try { block(cam) }
            catch (e: Exception) { runOnUiThread { onLog("$what falhou: ${e.message}") } }
        }.start()
    }

    private fun buildCameraPanel() {
        val grid = v<android.widget.GridLayout>(R.id.camGrid)
        for (spec in CameraFormat.specs) {
            val item = LayoutInflater.from(this).inflate(R.layout.item_setting, grid, false)
            item.layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = 0; columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            }
            item.findViewById<TextView>(R.id.label).text = spec.label
            valueViews[spec.code] = item.findViewById(R.id.value)
            item.setOnClickListener { openSetting(spec) }
            grid.addView(item)
        }
        v<MaterialButton>(R.id.btnShoot).setOnClickListener {
            onLog("Disparando…")
            cmd("Disparo") { it.capture() }
        }
        v<MaterialButton>(R.id.btnAf).setOnClickListener {
            cmd("Foco") { cam -> val ok = cam.autofocus(); runOnUiThread { onLog(if (ok) "Foco OK" else "Não achou foco") } }
        }
        v<MaterialButton>(R.id.btnLive).setOnClickListener {
            val cam = SyncBus.camera ?: return@setOnClickListener
            if (cam.isLive) { cam.stopLive(); liveOff() }
            else {
                v<MaterialButton>(R.id.btnLive).text = "Parar ao vivo"
                cam.startLive { jpeg ->
                    android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { SyncBus.liveFrame(it) }
                }
            }
        }
    }

    private fun liveOff() {
        v<MaterialButton>(R.id.btnLive).text = "Ao vivo"
        current?.let { show(it) } ?: run {
            v<ImageView>(R.id.preview).setImageDrawable(null); v<View>(R.id.previewEmpty).visibility = View.VISIBLE
        }
    }

    override fun onLiveFrame(frame: android.graphics.Bitmap) {
        if (SyncBus.camera?.isLive != true) return
        v<View>(R.id.previewEmpty).visibility = View.GONE
        v<ImageView>(R.id.preview).setImageBitmap(frame)
        v<TextView>(R.id.previewBadge).apply {
            visibility = View.VISIBLE; text = "● AO VIVO  " + CameraFormat.format(CameraFormat.FOCUS_IND, SyncBus.props[CameraFormat.FOCUS_IND]?.value)
            setTextColor(color(R.color.bad))
        }
    }

    private fun openSetting(spec: CameraFormat.Spec) {
        val prop = SyncBus.props[spec.code]
        if (SyncBus.camera == null || prop == null) { onLog("${spec.label}: conecte a câmera primeiro (ou a a6400 não oferece esse ajuste)"); return }
        if (prop.enabled == 0) onLog("${spec.label} está bloqueado na câmera agora (pode depender do modo do seletor)")
        when (spec.kind) {
            CameraFormat.Kind.STEP -> {
                val value = TextView(this).apply {
                    textSize = 34f; setTextColor(color(R.color.text)); gravity = android.view.Gravity.CENTER
                    text = CameraFormat.format(spec.code, prop.value); setPadding(0, 24, 0, 24)
                }
                fun btn(t: String, d: Int) = MaterialButton(this).apply {
                    text = t
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }
                    setOnClickListener {
                        cmd(spec.label) { cam ->
                            val old = cam.lastProps[spec.code]?.value
                            cam.step(spec.code, d); cam.waitChange(spec.code, old, 2000)
                        }
                    }
                }
                val box = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; setPadding(32, 16, 32, 0)
                    addView(value)
                    addView(android.widget.LinearLayout(this@MainActivity).apply {
                        addView(btn("◀ " + spec.minus, -1)); addView(btn(spec.plus + " ▶", +1))
                    })
                }
                stepDialog = spec.code to value
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(spec.label).setView(box).setPositiveButton("Pronto", null)
                    .setOnDismissListener { stepDialog = null }.show()
            }
            CameraFormat.Kind.PICK -> {
                val opts = prop.options.ifEmpty { CameraFormat.knownValues(spec.code) }.distinct()
                if (opts.isEmpty()) { onLog("${spec.label}: a câmera não informou as opções"); return }
                val labels = opts.map { CameraFormat.format(spec.code, it) }.toTypedArray()
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(spec.label)
                    .setSingleChoiceItems(labels, opts.indexOf(prop.value)) { d, i ->
                        d.dismiss()
                        cmd(spec.label) { cam ->
                            val old = cam.lastProps[spec.code]?.value
                            cam.setValue(spec.code, opts[i])
                            val now = cam.waitChange(spec.code, old)
                            if (now != opts[i]) runOnUiThread { onLog("${spec.label}: a câmera não aceitou ${labels[i]} (talvez bloqueado pelo modo)") }
                        }
                    }.show()
            }
        }
    }

    override fun onCamera(model: String?, props: Map<Int, SonyProps.Prop>) {
        val connected = SyncBus.camera != null
        v<View>(R.id.camCard).visibility = if (connected) View.VISIBLE else View.GONE
        v<View>(R.id.camLabel).visibility = if (connected) View.VISIBLE else View.GONE
        listOf(R.id.btnShoot, R.id.btnAf, R.id.btnLive).forEach { v<View>(it).isEnabled = connected }
        if (!connected && v<MaterialButton>(R.id.btnLive).text != "Ao vivo") liveOff()
        v<TextView>(R.id.camModel).text = if (connected) "Sony ${model ?: "a6400"}" else "Câmera desconectada"
        v<TextView>(R.id.camBattery).text = props[CameraFormat.BATTERY]?.value?.let { "🔋 " + CameraFormat.format(CameraFormat.BATTERY, it) } ?: ""
        val prog = props[CameraFormat.PROGRAM]?.value
        v<TextView>(R.id.camMode).text = if (prog != null) "Modo: ${CameraFormat.format(CameraFormat.PROGRAM, prog)} (mude no seletor da câmera)" else ""
        for ((code, tv) in valueViews) {
            val p = props[code]
            tv.text = if (p == null) "—" else CameraFormat.format(code, p.value)
            tv.alpha = if (p == null || p.enabled == 0) 0.4f else 1f
        }
        stepDialog?.let { (code, tv) -> tv.text = CameraFormat.format(code, props[code]?.value) }
    }

    private fun start() {
        if (waitingCard > 0) return   // já esperando o cartão abrir
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)

        // Câmera em modo PTP (PC Remoto)? Usa o modo ao vivo. Senão, importa do cartão.
        val dev = UsbCamera.find(usbManager) ?: return importFlow()
        onLog("Cabo detectado (PC Remoto): ${UsbCamera.describe(dev)}")
        if (!usbManager.hasPermission(dev)) {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(UsbCamera.ACTION_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usbManager.requestPermission(dev, pi)
            onLog("Aceite a permissão do USB na janela")
            return
        }
        startForegroundService(Intent(this, SyncService::class.java))
        onState(SyncBus.State.SEARCHING, "Conectando pelo cabo…")
        refreshChecklist()
    }

    // ───────────── Importar do cartão (Armazenamento em Massa) ─────────────

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var waitingCard = 0
    private var pendingUuid: String? = null

    private val treePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        val uuid = pendingUuid
        if (uri == null || uuid == null) { onLog("Acesso ao cartão cancelado"); refreshChecklist(); return@registerForActivityResult }
        if (!CardImporter.treeLooksRight(uri, uuid)) {
            dialog("Pasta errada", "Escolha a pasta DCIM do cartão da câmera (ou a raiz do cartão) e toque em \"Usar esta pasta\".")
            return@registerForActivityResult
        }
        CardImporter.saveTree(this, uuid, uri)
        onLog("Acesso ao cartão liberado ✓")
        importFlow()
    }

    /** Acha o cartão montado → garante acesso → decide o que importar → inicia. */
    private fun importFlow() {
        val card = CardImporter.mountedCards(this).firstOrNull()
        if (card == null) {
            if (usbManager.deviceList.isNotEmpty() && waitingCard < 25) {
                // O Android leva alguns segundos pra abrir o cartão depois de plugar
                if (waitingCard == 0) { onLog("Câmera plugada. Esperando o celular abrir o cartão…"); onState(SyncBus.State.SEARCHING, "Abrindo o cartão…") }
                waitingCard++
                ui.postDelayed({ importFlow() }, 1000)
                return
            }
            waitingCard = 0
            onState(SyncBus.State.OFF, "Desconectada")
            refreshChecklist()
            dialog("Cartão não encontrado",
                if (usbManager.deviceList.isEmpty())
                    "O celular não está vendo a câmera no cabo.\n\n• Use cabo USB-C ↔ Micro-USB que passe dados (OTG)\n• Câmera ligada, com cartão\n• Na câmera: MENU → Configuração (maleta) → Conexão USB → Armaz. Massa (ou Auto)"
                else
                    "A câmera está plugada, mas o celular não abriu o cartão.\n\n• Abra o app Arquivos do celular e veja se aparece um \"USB\"/pendrive\n• No Xiaomi: Configurações → Configurações adicionais → OTG (ligar, se existir)\n• Desplugue e plugue de novo\n\nAparelhos no cabo: " + usbManager.deviceList.values.joinToString { UsbCamera.describe(it) })
            return
        }
        waitingCard = 0
        val uuid = card.uuid!!
        onLog("Cartão aberto: ${CardImporter.label(card, this)}")
        val tree = CardImporter.savedTree(this, uuid)
        if (tree == null) {
            pendingUuid = uuid
            refreshChecklist()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Liberar acesso ao cartão")
                .setMessage("Só na primeira vez com este cartão: na próxima tela, abra a pasta DCIM, toque em \"Usar esta pasta\" e depois em \"Permitir\".")
                .setPositiveButton("Continuar") { _, _ -> treePicker.launch(CardImporter.permissionIntent(card)) }
                .setNegativeButton("Cancelar", null).show()
            return
        }
        refreshChecklist()
        if (ImportedStore(this).knowsCard(uuid)) { launchImport(tree, uuid, 0L); return }

        // Primeira vez com este cartão: pergunta o que fazer com as fotos que já estão nele
        onState(SyncBus.State.SEARCHING, "Contando fotos do cartão…")
        Thread {
            val all = runCatching { CardImporter.listPhotos(this, tree) }.getOrElse { e -> runOnUiThread { onLog("Erro ao ler o cartão: ${e.message}") }; return@Thread }
            val today = CardImporter.startOfToday()
            val nToday = all.count { it.modified >= today }
            runOnUiThread {
                onState(SyncBus.State.OFF, "Desconectada")
                val opts = arrayOf("Só as de hoje ($nToday)", "Todas (${all.size})", "Nenhuma agora — só as que eu tirar daqui pra frente")
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Encontrei ${all.size} fotos neste cartão")
                    .setItems(opts) { _, i ->
                        when (i) {
                            0 -> launchImport(tree, uuid, today)
                            1 -> launchImport(tree, uuid, 0L)
                            else -> { launchImport(tree, uuid, Long.MAX_VALUE) }
                        }
                    }.show()
            }
        }.start()
    }

    private fun launchImport(tree: android.net.Uri, uuid: String, since: Long) {
        startForegroundService(Intent(this, SyncService::class.java).setAction(SyncService.ACTION_IMPORT)
            .putExtra(SyncService.EXTRA_TREE, tree.toString()).putExtra(SyncService.EXTRA_UUID, uuid).putExtra(SyncService.EXTRA_SINCE, since))
        onState(SyncBus.State.SEARCHING, "Lendo o cartão…")
    }

    /** Lista do que falta, sempre visível. */
    private fun refreshChecklist() {
        fun line(ok: Boolean?, text: String) = (when (ok) { true -> "✅"; false -> "❌"; null -> "⏳" }) + "  " + text
        val ptp = UsbCamera.find(usbManager)
        val lines = if (ptp != null) listOf(
            line(true, "Câmera no cabo (modo PC Remoto)"),
            line(usbManager.hasPermission(ptp), "Permissão do USB"),
            line(SyncBus.state == SyncBus.State.CONNECTED, "Conectada e pronta pra fotografar"),
        ) else {
            val card = CardImporter.mountedCards(this).firstOrNull()
            val plugged = usbManager.deviceList.isNotEmpty() || card != null
            val access = card?.uuid?.let { CardImporter.savedTree(this, it) != null }
            listOf(
                line(plugged, if (plugged) "Câmera no cabo" else "Câmera no cabo — nada detectado"),
                line(if (card != null) true else if (plugged) null else false, "Cartão aberto pelo celular"),
                line(access, "Acesso à pasta DCIM"),
                line(if (SyncBus.state == SyncBus.State.SEARCHING) null else card != null && access == true, "Importação automática"),
            )
        }
        v<TextView>(R.id.checklist).text = lines.joinToString("\n")
    }

    private fun dialog(title: String, msg: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun bindSettings() {
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
            SyncBus.State.OFF -> R.color.muted to text.ifBlank { "Desconectada" }
            SyncBus.State.SEARCHING -> R.color.warn to text
            SyncBus.State.CONNECTED -> R.color.ok to "Conectada pelo cabo · pode fotografar"
            SyncBus.State.ERROR -> R.color.bad to text
        }
        v<View>(R.id.statusDot).backgroundTintList = ColorStateList.valueOf(color(c))
        v<TextView>(R.id.statusText).text = label
        v<MaterialButton>(R.id.startStop).apply {
            val on = state != SyncBus.State.OFF
            this.text = if (on) "Parar" else "Importar do cartão"
            backgroundTintList = ColorStateList.valueOf(color(if (on) R.color.surface2 else R.color.accent))
        }
        refreshChecklist()
    }

    override fun onLog(text: String) {
        if (logLines.firstOrNull() == text) return
        logLines.addFirst(text); if (logLines.size > 40) logLines.removeLast()
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
