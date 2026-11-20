package com.a6400sync

/**
 * Tradução dos códigos da a6400 (PC Remoto) para texto, conforme libgphoto2 e um dump real da a6400.
 * STEP = muda por passos (0x9207); PICK = escolhe da lista (0x9205); READ = só leitura.
 */
object CameraFormat {
    enum class Kind { STEP, PICK }
    data class Spec(val code: Int, val label: String, val kind: Kind, val minus: String = "−", val plus: String = "+")

    const val SHUTTER = 0xD20D
    const val FNUMBER = 0x5007
    const val ISO = 0xD21E
    const val EV = 0x5010
    const val WB = 0x5005
    const val FOCUS_MODE = 0x500A
    const val FOCUS_AREA = 0xD22C
    const val METERING = 0x500B
    const val DRIVE = 0x5013
    const val QUALITY = 0x5004
    const val SIZE = 0xD203
    const val ASPECT = 0xD211
    const val DRO = 0xD201
    const val SAVE_DEST = 0xD222
    const val PROGRAM = 0x500E
    const val BATTERY = 0xD218
    const val FOCUS_IND = 0xD213
    const val COLOR_TEMP = 0xD20F

    /** Ordem do painel. Passo +1: velocidade = mais rápida; abertura = f maior (mais fechada). */
    val specs = listOf(
        Spec(SHUTTER, "Velocidade", Kind.STEP, "Mais longa", "Mais rápida"),
        Spec(FNUMBER, "Abertura", Kind.STEP, "Mais aberta", "Mais fechada"),
        Spec(ISO, "ISO", Kind.STEP, "Menor", "Maior"),
        Spec(EV, "Compensação", Kind.STEP, "−", "+"),
        Spec(WB, "Balanço branco", Kind.PICK),
        Spec(FOCUS_MODE, "Foco", Kind.PICK),
        Spec(FOCUS_AREA, "Área de foco", Kind.PICK),
        Spec(METERING, "Medição", Kind.PICK),
        Spec(DRIVE, "Disparo", Kind.PICK),
        Spec(QUALITY, "Qualidade", Kind.PICK),
        Spec(SIZE, "Tamanho", Kind.PICK),
        Spec(ASPECT, "Proporção", Kind.PICK),
        Spec(DRO, "DRO", Kind.PICK),
        Spec(SAVE_DEST, "Salvar em", Kind.PICK),
    )

    private val wb = mapOf(2L to "Auto", 4L to "Luz do dia", 6L to "Tungstênio", 7L to "Flash",
        0x8001L to "Fluor. quente", 0x8002L to "Fluor. fria", 0x8003L to "Fluor. diurna branca", 0x8004L to "Fluor. luz do dia",
        0x8010L to "Nublado", 0x8011L to "Sombra", 0x8012L to "Temp. de cor", 0x8020L to "Personal. 1", 0x8021L to "Personal. 2",
        0x8022L to "Personal. 3", 0x8030L to "Subaquático")
    private val focus = mapOf(1L to "MF", 2L to "AF-S", 0x8004L to "AF-C", 0x8005L to "AF-A", 0x8006L to "DMF")
    private val focusArea = mapOf(1L to "Ampla", 2L to "Zona", 3L to "Central", 0x101L to "Spot P", 0x102L to "Spot M",
        0x103L to "Spot G", 0x104L to "Spot expandido")
    private val metering = mapOf(0x8001L to "Multi", 0x8002L to "Central", 0x8003L to "Spot", 0x8004L to "Spot P",
        0x8005L to "Tela inteira", 0x8006L to "Realce")
    private val drive = mapOf(1L to "Único", 2L to "Contínuo Hi", 0x8010L to "Contínuo Hi+", 0x8015L to "Contínuo Mid",
        0x8012L to "Contínuo Lo", 0x8005L to "Timer 2 s", 0x8003L to "Timer 5 s", 0x8004L to "Timer 10 s")
    private val quality = mapOf(2L to "JPEG Padrão", 3L to "JPEG Fina", 4L to "JPEG Extra fina", 0x10L to "RAW",
        0x12L to "RAW+JPEG Padrão", 0x13L to "RAW+JPEG Fina", 0x14L to "RAW+JPEG Extra fina")
    private val size = mapOf(1L to "G (24 MP)", 2L to "M", 3L to "P")
    private val aspect = mapOf(1L to "3:2", 2L to "16:9", 3L to "4:3", 4L to "1:1")
    private val dro = mapOf(1L to "Desligado", 0x1FL to "Auto", 0x11L to "Nível 1", 0x12L to "Nível 2", 0x13L to "Nível 3",
        0x14L to "Nível 4", 0x15L to "Nível 5")
    private val saveDest = mapOf(1L to "Só celular", 0x11L to "Celular + cartão")
    private val program = mapOf(1L to "M · Manual", 2L to "P · Programa", 3L to "A · Prioridade de abertura",
        4L to "S · Prioridade de velocidade", 0x8000L to "Auto inteligente", 0x8001L to "Auto superior",
        0x8041L to "Panorama", 0x8050L to "Filme", 0x8051L to "Filme", 0x8052L to "Filme", 0x8053L to "Filme")
    private val focusInd = mapOf(1L to "", 2L to "● Em foco", 3L to "○ Sem foco", 5L to "Rastreando", 6L to "● Rastreando (foco)", 7L to "Rastreando")

    fun format(code: Int, v: Long?): String {
        if (v == null) return "—"
        return when (code) {
            SHUTTER -> shutter(v)
            FNUMBER -> if (v <= 0) "—" else "f/" + trim(v / 100.0)
            ISO -> iso(v)
            EV -> if (v == 0L) "±0" else "%+.1f".format(v / 1000.0)
            WB -> wb[v] ?: hex(v)
            FOCUS_MODE -> focus[v] ?: hex(v)
            FOCUS_AREA -> focusArea[v] ?: hex(v)
            METERING -> metering[v] ?: hex(v)
            DRIVE -> drive[v] ?: if (v >= 0x8300) "Bracketing" else hex(v)
            QUALITY -> quality[v] ?: hex(v)
            SIZE -> size[v] ?: hex(v)
            ASPECT -> aspect[v] ?: hex(v)
            DRO -> dro[v] ?: hex(v)
            SAVE_DEST -> saveDest[v] ?: hex(v)
            PROGRAM -> program[v] ?: if (v in 0x8011..0x8018) "Cena" else hex(v)
            BATTERY -> if (v < 0) "?" else "$v%"
            FOCUS_IND -> focusInd[v] ?: ""
            COLOR_TEMP -> "${v}K"
            else -> hex(v)
        }
    }

    /** Valores conhecidos (quando a câmera não manda a lista). */
    fun knownValues(code: Int): List<Long> = when (code) {
        WB -> wb.keys; FOCUS_MODE -> focus.keys; FOCUS_AREA -> focusArea.keys; METERING -> metering.keys
        DRIVE -> drive.keys; QUALITY -> quality.keys; SIZE -> size.keys; ASPECT -> aspect.keys; DRO -> dro.keys
        SAVE_DEST -> saveDest.keys; else -> emptySet()
    }.toList()

    private fun shutter(v: Long): String {
        if (v == 0L) return "Bulb"
        val num = (v shr 16) and 0xFFFF; val den = v and 0xFFFF
        if (den == 0L) return "—"
        return when {
            num == 1L && den > 1 -> "1/$den"
            den == 1L -> "$num\""
            else -> trim(num.toDouble() / den) + "\""
        }
    }

    private fun iso(v: Long): String {
        val low = v and 0xFFFFFF
        val base = if (low == 0xFFFFFFL) "Auto" else "$low"
        return when ((v shr 24) and 0xFF) { 1L -> "$base NR"; 2L -> "$base NR+"; else -> base }
    }

    private fun trim(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else "%.1f".format(d)
    private fun hex(v: Long) = "0x%X".format(v)
}
