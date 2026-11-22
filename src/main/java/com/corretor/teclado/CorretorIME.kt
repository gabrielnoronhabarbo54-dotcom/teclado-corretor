package com.corretor.teclado

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class CorretorIME : InputMethodService() {

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val letters = ArrayList<Button>()
    private var shift = false
    private var auto = true
    private var autoBtn: Button? = null
    private var fixBtn: Button? = null

    override fun onCreateInputView(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF202124.toInt())
        root.setPadding(dp(3), dp(6), dp(3), dp(6))
        letters.clear()

        for (line in listOf("1234567890", "qwertyuiop", "asdfghjklç")) {
            val row = newRow()
            for (c in line) row.addView(letterKey(c.toString()))
            root.addView(row)
        }

        val r4 = newRow()
        r4.addView(key("⇧", 1.5f) { toggleShift() })
        for (c in "zxcvbnm") r4.addView(letterKey(c.toString()))
        r4.addView(key("⌫", 1.5f) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) })
        root.addView(r4)

        val r5 = newRow()
        r5.addView(key(",", 0.9f) { typeKey(",") })
        r5.addView(key("?", 0.9f) { typeKey("?") })
        r5.addView(key("espaço", 2.4f) { typeKey(" ") })
        r5.addView(key(".", 0.9f) { typeKey(".") })
        r5.addView(key("!", 0.9f) { typeKey("!") })
        val a = key("Auto ✓", 1.6f) { toggleAuto() }
        a.textSize = 12f
        autoBtn = a
        r5.addView(a)
        val f = key("✨", 1.4f) { correct() }
        fixBtn = f
        r5.addView(f)
        r5.addView(key("⏎", 1.2f) {
            if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        })
        root.addView(r5)
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shift = false
        refreshLabels()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun newRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        return row
    }

    private fun key(label: String, weight: Float = 1f, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 18f
        b.setTextColor(0xFFFFFFFF.toInt())
        b.setBackgroundColor(0xFF3C4043.toInt())
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(0, 0, 0, 0)
        val lp = LinearLayout.LayoutParams(0, dp(48), weight)
        lp.setMargins(dp(2), dp(3), dp(2), dp(3))
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun letterKey(base: String): Button {
        val b = key(base) { typeKey(base) }
        b.tag = base
        if (base[0].isLetter()) letters.add(b)
        return b
    }

    private fun toggleShift() {
        shift = !shift
        refreshLabels()
    }

    private fun refreshLabels() {
        for (b in letters) {
            val base = b.tag as String
            b.text = if (shift) base.uppercase() else base
        }
    }

    private fun toggleAuto() {
        auto = !auto
        autoBtn?.text = if (auto) "Auto ✓" else "Auto ✗"
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
        fixBtn?.text = "⏳"
        executor.execute {
            val fixed = try { checkText(before) } catch (e: Exception) { null }
            main.post { applyFix(before, fixed) }
        }
    }

    private fun applyFix(before: String, fixed: String?) {
        fixBtn?.text = "✨"
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
