package com.example.kb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

interface KbListener {
    fun onChar(text: String)
    fun onShift()
    fun onBackspace()
    fun onSpace()
    fun onTranslate()
    fun onTranslateLong()
    fun onSuggestion(index: Int)
}

/**
 * Layout (10 unità di larghezza, tutti i tasti 1 unità tranne la barra spaziatrice):
 *  q w e r t y u i o p
 *   a s d f g h j k l
 *    z x c v b n m
 *  [Shift][      Spazio (7)      ][.][Cancella]
 */
class KeyboardView(ctx: Context, private val l: KbListener) : View(ctx) {

    companion object {
        const val CHAR = 0
        const val SHIFT = 1
        const val BACK = 2
        const val SPACE = 3
        const val TRANS = 4
        const val SUG = 5
    }

    private class Key(
        val label: String, val hint: String?, val type: Int, val units: Float,
        val func: Boolean = false, val idx: Int = 0
    ) {
        val rect = RectF()
        var pressed = false
    }

    /** 0 = minuscolo, 1 = maiuscola singola, 2 = blocco maiuscole */
    var shift = 0
        set(v) { field = v; invalidate() }
    var suggestions: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var langLabel = "EN"
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val gap = 3f * dp
    private val stripH = 48f * dp
    private val rowH = 54f * dp
    private val main = Handler(Looper.getMainLooper())

    private fun letters(s: String, hints: String) =
        s.mapIndexed { i, c -> Key(c.toString(), hints[i].toString(), CHAR, 1f) }

    private val rows: List<List<Key>> = listOf(
        letters("qwertyuiop", "1234567890"),
        letters("asdfghjkl", "@#€_&-+()"),
        letters("zxcvbnm", "*\"':;!?"),
        listOf(
            Key("", null, SHIFT, 1f, func = true),
            Key("", null, SPACE, 7f),
            Key(".", ",", CHAR, 1f, func = true),   // tenendo premuto -> virgola
            Key("", null, BACK, 1f, func = true)
        )
    )
    private val strip: List<Key> = listOf(
        Key("", null, TRANS, 1f),
        Key("", null, SUG, 1f, idx = 0),
        Key("", null, SUG, 1f, idx = 1),
        Key("", null, SUG, 1f, idx = 2)
    )
    private val all = strip + rows.flatten()

    private val cBg = 0xFF1E1F22.toInt()
    private val cLetter = 0xFF3B3D43.toInt()
    private val cFunc = 0xFF586378.toInt()
    private val cPressed = 0xFF7A8496.toInt()

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = Color.WHITE
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT; color = 0xFFB4B8C2.toInt(); textSize = 11f * dp
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2.2f * dp
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (stripH + 4 * rowH + 6 * dp).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val width = w.toFloat()
        val u = width / 10f
        rows.forEachIndexed { r, row ->
            var x = (width - row.sumOf { it.units.toDouble() }.toFloat() * u) / 2f
            val y = stripH + r * rowH
            for (k in row) {
                k.rect.set(x + gap, y + gap, x + k.units * u - gap, y + rowH - gap)
                x += k.units * u
            }
        }
        val tw = 56f * dp
        strip[0].rect.set(0f, 0f, tw, stripH - 2 * gap)
        val sw = (width - tw) / 3f
        for (i in 1..3) strip[i].rect.set(tw + (i - 1) * sw, 0f, tw + i * sw, stripH - 2 * gap)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(cBg)
        for (k in strip) drawStripItem(c, k)
        for (row in rows) for (k in row) drawKey(c, k)
    }

    private fun baseline(p: Paint, cy: Float) = cy - (p.descent() + p.ascent()) / 2f

    private fun drawStripItem(c: Canvas, k: Key) {
        val r = k.rect
        if (k.type == TRANS) {
            txt.textSize = 18f * dp
            c.drawText("文A", r.centerX(), baseline(txt, r.centerY() - 5 * dp), txt)
            txt.textSize = 10f * dp
            c.drawText("→ " + langLabel, r.centerX(), r.bottom - 4 * dp, txt)
        } else {
            val s = suggestions.getOrNull(k.idx) ?: return
            txt.textSize = 18f * dp
            val shown = if (s.length > 14) s.take(13) + "…" else s
            c.drawText(shown, r.centerX(), baseline(txt, r.centerY()), txt)
        }
        if (k.pressed) {
            keyPaint.color = 0x33FFFFFF
            c.drawRoundRect(r, 10 * dp, 10 * dp, keyPaint)
        }
    }

    private fun drawKey(c: Canvas, k: Key) {
        val r = k.rect
        keyPaint.color = when {
            k.pressed -> cPressed
            k.func -> cFunc
            else -> cLetter
        }
        c.drawRoundRect(r, 10 * dp, 10 * dp, keyPaint)
        val cx = r.centerX()
        val cy = r.centerY()
        when (k.type) {
            CHAR -> {
                txt.textSize = 25f * dp
                val t = if (shift > 0) k.label.uppercase() else k.label
                c.drawText(t, cx, baseline(txt, cy), txt)
                k.hint?.let { c.drawText(it, r.right - 7 * dp, r.top + 16 * dp, hintPaint) }
            }
            SHIFT -> {
                val s = 9f * dp
                path.reset()
                path.moveTo(cx, cy - s); path.lineTo(cx + s, cy)
                path.lineTo(cx + s * .45f, cy); path.lineTo(cx + s * .45f, cy + s * .85f)
                path.lineTo(cx - s * .45f, cy + s * .85f); path.lineTo(cx - s * .45f, cy)
                path.lineTo(cx - s, cy); path.close()
                line.style = if (shift > 0) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                c.drawPath(path, line)
                line.style = Paint.Style.STROKE
                if (shift == 2) c.drawLine(cx - s * .45f, cy + s * 1.2f, cx + s * .45f, cy + s * 1.2f, line)
            }
            BACK -> {
                val s = 10f * dp
                line.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(cx - s, cy); path.lineTo(cx - s * .35f, cy - s * .8f)
                path.lineTo(cx + s, cy - s * .8f); path.lineTo(cx + s, cy + s * .8f)
                path.lineTo(cx - s * .35f, cy + s * .8f); path.close()
                c.drawPath(path, line)
                c.drawLine(cx - s * .05f, cy - s * .35f, cx + s * .6f, cy + s * .35f, line)
                c.drawLine(cx + s * .6f, cy - s * .35f, cx - s * .05f, cy + s * .35f, line)
            }
        }
    }

    // ---------- Touch (multi-touch, ripetizione cancella, pressione lunga) ----------

    private class Ptr(val key: Key) {
        var done = false
        var run: Runnable? = null
    }

    private val ptrs = HashMap<Int, Ptr>()

    private fun hit(x: Float, y: Float): Key? {
        for (k in all) {
            val r = k.rect
            if (x >= r.left - gap && x <= r.right + gap && y >= r.top - gap && y <= r.bottom + gap) return k
        }
        return null
    }

    private fun down(id: Int, x: Float, y: Float) {
        val k = hit(x, y) ?: return
        val p = Ptr(k)
        ptrs[id] = p
        k.pressed = true
        invalidate()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when {
            k.type == BACK -> {
                p.done = true
                l.onBackspace()
                val r = object : Runnable {
                    override fun run() { l.onBackspace(); main.postDelayed(this, 45) }
                }
                p.run = r
                main.postDelayed(r, 400)
            }
            (k.type == CHAR && k.hint != null) || k.type == TRANS -> {
                val r = Runnable {
                    p.done = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (k.type == TRANS) l.onTranslateLong() else l.onChar(k.hint!!)
                }
                p.run = r
                main.postDelayed(r, 380)
            }
        }
    }

    private fun release(id: Int, fire: Boolean) {
        val p = ptrs.remove(id) ?: return
        p.run?.let { main.removeCallbacks(it) }
        p.key.pressed = false
        invalidate()
        if (fire && !p.done) fire(p.key)
    }

    private fun fire(k: Key) {
        when (k.type) {
            CHAR -> l.onChar(if (shift > 0) k.label.uppercase() else k.label)
            SHIFT -> l.onShift()
            SPACE -> l.onSpace()
            TRANS -> l.onTranslate()
            SUG -> l.onSuggestion(k.idx)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                val p = ptrs[id] ?: continue
                val r = p.key.rect
                val x = e.getX(i); val y = e.getY(i)
                if (x < r.left - gap || x > r.right + gap || y < r.top - gap || y > r.bottom + gap) release(id, false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                release(e.getPointerId(e.actionIndex), true)
            MotionEvent.ACTION_CANCEL -> ptrs.keys.toList().forEach { release(it, false) }
        }
        return true
    }
}
