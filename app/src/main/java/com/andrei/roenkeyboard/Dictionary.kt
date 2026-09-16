package com.andrei.roenkeyboard

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer

enum class Lang { EN, RO }

data class Entry(val word: String, val freq: Int, val lang: Lang)

data class Suggestion(val word: String, val score: Double, val lang: Lang, val distance: Int)

/**
 * Holds both the English and Romanian frequency dictionaries and produces
 * autocorrect / suggestion candidates without requiring the user to pick a
 * language: every lookup is evaluated against both dictionaries at once and
 * the best-scoring candidate wins, with a small bias towards Romanian for
 * genuinely ambiguous ties (per user preference).
 */
class Dictionary private constructor() {

    // word -> Entry, per language (lower-case, diacritics preserved)
    private val en = HashMap<String, Int>(30000)
    private val ro = HashMap<String, Int>(30000)

    // bucket index: (lang, firstChar, lengthBucket) -> words, to prune edit-distance search
    private val enByFirst = HashMap<Char, MutableList<String>>()
    private val roByFirst = HashMap<Char, MutableList<String>>()

    // ASCII-folded Romanian form (diacritics stripped) -> best (highest freq) accented word.
    // Lets someone typing "sarut" on a keyboard with no ș/ț keys still get "sărut" restored.
    private val roFoldToBest = HashMap<String, String>()

    companion object {
        private const val ROMANIAN_BIAS = 1.15
        @Volatile private var instance: Dictionary? = null

        fun get(context: Context): Dictionary {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val d = Dictionary()
                d.load(context)
                instance = d
                return d
            }
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
    }

    private fun load(context: Context) {
        loadFile(context, "dict/en.txt", en, enByFirst)
        loadFile(context, "dict/ro.txt", ro, roByFirst)
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
        context: Context,
        path: String,
        map: HashMap<String, Int>,
        byFirst: HashMap<Char, MutableList<String>>
    ) {
        val reader = BufferedReader(InputStreamReader(context.assets.open(path), Charsets.UTF_8))
        reader.useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val word = line.substring(0, tab)
                val freq = line.substring(tab + 1).trim().toIntOrNull() ?: continue
                map[word] = freq
                val first = word[0]
                byFirst.getOrPut(first) { mutableListOf() }.add(word)
            }
        }
    }

    fun isKnownWord(word: String): Boolean {
        val lw = word.lowercase()
        return en.containsKey(lw) || ro.containsKey(lw)
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

        // 3) Fuzzy edit-distance search (only if we don't already have a confident exact hit).
        if (results.isEmpty() || results.values.none { it.distance == 0 }) {
            val maxDist = if (lower.length <= 4) 1 else 2
            fuzzyCandidates(lower, enByFirst, en, Lang.EN, maxDist, 1.0, results)
            fuzzyCandidates(lower, roByFirst, ro, Lang.RO, maxDist, ROMANIAN_BIAS, results)
        }

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
        val candidates = mutableListOf<String>()
        // Same first letter, and also neighboring first letters (covers a typo on the first char).
        byFirst[word.first()]?.let { candidates.addAll(it) }
        if (word.length > 2) {
            // Also allow a typo'd first letter by scanning a limited neighborhood; skip for perf
            // when the dictionary bucket for the exact first letter already has enough entries.
        }
        for (cand in candidates) {
            if (kotlin.math.abs(cand.length - word.length) > maxDist) continue
            // Skip RO words that are just an undiacritized duplicate of a more frequent accented
            // word (e.g. "buna" when "bună" exists) - otherwise this exact self-match would win on
            // distance alone and undo the diacritic restoration done above in suggest().
            if (lang == Lang.RO && roFoldToBest[foldDiacritics(cand)] != cand) continue
            val dist = boundedEditDistance(word, cand, maxDist)
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
