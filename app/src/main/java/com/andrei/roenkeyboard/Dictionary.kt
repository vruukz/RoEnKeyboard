package com.andrei.roenkeyboard

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer

enum class Lang { EN, RO }

data class Suggestion(val word: String, val score: Double, val lang: Lang, val distance: Int)

/**
 * Holds both the English and Romanian frequency dictionaries and produces
 * autocorrect / suggestion candidates without requiring the user to pick a
 * language: every lookup is evaluated against both dictionaries at once and
 * the best-scoring candidate wins, with a small bias towards Romanian for
 * genuinely ambiguous ties (per user preference).
 */
class Dictionary private constructor() {

    // word -> frequency, per language (lower-case, diacritics preserved)
    private val en = HashMap<String, Int>(30000)
    private val ro = HashMap<String, Int>(30000)

    // bucket index by first letter, to prune the edit-distance search
    private val enByFirst = HashMap<Char, MutableList<String>>()
    private val roByFirst = HashMap<Char, MutableList<String>>()

    // ASCII-folded Romanian form (diacritics stripped) -> best (highest freq) accented word.
    // Lets someone typing "sarut" on a keyboard with no ș/ț keys still get "sărut" restored.
    private val roFoldToBest = HashMap<String, String>()

    companion object {
        private const val ROMANIAN_BIAS = 1.15

        /** A word that is itself valid is only re-spelled (e.g. "sa" -> "să") when the accented
         * form is at least this many times more common - otherwise both readings are plausible
         * and rewriting what the user typed is more likely to be wrong than right. */
        private const val KNOWN_WORD_RESTORE_RATIO = 3.0

        /** The above tie-break only applies to words common enough to be a real alternative
         * reading ("ca" vs "că", "in" vs "în"). Below this, an undiacritized entry is almost
         * always just the accented word typed lazily somewhere in the corpus ("masina" for
         * "mașina"), so the diacritics get restored regardless of the ratio. */
        private const val AMBIGUOUS_WORD_MIN_FREQ = 500000

        /** An English word this common is a word in its own right, not a Romanian one typed
         * without diacritics, so it is never "restored" into Romanian - otherwise writing English
         * turns "cat" into "cât", "tea" into "te-a" and "data" into "dată". Below this, an English
         * entry is corpus noise ("si", "maine", "colt"), and the Romanian reading wins. */
        private const val ENGLISH_WORD_PROTECT_FREQ = 10000

        /** ...unless the intended spelling is overwhelmingly more common than the English
         * lookalike. "mă" outweighs English "ma" by 13x so it is still restored, while "cât",
         * "dată" and "te-a" only lead "cat", "data" and "tea" by 3-7x, so those stay English. */
        private const val PROTECTED_WORD_OVERRIDE_RATIO = 12.0

        /** Don't "fix" an unknown word into something rare - that's how names and inflections
         * get mangled. The correction has to be a genuinely common word. */
        private const val MIN_FUZZY_FREQ = 10000

        /** Short unknown words are too easy to "correct" into an unrelated word ("grep" is one
         * edit from "greu", "cron" from "crown"), so under this length the replacement has to be
         * one of the handful of overwhelmingly common words - which is still enough for the typos
         * that actually matter ("teh" -> "the", "wiht" -> "with"). */
        private const val MIN_FUZZY_LEN = 5
        private const val SHORT_WORD_FUZZY_FREQ = 1000000

        @Volatile private var instance: Dictionary? = null

        fun get(context: Context): Dictionary {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val d = Dictionary()
                d.load { path -> context.assets.open(path) }
                instance = d
                return d
            }
        }

        /** Builds a dictionary from arbitrary streams. Used by the JVM unit tests, which read the
         * same asset files straight off disk without an Android runtime. */
        fun fromOpener(open: (String) -> InputStream): Dictionary {
            val d = Dictionary()
            d.load(open)
            return d
        }

        fun foldDiacritics(input: String): String {
            val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
            val sb = StringBuilder()
            for (c in normalized) {
                when (c) {
                    'ă', 'â', 'Ă', 'Â' -> sb.append(if (c.isUpperCase()) 'A' else 'a')
                    'î', 'Î' -> sb.append(if (c.isUpperCase()) 'I' else 'i')
                    'ș', 'ş', 'Ș', 'Ş' -> sb.append(if (c.isUpperCase()) 'S' else 's')
                    'ț', 'ţ', 'Ț', 'Ţ' -> sb.append(if (c.isUpperCase()) 'T' else 't')
                    else -> if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(c)
                }
            }
            return sb.toString()
        }

        /** Strips the joiners a contraction can carry (hyphen/apostrophe) as well as diacritics, so
         * "ți-am" and "didn't" can be matched against the "tiam"/"didnt" a user actually types. */
        fun foldForRestore(input: String): String =
            foldDiacritics(input.replace("-", "").replace("'", "").replace("’", ""))

        fun hasJoiner(word: String): Boolean =
            word.any { it == '-' || it == '\'' || it == '’' }

        /** First letter with any diacritic removed. Words are bucketed by this so that typing the
         * plain ASCII letter still reaches dictionary entries that begin with a diacritic - without
         * it, everything from "ți-am" to "împreună" sits in a bucket the typed word never looks in. */
        fun foldedFirstChar(word: String): Char = foldDiacritics(word.substring(0, 1))[0]
    }

    private fun load(open: (String) -> InputStream) {
        loadFile(open, "dict/en.txt", en, enByFirst)
        loadFile(open, "dict/ro.txt", ro, roByFirst)
        // Map every "folded" (diacritics stripped) shape to the single most frequent RO word with
        // that shape - including undiacritized words themselves. This is needed because the source
        // corpus (subtitles) contains a lot of undiacritized spellings as their own dictionary
        // entries (e.g. "si" 1.08M vs "și" 4.66M) - without this, an undiacritized typed word would
        // exact-match its own undiacritized entry and never get corrected to the proper accented one.
        for ((word, freq) in ro) {
            val folded = foldDiacritics(word)
            val current = roFoldToBest[folded]
            if (current == null || (ro[current] ?: 0) < freq) {
                roFoldToBest[folded] = word
            }
        }
    }

    private fun loadFile(
        open: (String) -> InputStream,
        path: String,
        map: HashMap<String, Int>,
        byFirst: HashMap<Char, MutableList<String>>
    ) {
        val reader = BufferedReader(InputStreamReader(open(path), Charsets.UTF_8))
        reader.useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val word = line.substring(0, tab)
                val freq = line.substring(tab + 1).trim().toIntOrNull() ?: continue
                map[word] = freq
                byFirst.getOrPut(foldedFirstChar(word)) { mutableListOf() }.add(word)
            }
        }
    }

    fun isKnownWord(word: String): Boolean {
        val lw = word.lowercase()
        return en.containsKey(lw) || ro.containsKey(lw)
    }

    /** Exact (case-insensitive) dictionary lookup in either language, used for hyphen/apostrophe
     * contraction handling (e.g. checking whether "v" + "-" + "am" is the known word "v-am", or
     * "didn" + "'" + "t" is "didn't"). Returns the canonical lower-case dictionary form, or null
     * if [word] isn't an entry in either dictionary. */
    fun exactMatch(word: String): String? {
        val lw = word.lowercase()
        return if (ro.containsKey(lw) || en.containsKey(lw)) lw else null
    }

    private fun frequencyOf(word: String): Int = maxOf(en[word] ?: 0, ro[word] ?: 0)

    private fun hasDoubledLetter(word: String): Boolean =
        (1 until word.length).any { word[it] == word[it - 1] }

    /**
     * The word to actually commit for a finished [typed] word - the same string back when it
     * should be left alone. This is deliberately much more conservative than [suggest]: the
     * suggestion strip can afford to show a speculative candidate, but silently rewriting what
     * someone typed is only acceptable when we're confident, otherwise ordinary words, names and
     * inflections get mangled.
     */
    fun correct(typed: String, capitalizationIsAutomatic: Boolean = false): String {
        if (typed.length < 2) return typed
        // Anything that isn't a plain word (digits, emails, URLs, symbols) is left untouched.
        if (!typed.all { it.isLetter() || it == '-' || it == '\'' || it == '’' }) return typed
        // ALL-CAPS and CamelCase are acronyms/brands ("USB", "GitHub", "iPhone"), never typos.
        if (typed.drop(1).any { it.isUpperCase() }) return typed

        val lower = typed.lowercase()
        val folded = foldDiacritics(lower)
        val known = en.containsKey(lower) || ro.containsKey(lower)

        // A capitalized word we don't recognise is almost always a proper noun ("Andrei", "Cluj",
        // "Unihertz") - leave it alone rather than guessing. A capital the keyboard added itself at
        // the start of a sentence says nothing about the word, so it doesn't count.
        if (typed[0].isUpperCase() && !capitalizationIsAutomatic && !known) return typed

        val top = suggest(lower, 1).firstOrNull() ?: return typed
        if (top.word == lower) return typed

        // Is the candidate just this word with its diacritics/hyphen/apostrophe put back, rather
        // than a different word? Those restorations are the whole point of this keyboard, so they
        // get far more latitude than a speculative typo fix.
        // Restoration only ever *adds* what a plain-ASCII keyboard can't type. If the user already
        // typed diacritics or a hyphen/apostrophe, that spelling is deliberate: never swap it for
        // another accented variant of the same shape ("mașină" must not become "mașina").
        val typedIsPlain = lower == foldForRestore(lower)
        val isRestoration = typedIsPlain && foldForRestore(top.word) == folded

        // Never strip a hyphen/apostrophe the user deliberately typed ("it's" must not become
        // "its"); adding one is a restoration, removing one is just wrong.
        if (hasJoiner(lower) && !hasJoiner(top.word)) return typed

        // Don't rewrite a solidly attested English word ("cat", "tea", "data") unless the
        // alternative is in a different league entirely.
        val englishFreq = en[lower] ?: 0
        if (englishFreq >= ENGLISH_WORD_PROTECT_FREQ &&
            frequencyOf(top.word) < englishFreq * PROTECTED_WORD_OVERRIDE_RATIO
        ) {
            return typed
        }

        if (known) {
            if (!isRestoration) return typed
            val typedFreq = frequencyOf(lower).toDouble()
            val topFreq = frequencyOf(top.word).toDouble()
            // Both spellings are plausible real words ("ca" vs "că"): only rewrite when one
            // clearly dominates. Rarer lookalikes are corpus noise, so they always get restored.
            if (typedFreq >= AMBIGUOUS_WORD_MIN_FREQ && topFreq < typedFreq * KNOWN_WORD_RESTORE_RATIO) {
                return typed
            }
            return matchCase(typed, top.word)
        }

        if (isRestoration) return matchCase(typed, top.word)

        // Genuine typo correction: require a real word, of reasonable length, correcting to
        // something common. Otherwise leave it - an unknown word is far more often a name, a
        // technical term or an inflection we simply don't carry than it is a typo.
        val topFreq = frequencyOf(top.word)
        if (lower.length < 3) return typed
        if (lower.length < MIN_FUZZY_LEN && topFreq < SHORT_WORD_FUZZY_FREQ) return typed
        if (topFreq < MIN_FUZZY_FREQ) return typed

        // One edit is a typo; two is usually a different word ("async" is not a misspelt "adânc").
        // Only long words get the benefit of the doubt, and only towards a very common word.
        if (top.distance > 1 && (lower.length < 8 || topFreq < MIN_FUZZY_FREQ * 5)) return typed

        // A candidate *shorter* than what was typed means we'd be deleting a letter the user
        // actually pressed - "regex" is not a misspelt "rege". That's only plausible when they
        // doubled a letter ("helllo" -> "hello").
        if (top.word.length < lower.length && !hasDoubledLetter(lower)) return typed

        return matchCase(typed, top.word)
    }

    /** Applies [original]'s capitalization to [corrected]. */
    fun matchCase(original: String, corrected: String): String {
        if (original.isEmpty()) return corrected
        return if (original[0].isUpperCase()) corrected.replaceFirstChar { it.uppercaseChar() } else corrected
    }

    /**
     * Returns ranked correction candidates for [rawWord] (which does not need to
     * be lower-cased). Combines exact matches, diacritic-fold matches and
     * edit-distance-limited fuzzy matches across both dictionaries.
     */
    fun suggest(rawWord: String, maxResults: Int = 3): List<Suggestion> {
        if (rawWord.isEmpty()) return emptyList()
        val lower = rawWord.lowercase()
        val results = LinkedHashMap<String, Suggestion>()

        // 1) Exact match in English: already correct, still offer as top candidate.
        en[lower]?.let { results[lower] = Suggestion(lower, it.toDouble(), Lang.EN, 0) }

        // 2) Romanian: resolve to the single most frequent word sharing this diacritic-folded
        // shape (this doubles as both "exact match" and "diacritic restoration" - see load()).
        // "masina" -> "mașină", and critically "si" -> "și" even though "si" also appears in the
        // dictionary, because "și" is far more frequent than the undiacritized form. This is
        // scored as distance 0 (not 1) even when diacritics were added/changed: a missing
        // diacritic isn't a typo the way a swapped letter is, so it shouldn't be outranked by an
        // unrelated same-distance word (e.g. English "si") just because that one was typed
        // verbatim - frequency (with the RO bias) is what should decide it.
        val folded = foldDiacritics(lower)
        roFoldToBest[folded]?.let { word ->
            val f = (ro[word] ?: 0).toDouble() * ROMANIAN_BIAS
            val cur = results[word]
            if (cur == null || f > cur.score) results[word] = Suggestion(word, f, Lang.RO, 0)
        }

        // 3) Fuzzy edit-distance search - always run for both languages, even if there's already
        // an exact hit: a missing-apostrophe/hyphen (scored as distance 0, see fuzzyCandidates)
        // restoration must get the chance to outscore a same-distance exact match. This matters
        // because the source corpora contain a lot of low-frequency noise entries that happen to
        // equal a contraction with its punctuation stripped (e.g. "didnt" and "nam" are themselves
        // rare dictionary entries) - without this, that noise entry would block "didn't"/"n-am"
        // from ever being considered at all.
        val maxDist = if (lower.length <= 4) 1 else 2
        fuzzyCandidates(lower, enByFirst, en, Lang.EN, maxDist, 1.0, results)
        fuzzyCandidates(lower, roByFirst, ro, Lang.RO, maxDist, ROMANIAN_BIAS, results)

        // Edit distance dominates the ranking (a closer typo match beats a merely more common
        // word), frequency only breaks ties within the same distance.
        return results.values
            .sortedWith(compareBy<Suggestion> { it.distance }.thenByDescending { it.score })
            .take(maxResults)
    }

    private fun fuzzyCandidates(
        word: String,
        byFirst: HashMap<Char, MutableList<String>>,
        freqMap: HashMap<String, Int>,
        lang: Lang,
        maxDist: Int,
        bias: Double,
        results: LinkedHashMap<String, Suggestion>
    ) {
        val candidates = byFirst[foldedFirstChar(word)] ?: return
        for (cand in candidates) {
            if (kotlin.math.abs(cand.length - word.length) > maxDist + 1) continue
            // Skip RO words that are just an undiacritized duplicate of a more frequent accented
            // word (e.g. "buna" when "bună" exists) - otherwise this exact self-match would win on
            // distance alone and undo the diacritic restoration done above in suggest().
            if (lang == Lang.RO && roFoldToBest[foldDiacritics(cand)] != cand) continue
            // A dictionary word that's just the typed word with a hyphen/apostrophe inserted (and,
            // for Romanian, diacritics restored too) - e.g. typing "tiam" for "ți-am", or "didnt"
            // for "didn't" - is scored as distance 0, the same as a missing diacritic: it's not
            // really a "typo" in the sense a swapped/wrong letter is, so a rival word shouldn't win
            // purely because it happens to be more common.
            val missingJoiner = (cand.contains('-') || cand.contains('\'') || cand.contains('’')) &&
                foldForRestore(cand) == word
            val dist = if (missingJoiner) 0 else boundedEditDistance(word, cand, maxDist)
            if (dist in 0..maxDist) {
                val freq = freqMap[cand] ?: continue
                val score = freq.toDouble() * bias / (1.0 + dist * 3.0)
                val cur = results[cand]
                if (cur == null || score > cur.score) {
                    results[cand] = Suggestion(cand, score, lang, dist)
                }
            }
        }
    }

    /**
     * Damerau-Levenshtein distance (optimal string alignment variant): like Levenshtein but
     * treats an adjacent-letter swap (e.g. "wrold" -> "world") as a single edit instead of two
     * substitutions. Swapped letters are one of the most common typo patterns, so without this a
     * frequent-but-unrelated word can outscore the correct one purely on word frequency.
     */
    private fun boundedEditDistance(a: String, b: String, maxDist: Int): Int {
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > maxDist) return maxDist + 1

        val d = Array(la + 1) { IntArray(lb + 1) }
        for (i in 0..la) d[i][0] = i
        for (j in 0..lb) d[0][j] = j

        for (i in 1..la) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(
                    d[i - 1][j] + 1,
                    d[i][j - 1] + 1,
                    d[i - 1][j - 1] + cost
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, d[i - 2][j - 2] + 1)
                }
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxDist) return maxDist + 1
        }
        return d[la][lb]
    }
}
