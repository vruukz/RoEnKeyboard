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

    // Support for Romanian hyphenated contractions (v-am, s-a, mi-a, dintr-un, ...): the word just
    // committed - both as originally typed (lastCommittedRaw, used to test "raw-nextWord" against
    // the dictionary, since the typed prefix itself, not its own autocorrection, is what forms the
    // contraction) and as it actually appears in the text now (lastCommittedText, so a merge can
    // delete exactly that much back) - plus, when the user typed the hyphen directly, the prefix
    // before it (so "v-am" resolves as one unit instead of autocorrecting "v" in isolation, which
    // would just mangle it).
    private var lastCommittedRaw: String? = null
    private var lastCommittedText: String? = null
    private var pendingJoinerPrefix: String? = null
    private var pendingJoinerChar: String? = null

    // Set when the keyboard itself upper-cased the first letter of the word being typed (sentence
    // start), as opposed to the user pressing shift - autocorrect treats the two differently.
    private var currentWordAutoCapitalized = false

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
        lastCommittedRaw = null
        lastCommittedText = null
        pendingJoinerPrefix = null
        pendingJoinerChar = null
        stickyAlt = Sticky.OFF
        stickyShift = Sticky.OFF
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
        var c = ch
        if (currentWord.isEmpty()) {
            // Starting a new word: upper-case it if we're at the start of a sentence. Remember that
            // we did, so a capital letter here isn't mistaken for a deliberately typed proper noun
            // (which autocorrect leaves alone) when the word is finished.
            currentWordAutoCapitalized = c.isLowerCase() && shouldCapitalizeNextLetter(ic)
            if (currentWordAutoCapitalized) c = c.uppercaseChar()
        }
        currentWord.append(c)
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
            // Deleting back across a word/joiner boundary invalidates that tracked context.
            pendingJoinerPrefix = null
            pendingJoinerChar = null
            lastCommittedRaw = null
            lastCommittedText = null
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun handleWordBoundary(ic: android.view.inputmethod.InputConnection, boundary: String, sendEnterAction: Boolean = false) {
        val typedWord = currentWord.toString()

        if (boundary == "-" || boundary == "'") {
            // Likely the start of a contraction (v-am, s-a, didn't, ...): don't autocorrect the
            // prefix in isolation - "v", "s", "mi", "didn" etc aren't real words on their own and
            // would just get mangled - commit it as typed and resolve the whole thing once the
            // suffix after the joiner arrives.
            if (typedWord.isNotEmpty()) {
                ic.finishComposingText()
                pendingJoinerPrefix = typedWord
                pendingJoinerChar = boundary
            }
            currentWord.clear()
            updateCandidates(emptyList())
            ic.commitText(boundary, 1)
            return
        }

        val prefix = pendingJoinerPrefix
        val joiner = pendingJoinerChar
        if (typedWord.isNotEmpty() && prefix != null && joiner != null) {
            // Finishing a contraction typed with an explicit hyphen/apostrophe: "prefix<joiner>typedWord".
            val exact = dictionary.exactMatch("$prefix$joiner$typedWord")
            val resolved = exact ?: "$prefix$joiner${bestAutocorrection(typedWord)}"
            val cased = matchCase(prefix, resolved)
            ic.deleteSurroundingText(prefix.length + 1, 0) // remove the already-committed "prefix<joiner>"
            ic.setComposingText(cased, 1)
            ic.finishComposingText()
            lastCommittedRaw = "$prefix$joiner$typedWord".lowercase()
            lastCommittedText = cased
            pendingJoinerPrefix = null
            pendingJoinerChar = null
        } else if (typedWord.isNotEmpty()) {
            // Normal word boundary, but first check whether merging with the word just committed
            // forms a known contraction typed as two separate words ("v" <space> "am" -> "v-am").
            // This checks the *raw* typed previous word, not its own autocorrection (e.g. "v" was
            // likely auto-corrected to "va" on its own, but "v-am" - not "va-am" - is the real word).
            val prevRaw = lastCommittedRaw
            val prevText = lastCommittedText
            val merged = if (boundary == " " && prevRaw != null) dictionary.exactMatch("$prevRaw-$typedWord") else null
            if (merged != null && prevText != null) {
                val cased = matchCase(prevRaw!!, merged)
                ic.deleteSurroundingText(prevText.length + 1, 0) // remove the previously committed word + the space before this one
                ic.setComposingText(cased, 1)
                ic.finishComposingText()
                lastCommittedRaw = merged.lowercase()
                lastCommittedText = cased
            } else {
                val best = bestAutocorrection(typedWord)
                ic.setComposingText(best, 1)
                ic.finishComposingText()
                lastCommittedRaw = typedWord.lowercase()
                lastCommittedText = best
            }
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
        if (boundary != " ") { // contractions only merge across a plain space
            lastCommittedRaw = null
            lastCommittedText = null
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
    private fun bestAutocorrection(typed: String): String =
        dictionary.correct(typed, capitalizationIsAutomatic = currentWordAutoCapitalized)

    private fun matchCase(original: String, corrected: String): String =
        dictionary.matchCase(original, corrected)

    /**
     * True when the cursor sits at the start of a sentence, so the next letter typed should be
     * upper-cased: at the very start of the field, right after a line break, or after ". ", "! "
     * or "? ".
     */
    private fun shouldCapitalizeNextLetter(ic: android.view.inputmethod.InputConnection): Boolean =
        TextRules.shouldCapitalize(ic.getTextBeforeCursor(4, 0))

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

    /**
     * Sticky (one-shot / locked / off) state for the physical keyboard's modifier keys, so Alt,
     * Sym, Fn and Shift can be tapped instead of held: first tap arms the modifier for the next
     * key, a second tap locks it until tapped again.
     */
    private enum class Sticky { OFF, ONE_SHOT, LOCKED;
        fun next(): Sticky = when (this) { OFF -> ONE_SHOT; ONE_SHOT -> LOCKED; LOCKED -> OFF }
    }

    private var stickyAlt = Sticky.OFF
    private var stickyShift = Sticky.OFF

    private fun isModifierKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_SYM,
        KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> true
        else -> false
    }

    /** Meta state to resolve the next character with, combining the physical event's own modifiers
     * with whatever we have latched. */
    private fun effectiveMetaState(event: KeyEvent): Int {
        var meta = event.metaState
        if (stickyAlt != Sticky.OFF) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (stickyShift != Sticky.OFF) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        return meta
    }

    /** Clears any modifier that was only armed for a single keystroke. */
    private fun consumeOneShotModifiers() {
        if (stickyAlt == Sticky.ONE_SHOT) stickyAlt = Sticky.OFF
        if (stickyShift == Sticky.ONE_SHOT) stickyShift = Sticky.OFF
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Swallow the release of a modifier we handled on the way down, so the system doesn't also
        // act on it (and so releasing it doesn't cancel the sticky state we just set).
        if (isModifierKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Tap-to-toggle modifiers: Alt/Sym/Fn and Shift latch instead of needing to be held down.
        when (keyCode) {
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SYM, KeyEvent.KEYCODE_FUNCTION -> {
                stickyAlt = stickyAlt.next()
                return true
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                stickyShift = stickyShift.next()
                return true
            }
        }

        // Route physical/hardware keyboard presses (e.g. on devices like the Titan Slim)
        // through the same autocorrect pipeline as the on-screen keys.
        val ic = currentInputConnection
        if (ic != null && event != null && event.isPrintingKey &&
            keyCode != KeyEvent.KEYCODE_SPACE && keyCode != KeyEvent.KEYCODE_ENTER
        ) {
            val unicodeChar = event.getUnicodeChar(effectiveMetaState(event))
                .takeIf { it != 0 } ?: event.unicodeChar
            consumeOneShotModifiers()
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
