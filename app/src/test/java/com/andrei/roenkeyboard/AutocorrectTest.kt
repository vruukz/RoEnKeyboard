package com.andrei.roenkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Runs the real autocorrect against the real dictionaries on the JVM, so behaviour can be checked
 * in bulk instead of by typing words on the phone one at a time.
 */
class AutocorrectTest {

    companion object {
        private lateinit var dict: Dictionary

        private fun assetsDir(): File {
            val candidates = listOf(
                File("src/main/assets"),
                File("app/src/main/assets"),
                File("../app/src/main/assets")
            )
            return candidates.firstOrNull { File(it, "dict/ro.txt").exists() }
                ?: error("assets dir not found from ${File(".").absolutePath}")
        }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val base = assetsDir()
            dict = Dictionary.fromOpener { path -> File(base, path).inputStream() as InputStream }
        }

        private fun dictWords(file: String, limit: Int): List<String> =
            File(assetsDir(), file).readLines()
                .mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) null else line.substring(0, tab)
                }
                .take(limit)
    }

    private fun check(input: String, expected: String, why: String) {
        assertEquals("$why: typing \"$input\"", expected, dict.correct(input))
    }

    private fun unchanged(vararg words: String) {
        val broken = words.filter { dict.correct(it) != it }
            .joinToString { "$it -> ${dict.correct(it)}" }
        assertTrue("these should have been left alone: $broken", broken.isEmpty())
    }

    // ---------- the features we actually want ----------

    @Test
    fun restoresRomanianDiacritics() {
        check("masina", "mașina", "diacritic restoration")
        check("buna", "bună", "diacritic restoration")
        check("esti", "ești", "diacritic restoration")
        check("si", "și", "diacritic restoration")
        check("astazi", "astăzi", "diacritic restoration")
        check("sarut", "sărut", "diacritic restoration")
        check("multumesc", "mulțumesc", "diacritic restoration")
        check("tara", "țară", "diacritic restoration picks the more frequent accented form")
        check("impreuna", "împreună", "word whose dictionary entry starts with a diacritic")
        check("intrebare", "întrebare", "word whose dictionary entry starts with a diacritic")
        check("inseamna", "înseamnă", "word whose dictionary entry starts with a diacritic")
    }

    @Test
    fun restoresRomanianContractions() {
        check("vam", "v-am", "hyphen contraction")
        check("nam", "n-am", "hyphen contraction")
        check("tiam", "ți-am", "hyphen + diacritic contraction")
        check("sa", "să", "frequent accented form dominates")
        check("miam", "mi-am", "hyphen contraction")
        check("dintrun", "dintr-un", "hyphen contraction")
    }

    @Test
    fun restoresEnglishContractions() {
        check("didnt", "didn't", "apostrophe contraction")
        check("dont", "don't", "apostrophe contraction")
        check("cant", "can't", "apostrophe contraction")
        check("wont", "won't", "apostrophe contraction")
        check("im", "i'm", "apostrophe contraction")
        check("ive", "i've", "apostrophe contraction")
        check("youre", "you're", "apostrophe contraction")
        check("thats", "that's", "apostrophe contraction")
        check("couldnt", "couldn't", "apostrophe contraction")
    }

    @Test
    fun fixesRealTypos() {
        check("wrold", "world", "adjacent transposition")
        check("teh", "the", "classic typo")
        check("recieve", "receive", "classic typo")
        check("frined", "friend", "transposition")
        check("wiht", "with", "short typo towards a very common word")
        check("thsi", "this", "short typo towards a very common word")
    }

    @Test
    fun preservesCapitalization() {
        check("Buna", "Bună", "capitalized diacritic restoration")
        check("Wrold", "Wrold", "capitalized unknown word is treated as a name")
    }

    // ---------- the things that must NOT be touched ----------

    @Test
    fun neverRewritesWordsFromItsOwnDictionary() {
        // Any word the dictionary itself contains must come back either untouched or as a
        // diacritic/joiner restoration of the same word - never as a different word.
        val offenders = mutableListOf<String>()
        for (file in listOf("dict/en.txt", "dict/ro.txt")) {
            for (word in dictWords(file, 4000)) {
                if (word.length < 2) continue
                val out = dict.correct(word)
                if (out == word) continue
                val sameShape = Dictionary.foldForRestore(out) == Dictionary.foldDiacritics(word)
                if (!sameShape) offenders += "$word -> $out"
            }
        }
        assertTrue(
            "real dictionary words rewritten into different words (${offenders.size}): " +
                offenders.take(40).joinToString(),
            offenders.isEmpty()
        )
    }

    @Test
    fun leavesNamesAndBrandsAlone() {
        unchanged(
            "Andrei", "Carpinisan", "Cluj", "Napoca", "Unihertz", "Titan", "Anthropic",
            "Kotlin", "Gradle", "GitHub", "Android", "Google", "Claude", "Mihai", "Ioana",
            "Bucuresti", "Timisoara", "Oradea", "Brasov", "Sibiu"
        )
    }

    @Test
    fun leavesAcronymsAndCodeAlone() {
        unchanged("USB", "HTTP", "API", "SDK", "iPhone", "macOS", "JSON", "XML", "PDF", "GPS")
    }

    @Test
    fun leavesNonWordsAlone() {
        unchanged("abc123", "v2", "3d", "x86", "covid19")
    }

    @Test
    fun leavesTechnicalVocabularyAlone() {
        // Ordinary words that simply aren't in a 25k subtitle frequency list. None of these are
        // typos, so none of them should be silently replaced.
        unchanged(
            "kubernetes", "webhook", "middleware", "dataset", "frontend", "backend",
            "changelog", "namespace", "async", "boolean", "enum", "regex", "stdout",
            "dizertatie", "termostat", "higrometru", "condensator", "rezistor"
        )
    }

    @Test
    fun leavesCorrectlySpelledRomanianAlone() {
        unchanged(
            "bună", "ziua", "mulțumesc", "frumos", "acasă", "școală", "părinți", "mâine",
            "împreună", "așa", "când", "făcut", "vreau", "poate", "trebuie", "acum",
            "sănătate", "dimineața", "prieteni", "această", "știu", "și", "ești", "mașină"
        )
    }

    @Test
    fun leavesCorrectlySpelledEnglishAlone() {
        unchanged(
            "hello", "world", "keyboard", "phone", "message", "tomorrow", "thanks",
            "because", "something", "different", "important", "computer", "morning",
            "didn't", "don't", "it's", "people", "through", "thought", "language"
        )
    }

    @Test
    fun doesNotTurnShortWordsIntoUnrelatedOnes() {
        // Short unknown fragments are the easiest thing to mangle; leaving them is the safe call.
        unchanged("zx", "qq", "abc", "xyz", "brb", "omg", "lol", "pls")
    }

    @Test
    fun neverSwapsOneAccentedFormForAnother() {
        // A word typed *with* diacritics is deliberate - restoring diacritics must never turn it
        // into a different inflection of the same shape. (Plain-ASCII forms are a different case:
        // those are exactly what diacritic restoration is for, so they're expected to change.)
        unchanged(
            "mașină", "mașina", "țară", "bună", "fată", "casă", "masă", "carte", "cărți",
            "ședință", "știință", "întâlnire", "ușă", "împărat"
        )
    }

    @Test
    fun doesNotInventCorrectionsForUnknownWords() {
        // Two edits away is a different word, not a typo, and deleting a letter the user actually
        // pressed is almost never right.
        unchanged(
            "async", "regex", "kotlin", "nginx", "redis", "sudo", "grep", "cron",
            "linux", "debian", "ubuntu", "arduino", "raspberry", "voltaj", "senzor"
        )
    }

    @Test
    fun stillFixesDoubledLetters() {
        check("helllo", "hello", "removing an accidentally repeated letter is fine")
    }

    @Test
    fun neverStripsTypedPunctuation() {
        unchanged("it's", "don't", "we're", "v-am", "n-am", "ți-am", "într-un", "well-known")
    }

    @Test
    fun capitalizationRule() {
        // start of field / after a line break / after sentence punctuation + space
        assertTrue("empty field", TextRules.shouldCapitalize(""))
        assertTrue("null", TextRules.shouldCapitalize(null))
        assertTrue("after newline", TextRules.shouldCapitalize("hello\n"))
        assertTrue("after period", TextRules.shouldCapitalize("Hello there. "))
        assertTrue("after question mark", TextRules.shouldCapitalize("Ce faci? "))
        assertTrue("after exclamation", TextRules.shouldCapitalize("Salut! "))
        assertTrue("only spaces", TextRules.shouldCapitalize("   "))
        // mid sentence must stay lower-case
        assertTrue("mid sentence", !TextRules.shouldCapitalize("hello "))
        assertTrue("mid word", !TextRules.shouldCapitalize("hel"))
        assertTrue("after comma", !TextRules.shouldCapitalize("bună, "))
        assertTrue("right after period, no space", !TextRules.shouldCapitalize("end."))
    }

    @Test
    fun fullDictionarySweep() {
        // Every word in both dictionaries, typed exactly as spelled. It may only ever come back
        // unchanged or as a restoration of the same word (diacritics/apostrophe/hyphen added) -
        // never as some *other* word. This is the strongest guard against the "it corrects things
        // that were already fine" problem.
        val offenders = mutableListOf<String>()
        var checked = 0
        var restored = 0
        for (file in listOf("dict/en.txt", "dict/ro.txt")) {
            for (word in dictWords(file, Int.MAX_VALUE)) {
                if (word.length < 2) continue
                checked++
                val out = dict.correct(word)
                if (out == word) continue
                if (Dictionary.foldForRestore(out) == Dictionary.foldDiacritics(word)) {
                    restored++
                } else {
                    offenders += "$word -> $out"
                }
            }
        }
        println("full sweep: $checked words, $restored restorations, ${offenders.size} rewrites")
        assertTrue(
            "dictionary words rewritten into a different word (${offenders.size}): " +
                offenders.take(40).joinToString(),
            offenders.isEmpty()
        )
    }

    @Test
    fun englishWordsAreNotDraggedIntoRomanian() {
        // All of these are real English words that also look like Romanian words missing their
        // diacritics. Writing English must not turn them into Romanian.
        unchanged("cat", "tea", "data", "plan", "band", "card", "rain", "train", "pain")
    }

    @Test
    fun sentenceStartCapitalizationDoesNotBreakCorrection() {
        // At the start of a sentence the keyboard upper-cases the first letter itself; that capital
        // must not make autocorrect treat the word as a proper noun, but a capital the user typed
        // deliberately still should.
        assertEquals("Bună", dict.correct("Buna", capitalizationIsAutomatic = true))
        assertEquals("World", dict.correct("Wrold", capitalizationIsAutomatic = true))
        assertEquals("Wrold", dict.correct("Wrold", capitalizationIsAutomatic = false))
        assertEquals("Andrei", dict.correct("Andrei", capitalizationIsAutomatic = true))
    }

    @Test
    fun realisticSentencesAreNotMangled() {
        // Words here are all legitimate; the only edits expected are diacritic restorations.
        val cases = listOf(
            "salut ce faci diseara" to "salut ce faci diseară",
            // "in" is left alone on purpose: it's a very common English word, so it can't be
            // silently turned into "în" without wrecking English typing.
            "ne vedem la ora cinci in oras" to "ne vedem la ora cinci in oraș",
            "i sent you the file yesterday" to "i sent you the file yesterday",
            "please check the report before the meeting" to "please check the report before the meeting",
            "am ajuns acasa si sunt obosit" to "am ajuns acasă și sunt obosit"
        )
        for ((input, expected) in cases) {
            val got = input.split(" ").joinToString(" ") { dict.correct(it) }
            assertEquals("sentence \"$input\"", expected, got)
        }
    }

    @Test
    fun sentencesSurviveIntact() {
        val sentences = listOf(
            "buna ziua ce mai faci" to "bună ziua ce mai faci",
            "hello how are you doing today" to "hello how are you doing today",
            "ma duc acasa la parinti maine" to "mă duc acasă la părinți mâine"
        )
        for ((input, expected) in sentences) {
            val got = input.split(" ").joinToString(" ") { dict.correct(it) }
            assertEquals("sentence", expected, got)
        }
    }
}
