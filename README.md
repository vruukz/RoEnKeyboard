# RoEn Keyboard

A minimal Android keyboard (IME) for the **Unihertz Titan Slim** with automatic
bilingual autocorrect for **English and Romanian** — no manual language switch.

## How the autocorrect works

Every word you finish typing (on space, punctuation, or Enter) is looked up
against **both** dictionaries at once — there's no language toggle to get wrong:

1. **Exact match** — if the word is already valid in either language, it's left alone.
2. **Diacritic restoration** — Romanian words typed without diacritics (since the
   Titan Slim's physical keyboard has no ă/â/î/ș/ț keys) are restored to their
   accented form, e.g. `masina` → `mașină`, `sarbatoare` → `sărbătoare`.
3. **Fuzzy correction** — otherwise the closest word (edit distance ≤ 2) from
   either dictionary is substituted, ranked by real-world word frequency
   (from the [OpenSubtitles-derived FrequencyWords corpus](https://github.com/hermitdave/FrequencyWords)).

Romanian candidates get a small scoring bias, so on a genuine tie between an
English and a Romanian correction, Romanian wins (per explicit preference —
English autocorrect still works fully, it just loses close ties).

A 3-candidate suggestion strip above the keyboard also lets you tap an
alternative before it's auto-applied. Long-press `a`, `e`, `i`, `s`, `t` for
quick access to `ă â î ș ț` while typing manually.

The IME also intercepts the Titan Slim's **physical keyboard** input
(`onKeyDown`), so autocorrect applies whether you're typing on the hardware
keys or the on-screen keyboard.

## Building

Requires JDK 17 and the Android SDK (or just let CI build it — see below).

```bash
./gradlew assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Installing on the Titan Slim

1. Enable **Settings → Security → Install unknown apps** for the browser/file
   manager you'll use, then install the APK.
2. Open **RoEn Keyboard**, tap **Enable in system settings**, and turn the
   keyboard on in the system's input method list.
3. Tap **Set as active keyboard** and pick **RoEn Keyboard**.

## CI / Releases

Pushing a tag like `v1.0.0` triggers [.github/workflows/release.yml](.github/workflows/release.yml),
which builds the APK on GitHub Actions and attaches it to a new GitHub
Release automatically — no local Android toolchain required.

## Dictionaries

`app/src/main/assets/dict/en.txt` and `ro.txt` are the top ~25k most frequent
words per language (word + frequency count, tab-separated), derived from the
FrequencyWords project's OpenSubtitles-based corpora.
