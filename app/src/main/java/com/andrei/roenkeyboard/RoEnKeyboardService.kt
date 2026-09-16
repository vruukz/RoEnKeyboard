package com.andrei.roenkeyboard

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import kotlin.math.min

/**
 * A single bilingual (Romanian + English) IME. There is no language switch:
 * every word typed is scored against both dictionaries in [Dictionary] and the
 * best correction wins automatically, biased slightly towards Romanian on ties.
 */
class RoEnKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private lateinit var keyboardView: KeyboardView
    private lateinit var qwertyKeyboard: Keyboard
    private lateinit var symbolsKeyboard: Keyboard

    private lateinit var candidate0: TextView
    private lateinit var candidate1: TextView
    private lateinit var candidate2: TextView

    private lateinit var dictionary: Dictionary

    private val currentWord = StringBuilder()
    private var capsLock = false
    private var shiftOn = false
    private var currentSuggestions: List<Suggestion> = emptyList()

    override fun onCreate() {
        super.onCreate()
        dictionary = Dictionary.get(applicationContext)
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_container, null)
        keyboardView = root.findViewById(R.id.keyboardView)
        candidate0 = root.findViewById(R.id.candidate0)
        candidate1 = root.findViewById(R.id.candidate1)
        candidate2 = root.findViewById(R.id.candidate2)

        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        symbolsKeyboard = Keyboard(this, R.xml.symbols)

        keyboardView.keyboard = qwertyKeyboard
        keyboardView.setOnKeyboardActionListener(this)
        keyboardView.isPreviewEnabled = true
        keyboardView.visibility = View.GONE // hidden by default; toggled via the candidates bar button

        candidate0.setOnClickListener { applySuggestionAt(0) }
        candidate1.setOnClickListener { applySuggestionAt(1) }
        candidate2.setOnClickListener { applySuggestionAt(2) }
        root.findViewById<View>(R.id.btnToggleKeyboard).setOnClickListener { toggleKeyboardVisibility() }

        return root
    }

    /** Always create/show the input view (so the suggestions bar exists) even though the device
     *  would otherwise auto-hide it while a physical keyboard is attached (e.g. the Titan Slim). */
    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentWord.clear()
        capsLock = false
        shiftOn = false
        keyboardView.keyboard = qwertyKeyboard
        keyboardView.visibility = View.GONE // start each input session with only the suggestions bar visible
        updateShiftState()
        updateCandidates(emptyList())
    }

    private fun toggleKeyboardVisibility() {
        keyboardView.visibility = if (keyboardView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        commitCurrentWordAsIs()
    }

    // ---- OnKeyboardActionListener ----

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val ic = currentInputConnection ?: return
        when (primaryCode) {
            Keyboard.KEYCODE_SHIFT -> {
                shiftOn = !shiftOn
                updateShiftState()
            }
            -2 -> { // toggle symbols / letters
                keyboardView.keyboard = if (keyboardView.keyboard === qwertyKeyboard) symbolsKeyboard else qwertyKeyboard
                updateShiftState()
            }
            Keyboard.KEYCODE_DELETE -> handleBackspace(ic)
            10 -> handleWordBoundary(ic, "\n", sendEnterAction = true)
            32 -> handleWordBoundary(ic, " ")
            44, 46, 33, 63, 58, 59 -> handleWordBoundary(ic, primaryCode.toChar().toString())
            else -> handleSoftKeyLetter(ic, primaryCode)
        }
    }

    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun onText(text: CharSequence?) {}
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeUp() {}
    override fun swipeDown() {}

    // ---- input handling ----

    /** Called from the on-screen keyboard: [codePoint] is always lower-case, cased here using our own shift/caps-lock state. */
    private fun handleSoftKeyLetter(ic: android.view.inputmethod.InputConnection, codePoint: Int) {
        var ch = codePoint.toChar()
        if (shiftOn || capsLock) {
            ch = ch.uppercaseChar()
            if (shiftOn && !capsLock) {
                shiftOn = false
                updateShiftState()
            }
        }
        handleLetter(ic, ch)
    }

    /** Appends an already-cased letter (from either the soft keyboard or a hardware key press) to the current word. */
    private fun handleLetter(ic: android.view.inputmethod.InputConnection, ch: Char) {
        currentWord.append(ch)
        ic.setComposingText(currentWord, 1)
        updateCandidates(dictionary.suggest(currentWord.toString()))
    }

    private fun handleBackspace(ic: android.view.inputmethod.InputConnection) {
        if (currentWord.isNotEmpty()) {
            currentWord.deleteCharAt(currentWord.length - 1)
            if (currentWord.isEmpty()) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
                updateCandidates(emptyList())
            } else {
                ic.setComposingText(currentWord, 1)
                updateCandidates(dictionary.suggest(currentWord.toString()))
            }
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun handleWordBoundary(ic: android.view.inputmethod.InputConnection, boundary: String, sendEnterAction: Boolean = false) {
        if (currentWord.isNotEmpty()) {
            val best = bestAutocorrection(currentWord.toString())
            ic.setComposingText(best, 1)
            ic.finishComposingText()
        }
        currentWord.clear()
        updateCandidates(emptyList())

        if (sendEnterAction) {
            val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            val handled = when (action) {
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE -> {
                    ic.performEditorAction(action)
                    true
                }
                else -> false
            }
            if (!handled) ic.commitText(boundary, 1)
        } else {
            ic.commitText(boundary, 1)
        }
    }

    private fun commitCurrentWordAsIs() {
        val ic = currentInputConnection ?: return
        if (currentWord.isNotEmpty()) {
            ic.finishComposingText()
            currentWord.clear()
        }
    }

    /** Chooses the correction to commit for a finished word, preserving original casing. */
    private fun bestAutocorrection(typed: String): String {
        if (typed.isEmpty()) return typed
        val suggestions = dictionary.suggest(typed, maxResults = 1)
        val top = suggestions.firstOrNull() ?: return typed

        // Already correct as typed (distance 0 and same spelling): keep it.
        if (top.distance == 0 && top.word.equals(typed, ignoreCase = true)) {
            return matchCase(typed, top.word)
        }
        // Only auto-apply a correction we're reasonably confident about.
        if (top.distance <= 2) {
            return matchCase(typed, top.word)
        }
        return typed
    }

    private fun matchCase(original: String, corrected: String): String {
        if (original.isEmpty()) return corrected
        return if (original[0].isUpperCase()) {
            corrected.replaceFirstChar { it.uppercaseChar() }
        } else corrected
    }

    // Maps each visible slot (0=left, 1=center/bold, 2=right) to the index into currentSuggestions
    // it's currently showing, or -1 if the slot is empty. The center slot always shows the actual
    // top-ranked suggestion (the one bestAutocorrection() will auto-apply on a word boundary).
    private var slotToSuggestion = intArrayOf(-1, -1, -1)

    private fun applySuggestionAt(slot: Int) {
        val suggestionIndex = slotToSuggestion.getOrElse(slot) { -1 }
        if (suggestionIndex < 0 || suggestionIndex >= currentSuggestions.size) return
        val ic = currentInputConnection ?: return
        val chosen = matchCase(currentWord.toString(), currentSuggestions[suggestionIndex].word)
        ic.setComposingText(chosen, 1)
        ic.finishComposingText()
        ic.commitText(" ", 1)
        currentWord.clear()
        updateCandidates(emptyList())
    }

    private fun updateCandidates(suggestions: List<Suggestion>) {
        currentSuggestions = suggestions
        // Suggestions are already ranked best-first; display the top one in the bold center slot
        // and the runners-up on either side, so the highlighted word is always the one that gets
        // auto-applied on space/punctuation.
        slotToSuggestion = when (suggestions.size) {
            0 -> intArrayOf(-1, -1, -1)
            1 -> intArrayOf(-1, 0, -1)
            2 -> intArrayOf(1, 0, -1)
            else -> intArrayOf(1, 0, 2)
        }
        if (!::candidate0.isInitialized) return // no on-screen view yet (e.g. typing on the physical keyboard)
        val views = arrayOf(candidate0, candidate1, candidate2)
        for (slot in views.indices) {
            val suggestionIndex = slotToSuggestion[slot]
            views[slot].text = if (suggestionIndex >= 0) matchCase(currentWord.toString(), suggestions[suggestionIndex].word) else ""
        }
    }

    private fun updateShiftState() {
        qwertyKeyboard.isShifted = shiftOn || capsLock
        keyboardView.invalidateAllKeys()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Route physical/hardware keyboard presses (e.g. on devices like the Titan Slim)
        // through the same autocorrect pipeline as the on-screen keys.
        val ic = currentInputConnection
        if (ic != null && event != null && event.isPrintingKey &&
            keyCode != KeyEvent.KEYCODE_SPACE && keyCode != KeyEvent.KEYCODE_ENTER
        ) {
            val unicodeChar = event.unicodeChar
            if (unicodeChar != 0 && Character.isLetter(unicodeChar)) {
                handleLetter(ic, unicodeChar.toChar())
                return true
            }
            if (unicodeChar != 0) {
                // Any other printable character (. , ! ? ' - etc.) finishes the current word first,
                // exactly like tapping punctuation on the on-screen keyboard does - otherwise these
                // were silently dropped and the word behind them never got autocorrected.
                handleWordBoundary(ic, unicodeChar.toChar().toString())
                return true
            }
        }
        if (ic != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_SPACE -> {
                    handleWordBoundary(ic, " ")
                    return true
                }
                KeyEvent.KEYCODE_ENTER -> {
                    handleWordBoundary(ic, "\n", sendEnterAction = true)
                    return true
                }
                KeyEvent.KEYCODE_DEL -> {
                    handleBackspace(ic)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
