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

    /** Jalankan efek render. */
    fun reveal(delayMs: Long = 0, durationMs: Long = 420) {
        animator?.cancel()
        progress = 0f
        invalidate()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delayMs
            duration = durationMs
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (progress >= 1f) {
            super.dispatchDraw(canvas)
            return
        }
        val y = height * progress
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), y)
        super.dispatchDraw(canvas)
        canvas.restore()
        // cahaya di belakang garis
        val glowH = 14 * density
        glowPaint.shader = LinearGradient(0f, y - glowH, 0f, y, 0x0039FF88, 0x5539FF88, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, y - glowH, width.toFloat(), y, glowPaint)
        // garis tipis
        canvas.drawRect(0f, y - 1f * density, width.toFloat(), y + 0.5f * density, linePaint)
    }
}

/**
 * Lapisan di atas seluruh layar: garis-garis halus ala monitor lama + garis sapuan samar
 * yang sesekali turun dari atas ke bawah. Tidak menangkap sentuhan.
 */
class CrtOverlay(ctx: Context) : View(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private val scanPaint = Paint().apply { color = 0x2E000000 }
    private val sweepPaint = Paint()
    private var sweepY = -1f
    private val sweep = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        startDelay = 1500
        addUpdateListener {
            // jeda di antara sapuan: hanya tampil di 60% awal siklus
            val f = it.animatedValue as Float
            sweepY = if (f < 0.6f) (f / 0.6f) * height else -1f
            invalidate()
        }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sweep.start()
    }

    override fun onDetachedFromWindow() {
        sweep.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val step = 3 * density
        var y = 0f
        while (y < height) {
            canvas.drawRect(0f, y, width.toFloat(), y + density * 0.8f, scanPaint)
            y += step
        }
        if (sweepY >= 0) {
            val h = 70 * density
            sweepPaint.shader = LinearGradient(0f, sweepY - h, 0f, sweepY, 0x0039FF88, 0x1439FF88, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, sweepY - h, width.toFloat(), sweepY, sweepPaint)
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
