package com.andrei.roenkeyboard

/** Small pure text rules, kept out of the IME service so they can be unit tested. */
object TextRules {

    /**
     * True when [before] - the text immediately preceding the cursor - means the next letter typed
     * starts a sentence and should be upper-cased: the very start of the field, right after a line
     * break, or after ". ", "! " or "? ".
     */
    fun shouldCapitalize(before: CharSequence?): Boolean {
        if (before.isNullOrEmpty()) return true
        val last = before[before.length - 1]
        if (last == '\n') return true
        if (!last.isWhitespace()) return false
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return true
        val prev = trimmed[trimmed.length - 1]
        return prev == '.' || prev == '!' || prev == '?'
    }
}
