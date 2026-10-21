package com.ronald.rimmodupdater

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout

/**
 * Kotak gaya HUD sci-fi: siku kuning di keempat sudut, garis tepi tipis, label kecil,
 * garis ukur, dan lampu indikator. Semuanya berdenyut menyala–redup (glow) pelan.
 */
class HudBox(ctx: Context, private val tagTop: String, private val tagBottom: String? = null) : FrameLayout(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    /** Jarak label di atas & bawah kotak. */
    private val topSpace = dp(13f)
    private val bottomSpace = if (tagBottom != null) dp(13f) else dp(7f)
    /** Jarak kiri-kanan supaya siku & cahayanya tidak terpotong tepi view. */
    private val side = dp(7f)

    private val amber = Hx.AMBER
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Hx.MONO
        textSize = dp(8f)
        letterSpacing = 0.12f
    }
    private val box = RectF()
    private val corners = Path()

    init {
        setWillNotDraw(false)
        setPadding((side + dp(11f)).toInt(), (topSpace + dp(7f)).toInt(), (side + dp(20f)).toInt(), (bottomSpace + dp(7f)).toInt())
        clipChildren = false
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        box.set(side, topSpace, w - side, h - bottomSpace)
        val c = dp(12f)
        corners.reset()
        corners.moveTo(box.left, box.top + c); corners.lineTo(box.left, box.top); corners.lineTo(box.left + c, box.top)
        corners.moveTo(box.right - c, box.top); corners.lineTo(box.right, box.top); corners.lineTo(box.right, box.top + c)
        corners.moveTo(box.right, box.bottom - c); corners.lineTo(box.right, box.bottom); corners.lineTo(box.right - c, box.bottom)
        corners.moveTo(box.left + c, box.bottom); corners.lineTo(box.left, box.bottom); corners.lineTo(box.left, box.bottom - c)
    }

    private fun amberA(a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(amber), Color.green(amber), Color.blue(amber))

    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    override fun onDraw(canvas: Canvas) {
        val g = Pulse.level // 0..1, berdenyut
        val r = dp(6f)

        // isi kotak & garis tepi tipis ikut berpendar
        fill.color = amberA(0.02f + 0.07f * g)
        canvas.drawRoundRect(box, r, r, fill)
        line.strokeWidth = dp(1f)
        line.color = amberA(0.15f + 0.45f * g)
        canvas.drawRoundRect(box, r, r, line)

        // cahaya di belakang siku: beberapa lapis tebal-transparan (ringan, tanpa blur)
        for (i in 3 downTo 1) {
            line.strokeWidth = dp(2f + i * 2.2f * (0.4f + g))
            line.color = amberA((0.10f + 0.22f * g) / i)
            canvas.drawPath(corners, line)
        }
        line.strokeWidth = dp(2f)
        line.color = mix(Color.parseColor("#C98A2E"), Color.parseColor("#FFE2A8"), g)
        canvas.drawPath(corners, line)

        // garis ukur kecil di kanan bawah
        line.strokeWidth = dp(1f)
        line.color = amberA(0.3f + 0.5f * g)
        for (i in 0 until 8) {
            val x = box.right - dp(14f) - i * dp(6f)
            canvas.drawLine(x, box.bottom - dp(3f), x, box.bottom - dp(if (i % 2 == 1) 6f else 9f), line)
        }

        // lampu indikator kanan atas (kedip lebih cepat)
        val blink = ((SystemClock.uptimeMillis() % 1000L) / 1000f).let { if (it < 0.5f) 1f - it * 1.6f else 0.2f + (it - 0.5f) * 1.6f }
        line.color = amberA(blink)
        canvas.drawCircle(box.right - dp(10f), box.top + dp(10f), dp(3f), line)

        // label HUD
        text.color = amberA(0.45f + 0.55f * g)
        text.setShadowLayer(dp(4f) * g, 0f, 0f, amberA(g))
        canvas.drawText(tagTop, box.left + dp(12f), topSpace - dp(4f), text)
        tagBottom?.let {
            val w = text.measureText(it)
            canvas.drawText(it, box.right - dp(12f) - w, box.bottom + dp(10f), text)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Pulse.add(this)
    }

    override fun onDetachedFromWindow() {
        Pulse.remove(this)
        super.onDetachedFromWindow()
    }

    /** Satu animator untuk semua kotak HUD; hanya kotak yang terlihat yang digambar ulang. */
    private object Pulse {
        var level = 0f
        private val boxes = mutableSetOf<HudBox>()
        private val tmp = android.graphics.Rect()
        private var last = 0L
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                level = it.animatedValue as Float
                val now = SystemClock.uptimeMillis()
                if (now - last < 40) return@addUpdateListener // ±25 fps cukup untuk denyut pelan
                last = now
                boxes.forEach { b -> if (b.isShown && b.getGlobalVisibleRect(tmp)) b.invalidate() }
            }
        }

        fun add(b: HudBox) {
            boxes += b
            if (!anim.isStarted) anim.start()
        }

        fun remove(b: HudBox) {
            boxes -= b
            if (boxes.isEmpty()) anim.cancel()
        }
    }
}
