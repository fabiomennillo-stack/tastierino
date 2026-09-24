package com.example.kb

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class KeyboardService : InputMethodService(), KbListener, SpellCheckerSession.SpellCheckerSessionListener {

    private lateinit var view: KeyboardView
    private var spell: SpellCheckerSession? = null
    private val main = Handler(Looper.getMainLooper())

    private val spaceTimes = ArrayDeque<Long>()
    private var lastShiftTap = 0L
    private var pendingWord = ""
    private var autoOk = true

    private val langs = listOf("en", "it", "es", "fr", "de", "pt")
    private var langIdx = if (Locale.getDefault().language == "en") 1 else 0

    override fun onCreate() {
        super.onCreate()
        try {
            val tsm = getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
            spell = tsm.newSpellCheckerSession(null, Locale.getDefault(), this, true)
        } catch (e: Exception) { /* nessun correttore disponibile */ }
    }

    override fun onDestroy() {
        spell?.close()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        view = KeyboardView(this, this)
        view.langLabel = langs[langIdx].uppercase()
        return view
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        val blocked = listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )
        autoOk = cls == InputType.TYPE_CLASS_TEXT && v !in blocked &&
                (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
        view.suggestions = emptyList()
        spaceTimes.clear()
        view.shift = 0
        updateCaps()
    }

    // ---------- Helpers ----------

    private fun updateCaps() {
        if (view.shift == 2) return
        val ic = currentInputConnection ?: return
        val caps = ic.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0)
        view.shift = if (caps != 0) 1 else 0
    }

    private fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString() ?: return ""
        return before.takeLastWhile { it.isLetter() || it == '\'' }
    }

    private fun matchCase(w: String, s: String): String = when {
        w.length > 1 && w.all { it.isUpperCase() } -> s.uppercase()
        w.isNotEmpty() && w[0].isUpperCase() -> s.replaceFirstChar { it.uppercase() }
        else -> s
    }

    private fun refreshSuggestions() {
        if (!autoOk) { view.suggestions = emptyList(); return }
        val w = currentWord()
        pendingWord = w
        if (w.length < 2) { view.suggestions = emptyList(); return }
        view.suggestions = listOf(w)
        spell?.getSentenceSuggestions(arrayOf(TextInfo(w)), 3)
    }

    override fun onGetSentenceSuggestions(results: Array<SentenceSuggestionsInfo>?) {
        val r = results?.firstOrNull() ?: return
        if (r.suggestionsCount < 1) return
        val si = r.getSuggestionsInfoAt(0)
        val typo = (si.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0
        val sugs = (0 until si.suggestionsCount).map { si.getSuggestionAt(it) }.filter { it.isNotBlank() }
        main.post {
            val w = pendingWord
            if (w.isEmpty() || w != currentWord()) return@post
            view.suggestions = if (typo && sugs.isNotEmpty()) listOf(w) + sugs.take(2) else listOf(w)
        }
    }

    override fun onGetSuggestions(results: Array<SuggestionsInfo>?) {}

    // ---------- Tasti ----------

    override fun onChar(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        spaceTimes.clear()
        updateCaps()
        refreshSuggestions()
    }

    override fun onShift() {
        val now = SystemClock.uptimeMillis()
        view.shift = when {
            view.shift == 2 -> 0
            view.shift == 1 && now - lastShiftTap < 350 -> 2
            view.shift == 1 -> 0
            else -> 1
        }
        lastShiftTap = now
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingTextInCodePoints(1, 0)
        spaceTimes.clear()
        updateCaps()
        refreshSuggestions()
    }

    /** 3 spazi in rapida successione = Invio. */
    override fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (spaceTimes.isNotEmpty() && now - spaceTimes.last() > 500) spaceTimes.clear()
        spaceTimes.addLast(now)

        if (spaceTimes.size >= 3) {
            spaceTimes.clear()
            ic.deleteSurroundingText(2, 0)   // toglie i due spazi già inseriti
            sendEnter(ic)
            updateCaps()
            view.suggestions = emptyList()
            return
        }
        if (spaceTimes.size == 1 && autoOk) {
            val sug = view.suggestions
            val w = currentWord()
            if (sug.size > 1 && sug[0] == w && w.isNotEmpty()) {
                ic.deleteSurroundingText(w.length, 0)
                ic.commitText(matchCase(w, sug[1]), 1)
            }
        }
        ic.commitText(" ", 1)
        view.suggestions = emptyList()
        updateCaps()
    }

    private fun sendEnter(ic: InputConnection) {
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    override fun onSuggestion(index: Int) {
        val ic = currentInputConnection ?: return
        val s = view.suggestions.getOrNull(index) ?: return
        val w = currentWord()
        ic.deleteSurroundingText(w.length, 0)
        ic.commitText((if (index == 0) s else matchCase(w, s)) + " ", 1)
        view.suggestions = emptyList()
        updateCaps()
    }

    // ---------- Traduttore (ML Kit, on-device) ----------

    override fun onTranslateLong() {
        langIdx = (langIdx + 1) % langs.size
        view.langLabel = langs[langIdx].uppercase()
        Toast.makeText(this, "Traduzione verso: " + langs[langIdx].uppercase(), Toast.LENGTH_SHORT).show()
    }

    /** Traduce il testo selezionato, oppure tutto il testo del campo. */
    override fun onTranslate() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val before = if (selected.isEmpty()) ic.getTextBeforeCursor(3000, 0)?.toString().orEmpty() else ""
        val after = if (selected.isEmpty()) ic.getTextAfterCursor(3000, 0)?.toString().orEmpty() else ""
        val text = if (selected.isNotEmpty()) selected else before + after
        if (text.isBlank()) return
        val target = TranslateLanguage.fromLanguageTag(langs[langIdx]) ?: return

        LanguageIdentification.getClient().identifyLanguage(text)
            .addOnSuccessListener { code ->
                val src = if (code == "und") null else TranslateLanguage.fromLanguageTag(code)
                if (src == null) { toast("Lingua non riconosciuta"); return@addOnSuccessListener }
                if (src == target) { toast("Il testo è già in " + langs[langIdx].uppercase()); return@addOnSuccessListener }
                val tr = Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(target).build()
                )
                toast("Traduco…")
                tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        tr.translate(text)
                            .addOnSuccessListener { out ->
                                val c = currentInputConnection ?: return@addOnSuccessListener
                                c.beginBatchEdit()
                                if (selected.isEmpty()) c.deleteSurroundingText(before.length, after.length)
                                c.commitText(out, 1)
                                c.endBatchEdit()
                            }
                            .addOnFailureListener { toast("Traduzione non riuscita") }
                            .addOnCompleteListener { tr.close() }
                    }
                    .addOnFailureListener { toast("Serve internet per scaricare il modello"); tr.close() }
            }
            .addOnFailureListener { toast("Lingua non riconosciuta") }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
