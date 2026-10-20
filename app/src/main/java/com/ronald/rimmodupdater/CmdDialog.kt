package com.ronald.rimmodupdater

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pop-up bergaya jendela terminal (CMD). API-nya sengaja mirip AlertDialog.Builder
 * supaya semua dialog di aplikasi bisa memakainya.
 */
class CmdDialog(private val ctx: Context) {

    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var view: View? = null
    private var items: Array<out CharSequence>? = null
    private var itemsListener: ((DialogInterface, Int) -> Unit)? = null
    private var single: Array<out CharSequence>? = null
    private var singleChecked = -1
    private var singleListener: ((DialogInterface, Int) -> Unit)? = null
    private var cancelable = true
    private var onDismiss: ((DialogInterface) -> Unit)? = null
    private var pos: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
    private var neg: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
    private var neu: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null

    fun setTitle(t: CharSequence?) = apply { title = t }
    fun setMessage(m: CharSequence?) = apply { message = m }
    fun setView(v: View?) = apply { view = v }
    fun setItems(list: Array<out CharSequence>, l: (DialogInterface, Int) -> Unit) = apply { items = list; itemsListener = l }
    fun setSingleChoiceItems(list: Array<out CharSequence>, checked: Int, l: (DialogInterface, Int) -> Unit) =
        apply { single = list; singleChecked = checked; singleListener = l }
    fun setCancelable(c: Boolean) = apply { cancelable = c }
    fun setOnDismissListener(l: (DialogInterface) -> Unit) = apply { onDismiss = l }
    fun setPositiveButton(text: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { pos = text to l }
    fun setNegativeButton(text: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { neg = text to l }
    fun setNeutralButton(text: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { neu = text to l }

    private fun tv(text: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) Hx.MONO_BOLD else Hx.MONO
        setLineSpacing(px(3).toFloat(), 1f)
    }

    private fun box(stroke: Int, fill: Int, radius: Int = 4) = GradientDrawable().apply {
        cornerRadius = px(radius).toFloat()
        setColor(fill)
        setStroke(px(1), stroke)
    }

    /** Warnai EditText/TextView di dalam view kustom supaya ikut tema terminal. */
    private fun theme(v: View) {
        when (v) {
            is EditText -> {
                v.setTextColor(Hx.BRIGHT)
                v.setHintTextColor(Hx.DIM)
                v.typeface = Hx.MONO
                v.textSize = 14f
                v.background = box(Hx.GREEN, Hx.CARD)
                v.setPadding(px(10), px(10), px(10), px(10))
                (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.topMargin = px(6); it.bottomMargin = px(6) }
                    ?: run { v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6); bottomMargin = px(6) } }
                try { v.textCursorDrawable = ColorDrawable(Hx.GREEN).apply { setBounds(0, 0, px(8), 0) } } catch (_: Throwable) {}
            }
            is TextView -> {
                v.setTextColor(Hx.GREEN)
                v.typeface = Hx.MONO
            }
            is ViewGroup -> {
                // padding samping bawaan dialog lama tidak perlu (isi sudah diberi jarak)
                if (v.paddingLeft >= px(16)) v.setPadding(0, v.paddingTop, 0, v.paddingBottom)
                for (i in 0 until v.childCount) theme(v.getChildAt(i))
            }
        }
    }

    /** Wadah yang tingginya dibatasi (isi panjang bisa digulir). */
    private class MaxHeight(ctx: Context, val maxH: Int) : FrameLayout(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }

    fun show(): Dialog {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setCancelable(cancelable)
        onDismiss?.let { l -> d.setOnDismissListener { l(it) } }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = box(Hx.GREEN, Color.parseColor("#020604"), 6)
            elevation = px(12).toFloat()
        }

        // bilah jendela: ● ● ●  root@steamod:~
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(8), px(12), px(8))
            listOf("#E0605A", "#E2B34A", "#5FBF6A").forEach { c ->
                addView(View(ctx).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(c)) }
                }, LinearLayout.LayoutParams(px(9), px(9)).apply { rightMargin = px(6) })
            }
            addView(tv("  root@steamod:~", 11f, Hx.DIM))
        }
        root.addView(bar)
        root.addView(View(ctx).apply { setBackgroundColor(Hx.DARK) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)))

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(4))
        }

        title?.let {
            val sb = SpannableStringBuilder("$ ")
            sb.setSpan(ForegroundColorSpan(Hx.DIM), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(it)
            body.addView(tv(sb, 14f, Hx.BRIGHT, bold = true).apply { Hx.glow(this, Hx.GREEN, 8f) })
        }
        message?.let { m ->
            val lines = m.toString().split('\n').joinToString("\n") { l -> if (l.isBlank()) "" else "> $l" }
            body.addView(tv(lines, 12.5f, Hx.DIM).apply { setPadding(0, px(6), 0, 0) })
        }

        fun option(i: Int, text: CharSequence, selected: Boolean, onClick: () -> Unit) =
            tv(text, 13f, if (selected) Hx.BRIGHT else Hx.GREEN, bold = selected).apply {
                background = if (selected) box(Hx.GREEN, Hx.DARK) else box(Hx.DARK, Color.TRANSPARENT)
                setPadding(px(10), px(9), px(10), px(9))
                isClickable = true
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x4439FF88), null, ColorDrawable(Color.WHITE))
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) }
            }

        items?.let { list ->
            body.addView(tv("select action:", 12f, Hx.DIM).apply { setPadding(0, px(8), 0, 0) })
            list.forEachIndexed { i, s ->
                val label = s.toString().removePrefix("> ")
                body.addView(option(i, "[${i + 1}] $label", false) { d.dismiss(); itemsListener?.invoke(d, i) })
            }
        }
        single?.let { list ->
            list.forEachIndexed { i, s ->
                body.addView(option(i, (if (i == singleChecked) "(•) " else "( ) ") + s, i == singleChecked) { singleListener?.invoke(d, i) })
            }
        }
        view?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            theme(v)
            body.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        }
        if (items != null || single != null) body.addView(tv("> █", 12f, Hx.GREEN).apply { setPadding(0, px(8), 0, 0) })

        val screenH = ctx.resources.displayMetrics.heightPixels
        val holder = MaxHeight(ctx, (screenH * 0.62).toInt())
        // isi panjang digulir; kecuali view-nya sendiri sudah bisa digulir (mis. log)
        if (view is ScrollView) holder.addView(body) else holder.addView(ScrollView(ctx).apply { addView(body) })
        root.addView(holder)

        // tombol: [netral] [batal] [OK hijau penuh]
        val buttons = listOfNotNull(neu?.let { Triple(it, DialogInterface.BUTTON_NEUTRAL, false) },
            neg?.let { Triple(it, DialogInterface.BUTTON_NEGATIVE, false) },
            pos?.let { Triple(it, DialogInterface.BUTTON_POSITIVE, true) })
        if (buttons.isNotEmpty()) {
            val stack = buttons.sumOf { it.first.first.length } > 26
            val row = LinearLayout(ctx).apply {
                orientation = if (stack) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(px(14), px(8), px(14), px(14))
            }
            val ordered = if (stack) buttons.reversed() else buttons
            ordered.forEachIndexed { idx, (pair, which, solid) ->
                val b = tv(pair.first.toString().uppercase(), 12f, if (solid) Hx.ON_GREEN else Hx.GREEN, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = box(Hx.GREEN, if (solid) Hx.GREEN else Color.TRANSPARENT)
                    setPadding(px(14), px(9), px(14), px(9))
                    isClickable = true
                    foreground = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x5539FF88), null, ColorDrawable(Color.WHITE))
                    setOnClickListener { d.dismiss(); pair.second?.invoke(d, which) }
                }
                row.addView(b, if (stack) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (idx > 0) topMargin = px(8) }
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (idx > 0) leftMargin = px(10) })
            }
            root.addView(row)
        } else {
            root.addView(View(ctx), LinearLayout.LayoutParams(1, px(10)))
        }

        d.setContentView(root)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ctx.resources.displayMetrics.widthPixels - px(36), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.65f)
        }
        d.show()
        return d
    }
}
