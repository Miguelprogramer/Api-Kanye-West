package com.a6400sync

import android.content.Context

/** Ajustes salvos no celular e lidos a cada foto (mudança vale na hora). */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("a6400", Context.MODE_PRIVATE)
    var autoEdit: Boolean get() = p.getBoolean("edit", true); set(v) = p.edit().putBoolean("edit", v).apply()
    var keepDiscarded: Boolean get() = p.getBoolean("discard", true); set(v) = p.edit().putBoolean("discard", v).apply()
    var filterDuplicates: Boolean get() = p.getBoolean("dup", true); set(v) = p.edit().putBoolean("dup", v).apply()
    /** 0 = tolerante, 100 = exigente */
    var blurStrictness: Float get() = p.getFloat("blur", 50f); set(v) = p.edit().putFloat("blur", v).apply()
    /** 0..40 = +0%..+40% de saturação */
    var saturationBoost: Float get() = p.getFloat("sat", 12f); set(v) = p.edit().putFloat("sat", v).apply()
    var style: String get() = p.getString("style", "natural")!!; set(v) = p.edit().putString("style", v).apply()

    /** ID fixo do celular: a câmera reconhece o mesmo aparelho entre conexões. */
    val deviceId: java.util.UUID get() = java.util.UUID.fromString(
        p.getString("guid", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("guid", it).apply() })

    fun applyToEngines() {
        Triage.blurThreshold = 10.0 + blurStrictness * 1.4
        Triage.checkDuplicates = filterDuplicates
        AutoEdit.saturation = 1f + saturationBoost / 100f
        AutoEdit.style = style
    }
}
