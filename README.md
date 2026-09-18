# RoEn Keyboard

Android keyboard (IME) built for **phones with a physical QWERTY keyboard**, with
automatic bilingual autocorrect for **English and Romanian** — and no language
switch to remember.

If you type in two languages on a hardware keyboard, you've hit both of these:
the keyboard has no `ă â î ș ț` keys, and every stock keyboard makes you toggle
languages (or silently "corrects" one language into the other). This fixes both.

Works on any Android device with a hardware keyboard — BlackBerry-style QWERTY
phones, the Unihertz Titan family, F(x)tec Pro¹, Astro Slide, Cosmo
Communicator, or any phone with a Bluetooth/USB keyboard attached. It's also a
perfectly usable on-screen keyboard on a normal touchscreen phone.

## What it does

**Types Romanian on a keyboard that has no Romanian keys.** Type the plain
letters and the diacritics come back: `masina` → `mașina`, `multumesc` →
`mulțumesc`, `impreuna` → `împreună`, `tiam` → `ți-am`.

**Never asks which language you're in.** Every finished word is scored against
both dictionaries at once, so you can switch language mid-sentence — even
mid-word — and it keeps up.

**Doesn't wreck the language you didn't mean.** Real English words are not
dragged into Romanian: `cat` stays `cat`, not `cât`; `data` stays `data`, not
`dată`. Romanian still wins where it genuinely dominates.

**Leaves alone what it doesn't understand.** Names, brands, acronyms, and
technical vocabulary are not "corrected" into whatever happens to be one edit
away — `regex`, `grep`, `kubernetes` and `Cluj` survive untouched. This is
enforced by a test that sweeps all 50,000 dictionary entries and fails if any of
them is rewritten into a different word.

**Restores contractions you can't be bothered to punctuate.** `didnt` →
`didn't`, `dont` → `don't`, `youre` → `you're`, and the Romanian clitics
`vam` → `v-am`, `nam` → `n-am`, `sa` → `să`, `dintrun` → `dintr-un` — typed as
one word, as two words (`v am`), or with the hyphen already in place.

**Fixes ordinary typos.** `wrold` → `world`, `teh` → `the`, `recieve` →
`receive`, including swapped adjacent letters, which most simple spellcheckers
score as two errors and therefore miss.

**Stays out of the way.** The on-screen keyboard is hidden by default — you get
just a thin suggestion strip, since the hardware keyboard is doing the typing.
A button on the strip pops the on-screen keyboard up when you want it.

Plus: sentence auto-capitalization, tap-to-toggle `Alt`/`Sym`/`Shift` (no
holding them down), and a 3-candidate suggestion strip you can tap before a
correction is applied.

## How the correction works

Every word you finish (on space, punctuation, or Enter) goes through:

1. **Exact match** — already a valid word in either language? Left alone.
2. **Diacritic / punctuation restoration** — the typed letters match a real word
   once you add diacritics, a hyphen or an apostrophe. Treated as high
   confidence, because a plain-ASCII keyboard physically can't produce those.
3. **Typo correction** — only with real confidence: one edit (two for long
   words), towards a genuinely common word, and never by deleting a letter you
   actually pressed unless you doubled it.

Candidates are ranked by real-world frequency from the
[OpenSubtitles-derived FrequencyWords corpus](https://github.com/hermitdave/FrequencyWords),
with edit distance dominating the ranking and Romanian taking close ties.

A word typed *with* diacritics or an apostrophe is always treated as deliberate
and never re-spelled.

## Install

1. Download the APK from [Releases](../../releases).
2. Allow install from unknown sources for your browser/file manager, install it.
3. Open **RoEn Keyboard** → **Enable in system settings** → turn it on.
4. **Set as active keyboard** → pick **RoEn Keyboard**.

> Release APKs are debug-signed by CI, and each build uses a different key, so
> installing a new version over an old one will fail with a signature mismatch.
> Uninstall the previous version first.

## Building

Requires JDK 17 and the Android SDK.

```bash
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # the autocorrect test suite
```

Pushing a `v*` tag builds the APK on GitHub Actions and attaches it to a
release; the tests run first, so a regression blocks the release.

## Dictionaries

`app/src/main/assets/dict/en.txt` and `ro.txt` hold the ~25k most frequent words
per language (word + frequency, tab-separated), from the FrequencyWords
OpenSubtitles corpora, plus hand-curated entries for the contractions those
corpora tokenize away (`didn't`, `v-am`, `într-un`, ...).

## Adding another language

The engine isn't Romanian-specific — it scores a typed word against two
frequency lists and knows how to restore characters a keyboard can't type. To
swap in a different pair, replace the two dictionary files and adjust the
diacritic folding in `Dictionary.foldDiacritics`.
