package com.ronald.rimmodupdater

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kartu progres gaya Kuitansi + panel terminal hijau.
 * Semua fungsi aman dipanggil dari thread mana pun; tampilan diperbarui paling cepat tiap ~80 ms.
 */
class ProgressPanel(private val ctx: Context) {

    enum class ItemState { PENDING, CURRENT, DONE, FAILED }

    private val density = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * density).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // --- warna
    private val cText = Color.parseColor("#C9FFE0")
    private val cMuted = Color.parseColor("#1F9E57")
    private val tGreen = Color.parseColor("#7DFFA8")
    private val tTime = Color.parseColor("#3F7A55")
    private val tTag = Color.parseColor("#B6FFCF")
    private val tOk = Color.parseColor("#3DFF8B")
    private val tErr = Color.parseColor("#FF7A6E")
    private val okGreen = Color.parseColor("#39FF88")
    private val failRed = Color.parseColor("#E0605A")

    // --- state (dibaca di main thread saat render)
    @Volatile private var caption = ""
    @Volatile private var title = ""
    @Volatile private var badge = ""
    @Volatile private var fraction: Float? = null
    @Volatile private var stats: List<Pair<String, String>> = emptyList()
    @Volatile private var detail = ""
    @Volatile private var items: List<Pair<String, ItemState>> = emptyList()
    private val lines = ArrayDeque<CharSequence>()
    private val maxLines = 8
    @Volatile private var dirty = false
    @Volatile private var scheduled = false

    // --- bingkai kartu yang berdenyut selama proses berjalan
    private val cardBg = GradientDrawable().apply {
        setColor(Color.parseColor("#06110B"))
        setStroke((1 * density).toInt(), Color.parseColor("#0F3D24"))
        cornerRadius = 8 * density
    }
    private var pulse: android.animation.ValueAnimator? = null

    private fun startPulse() {
        pulse?.cancel()
        pulse = android.animation.ValueAnimator.ofArgb(Color.parseColor("#0F3D24"), Color.parseColor("#39FF88")).apply {
            duration = 900
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            addUpdateListener { cardBg.setStroke((1 * density).toInt(), it.animatedValue as Int) }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        cardBg.setStroke((1 * density).toInt(), Color.parseColor("#39FF88"))
    }

    // --- views
    val view: LinearLayout
    private val captionView: TextView
    private val titleView: TextView
    private val badgeView: TextView
    private val pctView: TextView
    private val statValues = mutableListOf<TextView>()
    private val statLabels = mutableListOf<TextView>()
    private val statCells = mutableListOf<View>()
    private val bar: LinearProgressIndicator
    private val detailView: TextView
    private val termTitle: TextView
    private val termText: TextView
    private val listView: TextView

    init {
        view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(12))
            background = cardBg
            visibility = View.GONE
        }
        captionView = TextView(ctx).apply {
            textSize = 10.5f
            letterSpacing = 0.06f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cMuted)
        }
        view.addView(captionView)

        titleView = TextView(ctx).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        badgeView = TextView(ctx).apply {
            textSize = 10.5f
            letterSpacing = 0.04f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#39FF88"))
            setPadding(px(8), px(2), px(8), px(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F3D24"))
                cornerRadius = px(20).toFloat()
            }
        }
        pctView = TextView(ctx).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
            gravity = Gravity.END
        }
        view.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // judul dibatasi lebarnya supaya badge & persen tetap terlihat
            titleView.maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.55f).toInt()
            addView(titleView)
            addView(badgeView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = px(8)
            })
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(pctView, LinearLayout.LayoutParams(px(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(10), 0, px(8))
        }
        repeat(4) {
            val v = TextView(ctx).apply {
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cText)
                maxLines = 1
            }
            val l = TextView(ctx).apply {
                textSize = 11f
                setTextColor(cMuted)
                maxLines = 1
            }
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(v)
                addView(l)
            }
            statValues += v
            statLabels += l
            statCells += cell
            statsRow.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        view.addView(statsRow)

        bar = LinearProgressIndicator(ctx).apply {
            max = 1000
            isIndeterminate = true
            trackThickness = px(7)
            trackCornerRadius = px(4)
            setIndicatorColor(Color.parseColor("#39FF88"))
            trackColor = Color.parseColor("#0F3D24")
        }
        view.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        detailView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(cMuted)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, px(6), 0, 0)
        }
        view.addView(detailView)

        // --- terminal
        val mono = Typeface.MONOSPACE
        val dots = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(5), px(8), px(5))
            setBackgroundColor(Color.parseColor("#0E1512"))
            listOf("#E0605A", "#E2B34A", "#5FBF6A").forEach { c ->
                addView(View(ctx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(c))
                    }
                }, LinearLayout.LayoutParams(px(7), px(7)).apply { marginEnd = px(5) })
            }
        }
        termTitle = TextView(ctx).apply {
            textSize = 10.5f
            typeface = mono
            setTextColor(Color.parseColor("#5C8A6C"))
            setPadding(px(4), 0, 0, 0)
            text = "steam://depot/${SteamManager.appId}"
        }
        dots.addView(termTitle)
        termText = TextView(ctx).apply {
            textSize = 10.4f
            typeface = mono
            setTextColor(tGreen)
            setLineSpacing(0f, 1.25f)
            setPadding(px(8), px(7), px(8), px(7))
            setShadowLayer(6f * density, 0f, 0f, Color.argb(90, 80, 255, 150))
            minLines = this@ProgressPanel.maxLines
            maxLines = this@ProgressPanel.maxLines
        }
        val term = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#07090A"))
                setStroke(px(1), Color.parseColor("#1F3B2B"))
                cornerRadius = px(10).toFloat()
            }
            clipToOutline = true
            addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#1F3B2B")) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)))
            // geser ke kanan/kiri untuk membaca baris panjang
            addView(android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = true
                isFillViewport = true
                addView(termText, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        view.addView(term, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(10)
        })

        listView = TextView(ctx).apply {
            textSize = 12f
            setTextColor(cMuted)
            setLineSpacing(px(3).toFloat(), 1f)
            setPadding(0, px(8), 0, 0)
        }
        view.addView(listView)
    }

    // ------------------------------------------------------------------ API

    fun start(caption: String, title: String, badge: String) {
        synchronized(lines) { lines.clear() }
        this.caption = caption
        this.title = title
        this.badge = badge
        fraction = null
        stats = emptyList()
        detail = ""
        items = emptyList()
        main.post {
            termTitle.text = "steam://depot/${SteamManager.appId}"
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.translationY = -12 * density
                view.animate().alpha(1f).translationY(0f).setDuration(260).start()
            }
            startPulse()
        }
        invalidate()
    }

    /** Proses selesai: bingkai berhenti berdenyut (tetap terlihat sebentar). */
    fun finish() = main.post { stopPulse() }

    fun hide() = main.post {
        stopPulse()
        view.animate().alpha(0f).setDuration(220).withEndAction {
            view.visibility = View.GONE
            view.alpha = 1f
        }.start()
    }

    fun caption(s: String) { caption = s; invalidate() }
    fun title(s: String) { title = s; invalidate() }
    fun badge(s: String) { badge = s; invalidate() }
    fun detail(s: String) { detail = s; invalidate() }
    fun fraction(f: Float?) { fraction = f?.coerceIn(0f, 1f); invalidate() }
    fun stats(vararg s: Pair<String, String>) { stats = s.toList(); invalidate() }
    fun items(list: List<Pair<String, ItemState>>) { items = list; invalidate() }

    /** Tambah baris ke terminal. [ok] = teks hijau terang di akhir baris (misal "OK", "✓"). */
    fun log(tag: String, msg: String, ok: String? = null, error: Boolean = false) {
        val sb = SpannableStringBuilder()
        fun add(text: String, color: Int, bold: Boolean = false) {
            val st = sb.length
            sb.append(text)
            sb.setSpan(ForegroundColorSpan(color), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        add(timeFmt.format(Date()) + " ", tTime)
        add(tag.padEnd(8) + " ", if (error) tErr else tTag, bold = true)
        add(msg, if (error) tErr else tGreen)
        if (ok != null) add(" $ok", tOk)
        synchronized(lines) {
            lines.addLast(sb)
            while (lines.size > maxLines) lines.removeFirst()
        }
        invalidate()
    }

    // ------------------------------------------------------------------ render

    private fun invalidate() {
        dirty = true
        if (scheduled) return
        scheduled = true
        main.postDelayed({
            scheduled = false
            if (dirty) {
                dirty = false
                render()
            }
        }, 80)
    }

    private fun render() {
        captionView.text = caption
        captionView.visibility = if (caption.isBlank()) View.GONE else View.VISIBLE
        titleView.text = title
        badgeView.text = badge
        badgeView.visibility = if (badge.isBlank()) View.GONE else View.VISIBLE

        val f = fraction
        if (f == null) {
            if (!bar.isIndeterminate) {
                bar.visibility = View.INVISIBLE
                bar.isIndeterminate = true
                bar.visibility = View.VISIBLE
            }
            pctView.text = ""
        } else {
            if (bar.isIndeterminate) {
                bar.visibility = View.INVISIBLE
                bar.isIndeterminate = false
                bar.visibility = View.VISIBLE
            }
            bar.setProgressCompat((f * 1000).toInt(), true)
            pctView.text = "${(f * 100).toInt()}%"
        }

        val s = stats
        statCells.forEachIndexed { i, cell ->
            if (i < s.size) {
                cell.visibility = View.VISIBLE
                statValues[i].text = s[i].first
                statLabels[i].text = s[i].second
            } else {
                cell.visibility = View.INVISIBLE
            }
        }
        (statCells.first().parent as View).visibility = if (s.isEmpty()) View.GONE else View.VISIBLE

        detailView.text = detail
        detailView.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE

        val term = SpannableStringBuilder()
        synchronized(lines) {
            lines.forEachIndexed { i, l ->
                if (i > 0) term.append('\n')
                term.append(l)
            }
        }
        val st = term.length
        term.append("█")
        term.setSpan(ForegroundColorSpan(tGreen), st, term.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        termText.text = term

        val list = items
        if (list.isEmpty()) {
            listView.visibility = View.GONE
        } else {
            listView.visibility = View.VISIBLE
            val sb = SpannableStringBuilder()
            list.forEachIndexed { i, (name, state) ->
                if (i > 0) sb.append("    ")
                val start = sb.length
                when (state) {
                    ItemState.DONE -> {
                        sb.append("✓ $name")
                        sb.setSpan(ForegroundColorSpan(okGreen), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    ItemState.CURRENT -> {
                        sb.append("● $name")
                        sb.setSpan(ForegroundColorSpan(cText), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    ItemState.FAILED -> {
                        sb.append("✗ $name")
                        sb.setSpan(ForegroundColorSpan(failRed), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    ItemState.PENDING -> sb.append("○ $name")
                }
            }
            listView.text = sb
        }
    }
}
