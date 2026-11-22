package com.corretor.teclado

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class CorretorIME : InputMethodService() {

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val letterKeys = ArrayList<TextView>()
    private var root: LinearLayout? = null
    private var shiftKey: TextView? = null
    private var autoKey: TextView? = null
    private var fixKey: TextView? = null
    private var repeatTask: Runnable? = null
    private var shift = false
    private var symbols = false
    private var auto = true

    override fun onCreateInputView(): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setBackgroundColor(0xFF202124.toInt())
        c.setPadding(dp(3), dp(4), dp(3), dp(10))
        root = c
        buildKeys()
        return c
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shift = false
        if (symbols) {
            symbols = false
            buildKeys()
        } else {
            refreshLabels()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun newRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        return row
    }

    private fun spacer(weight: Float): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(0, dp(52), weight)
        return v
    }

    private fun keyBg(special: Boolean, pressed: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = dp(8).toFloat()
        val color = if (pressed) 0xFF6B6E73.toInt()
        else if (special) 0xFF2E3033.toInt()
        else 0xFF45484C.toInt()
        d.setColor(color)
        return d
    }

    private fun makeKey(
        label: String,
        weight: Float,
        special: Boolean,
        size: Float,
        repeat: Boolean,
        onPress: () -> Unit
    ): TextView {
        val t = TextView(this)
        t.text = label
        t.gravity = Gravity.CENTER
        t.setTextColor(Color.WHITE)
        t.textSize = size
        t.background = keyBg(special, false)
        val lp = LinearLayout.LayoutParams(0, dp(52), weight)
        lp.setMargins(dp(2), dp(3), dp(2), dp(3))
        t.layoutParams = lp
        t.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.background = keyBg(special, true)
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onPress()
                    if (repeat) startRepeat(onPress)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.background = keyBg(special, false)
                    stopRepeat()
                    true
                }
                else -> true
            }
        }
        return t
    }

    private fun startRepeat(action: () -> Unit) {
        stopRepeat()
        val r = object : Runnable {
            override fun run() {
                action()
                main.postDelayed(this, 50)
            }
        }
        repeatTask = r
        main.postDelayed(r, 400)
    }

    private fun stopRepeat() {
        val r = repeatTask
        if (r != null) main.removeCallbacks(r)
        repeatTask = null
    }

    private fun charKey(base: String): TextView {
        val k = makeKey(base, 1f, false, 20f, false) { typeKey(base) }
        k.tag = base
        if (base[0].isLetter()) letterKeys.add(k)
        return k
    }

    private fun charRow(chars: String): LinearLayout {
        val row = newRow()
        for (c in chars) row.addView(charKey(c.toString()))
        return row
    }

    private fun buildKeys() {
        val r = root ?: return
        r.removeAllViews()
        letterKeys.clear()
        shiftKey = null

        val bar = newRow()
        val a = makeKey(if (auto) "Auto ✓" else "Auto ✗", 1f, true, 14f, false) { toggleAuto() }
        autoKey = a
        bar.addView(a)
        val f = makeKey("✨ Corrigir", 1f, true, 14f, false) { correct() }
        fixKey = f
        bar.addView(f)
        r.addView(bar)

        r.addView(charRow("1234567890"))
        if (!symbols) {
            r.addView(charRow("qwertyuiop"))
            r.addView(charRow("asdfghjklç"))
            val r4 = newRow()
            val s = makeKey("⇧", 1.5f, true, 20f, false) { toggleShift() }
            shiftKey = s
            r4.addView(s)
            for (c in "zxcvbnm") r4.addView(charKey(c.toString()))
            r4.addView(makeKey("⌫", 1.5f, true, 20f, true) { del() })
            r.addView(r4)
        } else {
            r.addView(charRow("@#\$%&-+()/"))
            r.addView(charRow("*\"':;!?_=~"))
            val r4 = newRow()
            r4.addView(spacer(1.5f))
            for (c in "<>[]{}|") r4.addView(charKey(c.toString()))
            r4.addView(makeKey("⌫", 1.5f, true, 20f, true) { del() })
            r.addView(r4)
        }

        val b = newRow()
        b.addView(makeKey(if (symbols) "ABC" else "?123", 1.5f, true, 14f, false) { toggleSymbols() })
        b.addView(charKey(","))
        b.addView(makeKey("Português (BR)", 5f, false, 14f, false) { typeKey(" ") })
        b.addView(charKey("."))
        b.addView(makeKey("⏎", 1.5f, true, 20f, false) { enter() })
        r.addView(b)

        refreshLabels()
    }

    private fun refreshLabels() {
        for (k in letterKeys) {
            val base = k.tag as String
            k.text = if (shift) base.uppercase() else base
        }
        shiftKey?.setTextColor(if (shift) 0xFF8AB4F8.toInt() else Color.WHITE)
    }

    private fun toggleShift() {
        shift = !shift
        refreshLabels()
    }

    private fun toggleSymbols() {
        symbols = !symbols
        buildKeys()
    }

    private fun toggleAuto() {
        auto = !auto
        autoKey?.text = if (auto) "Auto ✓" else "Auto ✗"
    }

    private fun del() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun enter() {
        if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun typeKey(s: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(if (shift) s.uppercase() else s, 1)
        if (shift) {
            shift = false
            refreshLabels()
        }
        if (auto && (s == "." || s == "?" || s == "!")) correct()
    }

    private fun isPassword(): Boolean {
        val t = currentInputEditorInfo?.inputType ?: return false
        val cls = t and InputType.TYPE_MASK_CLASS
        val v = t and InputType.TYPE_MASK_VARIATION
        val textPass = cls == InputType.TYPE_CLASS_TEXT && (
            v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val numPass = cls == InputType.TYPE_CLASS_NUMBER &&
            v == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return textPass || numPass
    }

    private fun correct() {
        if (isPassword()) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(300, 0)?.toString() ?: return
        if (before.isBlank()) return
        fixKey?.text = "⏳"
        executor.execute {
            val fixed = try { checkText(before) } catch (e: Exception) { null }
            main.post { applyFix(before, fixed) }
        }
    }

    private fun applyFix(before: String, fixed: String?) {
        fixKey?.text = "✨ Corrigir"
        if (fixed == null) {
            Toast.makeText(this, "Não consegui corrigir (internet?)", Toast.LENGTH_SHORT).show()
            return
        }
        val ic = currentInputConnection ?: return
        val now = ic.getTextBeforeCursor(before.length + 20, 0)?.toString() ?: return
        val idx = now.lastIndexOf(before)
        if (idx < 0 || fixed == before) return
        val extra = now.substring(idx + before.length)
        if (extra.length > 15) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length + extra.length, 0)
        ic.commitText(fixed + extra, 1)
        ic.endBatchEdit()
    }

    private fun checkText(text: String): String {
        val conn = URL("https://api.languagetool.org/v2/check").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = "language=pt-BR&text=" + URLEncoder.encode(text, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) throw RuntimeException("HTTP " + conn.responseCode)
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val matches = JSONObject(resp).getJSONArray("matches")
            val edits = ArrayList<Triple<Int, Int, String>>()
            for (i in 0 until matches.length()) {
                val m = matches.getJSONObject(i)
                val reps = m.getJSONArray("replacements")
                if (reps.length() == 0) continue
                val cat = m.getJSONObject("rule").getJSONObject("category").optString("id")
                if (cat == "STYLE" || cat == "REDUNDANCY") continue
                edits.add(Triple(m.getInt("offset"), m.getInt("length"), reps.getJSONObject(0).getString("value")))
            }
            edits.sortByDescending { it.first }
            val sb = StringBuilder(text)
            var lastStart = Int.MAX_VALUE
            for ((off, len, rep) in edits) {
                if (off + len > lastStart) continue
                sb.replace(off, off + len, rep)
                lastStart = off
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }
}
