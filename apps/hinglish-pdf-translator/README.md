# Hindi Book Translator (Android)

An Android app that translates whole books (PDFs and EPUBs) into natural,
conversational **Hindi in Devanagari** (हिंदी), the way modern urban Indians
speak, with the free **Google Gemini API**, page by page, in the background.

- **Context-aware:** adapts आप / तुम and the tone to the book's genre, uses
  everyday English words written in Devanagari (ऑप्शन, प्लान) instead of
  bookish Hindi, and keeps speaker labels, names and "Chapter 1" in English.
- **Page by page:** a normal page is one Gemini request; page N of the output
  is page N of the original.
- **Structure kept:** headings, bullet points (with nesting), numbering and
  paragraph breaks are restored after translation.
- **Model fallback:** a prioritized list of Gemini models; a retired, out-of-
  quota or rate-limited model is swapped for the next one automatically.
- **Reading UI:** Devanagari fonts from Google Fonts (Mukta, Noto Serif
  Devanagari, Kalam) and an **Aa** sheet for font style and text size.
- **Background and crash-proof:** a foreground service with a "Translating
  page 45 of 300..." notification; every page is saved to Room the moment it
  is done, and a restart resumes at the first untranslated page.
- **Export:** a new PDF with the original page breaks and page sizes, saved to
  Downloads. EPUBs are saved as a translated copy of the same EPUB.
- **Small:** ~6.6 MB APK.

Tech: Kotlin, Jetpack Compose (Material 3, downloadable Google Fonts), Google AI
client SDK for Android (`com.google.ai.client.generativeai`), Room, a
foreground service, Coroutines/Flow, `pdfbox-android` for reading and
Android's `PdfDocument` for writing.

> **Privacy:** the text of your books is sent to Google to be translated. On
> the free tier, Google's terms say content may be used to improve its products.

## Setup: the Gemini API key

1. Get a free key at [Google AI Studio](https://aistudio.google.com/) → **Get API key**.
   2. Add it to `local.properties` in this folder (next to `settings.gradle.kts`).
   Android Studio creates the file; it is git-ignored, so the key never reaches git:

   ```properties
   sdk.dir=/path/to/Android/sdk
   GEMINI_API_KEY=my_actual_key_here
   ```

3. Rebuild. `app/build.gradle.kts` reads the key into
   `BuildConfig.GEMINI_API_KEY`, and `GeminiHinglishModel` in
   `TranslationRepository.kt` passes it to `GenerativeModel`. No Kotlin file
   contains the key.

**No key in the build?** The app then asks for one and keeps it only in its
private storage on that phone (never in code or git). A key in
`local.properties` always takes priority.

**Keep the key private.** A key compiled into an APK can be extracted by
anyone who has that APK, so don't publish an APK with your key inside. If a key
leaks, delete it in AI Studio and create a new one.

### Which model: automatic fallback

`GEMINI_MODELS` at the top of
[`TranslationRepository.kt`](app/src/main/java/com/example/hinglishpdf/data/TranslationRepository.kt)
is a prioritized list:

```kotlin
val GEMINI_MODELS = listOf(
    "gemini-1.5-flash",       // requested; shut down by Google on 29 Sept 2025
    "gemini-1.5-pro",         // requested; shut down by Google on 29 Sept 2025
    "gemini-3.5-flash-lite",  // current free-tier models, so the list always
    "gemini-3.8-flash",       // ends on one that works
)
```

`FallbackGeminiModel` tries them in order and moves on silently when a model
fails in a way another model can fix:

| Failure | What happens |
|---|---|
| Retired / not found (404) | Skipped for the rest of the app session (one wasted request per model per session) |
| Daily quota used up | Skipped for an hour, then tried again |
| Rate-limited (429) | Skipped until Gemini says it can retry; earlier models are preferred again afterwards |
| Bad key, region, refused text, no internet | Not hidden: another model would not help |
| Every model rate-limited | Waits for the first one to free up |
| Every model retired | Stops with a message to add a current model to the list |

A model that fails *after* it started answering is never spliced with another
model's text; the whole request is retried. The Gemini card in the app shows
which model actually answered.

## Build

Requirements: JDK 17+, Android SDK 35.

```bash
cd apps/hinglish-pdf-translator
./gradlew testDebugUnitTest assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew assembleRelease` gives an R8-shrunk, unsigned APK (~6.5 MB); sign
it with your own key before installing.

## How it works

```
Select PDF/EPUB ─► BookImporter.copyIn ─► Room: book (QUEUED) ─► TranslationService
                                                                   │
  1. Extract pages once (PDFBox + PdfLayoutAnalyzer) ─► Room: pages │ (one transaction)
  2. loop: page = first page with translations IS NULL              │
        TranslationRepository.translatePage(page):                  │
          BlockChunker: page → chunk(s) of whole blocks (≤800 words)│
          request = the chunk (system instruction = the prompt):     │
              # Chapter 4                                           │
              It was a cold morning.                                │
              - Bring a coat                                        │
              2. Leave early                                        │
          Gemini (streamed) ─► service ─► UI                        │
          answer matched back block by block                        │
        Room: save page N immediately                               │
  3. PdfExporter: page N → page N, original size ─► Downloads       │
```

### The prompt

[`HinglishPrompt.SYSTEM_PROMPT`](app/src/main/java/com/example/hinglishpdf/data/llm/HinglishPrompt.kt)
is the specified context-aware Hindi prompt, word for word (a unit test checks
it). The specified text stopped before closing its `<examples>` tag, so the
closing `</examples>` line was added. It is set once as Gemini's **system
instruction**, and each request carries only the chunk of text. Temperature
is 0.2, and safety filters are set to `NONE` so ordinary literature isn't
refused mid-book.

### Reading UI

The **Aa** button in the top bar opens a bottom sheet:

| Style | Google Font |
|---|---|
| Sans-serif (default) | Mukta |
| Serif | Noto Serif Devanagari |
| Casual | Kalam |

A slider sets the text size from 14 to 28 sp, with headings scaling along.
Lines are spaced at 1.6× so Devanagari matras aren't cramped. The choice is
saved on the phone. Fonts are downloaded once through Google Play Services
(`res/values/font_certs.xml` is the standard Google Fonts certificate list);
until they arrive, or on a phone without Play Services, the system's own
Devanagari font is used. The exported PDF uses the system Devanagari font.

### Getting the structure back

The chunk is written as Markdown, one block per paragraph. Gemini's answer is
split the same way; code fences, a leading "Modern Hindi:" label (the prompt's
examples use one) and quotes wrapped around the whole answer are removed first.
Speaker labels such as "YOUTH:" are part of the text and stay.

- **Block count matches:** block *i* is the translation of block *i*, and the
  app re-applies the original marker. A bullet stays a bullet even if Gemini
  drops the `-`.
- **Count doesn't match** (merged or split paragraphs): nothing is guessed.
  The chunk is translated again in two halves, recursively, until it lines up.
- **A block comes back empty:** it is retried on its own.
- **Gemini refuses some text:** that text stays in English instead of stopping
  the book.

### Free-tier limits and errors

[`TranslationRepository.kt`](app/src/main/java/com/example/hinglishpdf/data/TranslationRepository.kt)
paces requests and handles Gemini's errors as follows:

| Situation | What the app does |
|---|---|
| Every chunk | A fixed **4.5 s pause** after each translated chunk (about 13 requests a minute, under the free tier's 15) |
| Rate limit (429) | Another model in `GEMINI_MODELS` is tried first; if all are limited, **waits 60 s** (longer if Gemini asks) and tries again, **with no retry limit**. The book never stops for a rate limit, and the notification shows Gemini's own message |
| "limit: 0" (no free quota for this model and key) | The model is skipped for the session, like a retired one |
| Daily quota used up | Stops the book with a clear message; **Resume** later continues at the same page |
| Network drop, timeout, 5xx, or an answer the SDK can't read ("deserialize a response") | Retried with backoff (15 s → 5 min), up to 8 times, then stops; **Resume** continues at the same page |
| Invalid key, region, or no usable model | Stops with a message saying what to fix |

### Threads

The repository's `Flow` runs on `Dispatchers.Default` (network, parsing,
stitching). The service publishes progress to a `StateFlow`. The ViewModel
prepares the live page on `Dispatchers.Default` (conflated) and Compose
collects it on the main thread.

### Fault tolerance

- `pages` has primary key `(bookId, pageNumber)`. A page's `translations`
  column is NULL until the whole page is done, and the page is written as soon
  as it is.
- Resuming means "first page where translations IS NULL". A crash mid-page
  redoes only that page.
- The service is `START_STICKY`. If Android kills it, Android restarts it and
  it resumes. After a crash or reboot, opening the app restarts it.

### Export

`PdfExporter` writes one output page per source page at the source page's
size, with the page number in the footer. Headings, bullets and numbering are
laid out like the original. Text that would overflow is scaled down to fit its
own page. Pages with no text stay as pages, so the numbering never shifts. The
file goes to **Downloads** through MediaStore, which needs no storage
permission on Android 10+ (this app's minimum).

## Project structure

```
app/
├── build.gradle.kts                Dependencies; GEMINI_API_KEY (local.properties) → BuildConfig
├── proguard-rules.pro
└── src/main/
    ├── AndroidManifest.xml         INTERNET, foreground service (specialUse), wake lock, notifications
    └── java/com/example/hinglishpdf/
        ├── HinglishApp.kt          App-wide singletons (DB, Gemini fallback engine, settings)
        ├── MainActivity.kt
        ├── service/
        │   ├── TranslationService.kt   Foreground service: extract → translate page by page → export
        │   ├── Notifications.kt        "Translating page 45 of 300..." + Pause, finished/failed
        │   └── LiveStatus.kt           Live page and streaming text for the screen
        ├── data/
        │   ├── TranslationRepository.kt  Gemini client, model fallback, pacing, retries, page Flow
        │   ├── llm/HinglishPrompt.kt     The Hindi system instruction + answer parser
        │   ├── settings/GeminiKeyStore.kt BuildConfig key, or one pasted into the app
        │   ├── db/                       Room: books, pages (PK = bookId + pageNumber), DAOs
        │   ├── pdf/                      PDFBox extraction + layout analysis per page
        │   ├── epub/EpubBook.kt          EPUB reading and translated copy
        │   ├── document/                 Import, PDF export, blocks, plain-text formatting
        │   ├── export/BookExporter.kt    Writes the result to Downloads via MediaStore
        │   └── translate/BlockChunker.kt Page → chunks of whole blocks
        └── ui/                           Compose screen + ViewModel; reader/ = fonts + Aa sheet
```

## Limitations

- **Not yet run on a phone.** The build, the unit tests (prompt, parser,
  chunking, retries and quota handling with a fake Gemini, Room resume) and a
  live request to Google's endpoint (which correctly rejected a dummy key) all
  pass. A real translation with a valid key still needs to be tried.
- **The SDK is deprecated.** Google no longer updates
  `com.google.ai.client.generativeai` (last release 0.9.0) and recommends
  Firebase AI Logic for new apps. It still works with current models: it
  ignores response fields it doesn't know.
- **Free-tier limits** cap requests per minute and per day. A long book may
  take more than one day; it resumes where it stopped.
- **PDF structure is inferred** from font sizes, bold text, bullet glyphs,
  numbering and indentation. Unusual layouts (multi-column, tables) come out as
  plain paragraphs. Each output page is rebuilt, not a pixel copy.
- **Images are not copied** into the translated PDF; picture-only pages stay
  as pages with a note.
- **Scanned PDFs** contain images, not text; run OCR on them first.
- **Battery optimisation:** some brands (Xiaomi, Oppo, Vivo...) kill
  background apps aggressively. If translation stops with the screen off, set
  the app's battery usage to "Unrestricted".
