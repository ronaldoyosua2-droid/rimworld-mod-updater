package com.ronald.rimmodupdater

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.widget.TextView

/** Game yang didukung. */
enum class Game(val key: String, val appId: Int, val title: String, val short: String) {
    RIMWORLD("rimworld", 294100, "RimWorld", "RW"),
    ZOMBOID("zomboid", 108600, "Project Zomboid", "PZ");

    companion object {
        fun of(key: String?): Game = entries.firstOrNull { it.key == key } ?: RIMWORLD
    }
}

/** Palet & helper tema "hacker". */
object Hx {
    val BG = Color.parseColor("#030806")
    val CARD = Color.parseColor("#06110B")
    val GREEN = Color.parseColor("#39FF88")
    val DIM = Color.parseColor("#1F9E57")
    val DARK = Color.parseColor("#0F3D24")
    val BRIGHT = Color.parseColor("#C9FFE0")
    val AMBER = Color.parseColor("#FFB84D")
    val RED = Color.parseColor("#FF5F56")
    val ON_GREEN = Color.parseColor("#02130A")
    val MONO: Typeface = Typeface.MONOSPACE
    val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    /** Tulisan menyala, ikut berdenyut redup-terang (lihat GlowPulse). */
    fun glow(tv: TextView, color: Int = GREEN, radius: Float = 10f, pulse: Boolean = false) {
        tv.setShadowLayer(radius, 0f, 0f, Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)))
        if (pulse) GlowPulse.register(tv, color, radius)
    }

    fun states(enabled: Int, disabled: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(disabled, enabled),
    )
}
