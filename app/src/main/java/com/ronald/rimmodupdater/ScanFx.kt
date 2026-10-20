package com.ronald.rimmodupdater

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.random.Random

/**
 * Bungkus tampilan dengan efek "render": garis hijau tipis turun dari atas ke bawah,
 * dan isi hanya terlihat di atas garis itu (seperti layar sedang menggambar tulisan).
 */
class ScanReveal(ctx: Context) : FrameLayout(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private var progress = 1f
    private var animator: ValueAnimator? = null
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Hx.GREEN }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        clipChildren = true
    }

    /** Muncul perlahan (memudar masuk), tanpa garis turun. */
    fun reveal(delayMs: Long = 0, durationMs: Long = 420) {
        animator?.cancel()
        alpha = 0f
        animate().alpha(1f).setStartDelay(delayMs).setDuration(durationMs).start()
    }
}

/**
 * Lapisan di atas seluruh layar: garis-garis halus ala monitor lama (diam). Tidak menangkap sentuhan.
 */
class CrtOverlay(ctx: Context) : View(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private val scanPaint = Paint().apply { color = 0x2E000000 }
    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val step = 3 * density
        var y = 0f
        while (y < height) {
            canvas.drawRect(0f, y, width.toFloat(), y + density * 0.8f, scanPaint)
            y += step
        }
    }
}

/** Efek teks "dekripsi": karakter acak yang perlahan berubah jadi teks asli. */
object Decrypt {
    private const val GLYPHS = "!<>-_\\/[]{}=+*^?#01ABCDEF"

    fun run(view: android.widget.TextView, target: String, durationMs: Long = 700, onEnd: (() -> Unit)? = null) {
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener {
                val f = it.animatedValue as Float
                val revealed = (target.length * f).toInt()
                val sb = StringBuilder()
                target.forEachIndexed { i, c ->
                    sb.append(if (i < revealed || c == ' ') c else GLYPHS[Random.nextInt(GLYPHS.length)])
                }
                view.text = sb
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.text = target
                    onEnd?.invoke()
                }
            })
        }
        anim.start()
    }
}

/**
 * Cahaya tulisan yang berdenyut redup-terang pelan (seperti monitor tabung),
 * dengan kedip kecil sesekali. Semua tulisan yang "menyala" ikut satu irama.
 */
object GlowPulse {
    private class Entry(val ref: java.lang.ref.WeakReference<android.widget.TextView>, val color: Int, val radius: Float)

    private val items = mutableListOf<Entry>()
    private var flicker = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { tick(it.animatedValue as Float) }
    }

    fun register(tv: android.widget.TextView, color: Int, radius: Float) {
        items.removeAll { it.ref.get() == null || it.ref.get() === tv }
        items += Entry(java.lang.ref.WeakReference(tv), color, radius)
        if (!anim.isStarted) anim.start()
    }

    private fun tick(v: Float) {
        // kedip kecil acak, cepat pulih
        if (flicker <= 0f && Random.nextFloat() < 0.012f) flicker = 1f
        val dip = if (flicker > 0f) { flicker -= 0.25f; 0.45f } else 0f
        val level = (0.35f + 0.65f * v - dip).coerceIn(0.1f, 1f)
        val it = items.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val tv = e.ref.get()
            if (tv == null) { it.remove(); continue }
            if (!tv.isAttachedToWindow) continue
            val a = (60 + 170 * level).toInt()
            tv.setShadowLayer(
                e.radius * (0.4f + 0.9f * level), 0f, 0f,
                android.graphics.Color.argb(a, android.graphics.Color.red(e.color), android.graphics.Color.green(e.color), android.graphics.Color.blue(e.color)),
            )
            tv.alpha = 0.78f + 0.22f * level
        }
    }
}
