package com.ronald.rimmodupdater

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.cos

/**
 * Kotak gaya HUD sci-fi: siku di keempat sudut, garis tepi tipis, label kecil,
 * garis ukur, dan lampu indikator. Semuanya berdenyut menyala–redup (glow).
 * Warna & kecepatan denyut mengikuti [mode].
 */
class HudBox(
    ctx: Context,
    private val tagTop: String,
    private val tagBottom: String? = null,
    mode: Mode = Mode.INFO,
) : FrameLayout(ctx) {

    /** INFO/UPDATE = kuning normal, OK = hijau lambat, DANGER = merah cepat + segitiga ⚠. */
    enum class Mode(val color: Int, val periodMs: Long, val triangle: Boolean) {
        INFO(Hx.AMBER, 2000, false),
        UPDATE(Hx.AMBER, 2000, false),
        OK(Hx.GREEN, 3000, false),
        DANGER(Hx.RED, 1200, true),
    }

    var mode: Mode = mode
        set(v) { field = v; invalidate() }

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    /** Jarak label di atas & bawah kotak. */
    private val topSpace = dp(13f)
    private val bottomSpace = if (tagBottom != null) dp(13f) else dp(7f)
    /** Jarak kiri-kanan supaya siku & cahayanya tidak terpotong tepi view. */
    private val side = dp(7f)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Hx.MONO
        textSize = dp(8f)
        letterSpacing = 0.12f
    }
    private val box = RectF()
    private val corners = Path()
    private val tri = Path()

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
        // segitiga peringatan di kanan atas
        val cx = box.right - dp(12f)
        val cy = box.top + dp(11f)
        tri.reset()
        tri.moveTo(cx, cy - dp(5f)); tri.lineTo(cx + dp(5.5f), cy + dp(4.5f)); tri.lineTo(cx - dp(5.5f), cy + dp(4.5f)); tri.close()
    }

    private fun withA(color: Int, a: Float) =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** Warna lebih gelap ↔ lebih terang dari warna dasar. */
    private fun shade(color: Int, g: Float): Int {
        fun ch(c: Int) = if (g < 0.5f) (c * (0.65f + 0.7f * g)).toInt()
        else (c + (255 - c) * (g - 0.5f) * 1.1f).toInt()
        return Color.rgb(ch(Color.red(color)).coerceIn(0, 255), ch(Color.green(color)).coerceIn(0, 255), ch(Color.blue(color)).coerceIn(0, 255))
    }

    /** Tingkat cahaya 0..1 dengan kecepatan sesuai mode (gelombang cosinus halus). */
    private fun level(period: Long): Float {
        val p = (SystemClock.uptimeMillis() % period).toFloat() / period
        return (0.5f - 0.5f * cos(p * 2f * Math.PI.toFloat()))
    }

    override fun onDraw(canvas: Canvas) {
        val m = mode
        val col = m.color
        val g = level(m.periodMs)
        val r = dp(6f)

        // isi kotak & garis tepi tipis ikut berpendar
        fill.color = withA(col, 0.02f + 0.07f * g)
        canvas.drawRoundRect(box, r, r, fill)
        line.strokeWidth = dp(1f)
        line.color = withA(col, 0.15f + 0.45f * g)
        canvas.drawRoundRect(box, r, r, line)

        // cahaya di belakang siku: beberapa lapis tebal-transparan (ringan, tanpa blur)
        line.strokeJoin = Paint.Join.MITER
        for (i in 3 downTo 1) {
            line.strokeWidth = dp(2f + i * 2.2f * (0.4f + g))
            line.color = withA(col, (0.10f + 0.22f * g) / i)
            canvas.drawPath(corners, line)
        }
        line.strokeWidth = dp(2f)
        line.color = shade(col, g)
        canvas.drawPath(corners, line)

        // garis ukur kecil di kanan bawah
        line.strokeWidth = dp(1f)
        line.color = withA(col, 0.3f + 0.5f * g)
        for (i in 0 until 8) {
            val x = box.right - dp(14f) - i * dp(6f)
            canvas.drawLine(x, box.bottom - dp(3f), x, box.bottom - dp(if (i % 2 == 1) 6f else 9f), line)
        }

        // lampu indikator kanan atas: bulat berkedip, atau segitiga ⚠ untuk bahaya
        val blink = level((m.periodMs / 2).coerceAtLeast(500))
        if (m.triangle) {
            line.strokeWidth = dp(1.3f)
            line.strokeJoin = Paint.Join.ROUND
            line.color = withA(col, 0.3f + 0.7f * (1f - blink))
            canvas.drawPath(tri, line)
            val cx = box.right - dp(12f)
            val cy = box.top + dp(11f)
            canvas.drawLine(cx, cy - dp(1.5f), cx, cy + dp(1.5f), line)
            fill.color = line.color
            canvas.drawCircle(cx, cy + dp(3f), dp(0.8f), fill)
        } else {
            line.color = withA(col, 0.2f + 0.8f * (1f - blink))
            canvas.drawCircle(box.right - dp(10f), box.top + dp(10f), dp(3f), line)
        }

        // label HUD
        text.color = withA(col, 0.45f + 0.55f * g)
        text.setShadowLayer(dp(4f) * g + 0.01f, 0f, 0f, withA(col, g))
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

    /** Satu pewaktu untuk semua kotak HUD; hanya kotak yang terlihat yang digambar ulang (±25 fps). */
    private object Pulse {
        private val boxes = mutableSetOf<HudBox>()
        private val tmp = android.graphics.Rect()
        private var last = 0L
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = SystemClock.uptimeMillis()
                if (now - last < 40) return@addUpdateListener
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
