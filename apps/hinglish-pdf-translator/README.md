# Hinglish Book Translator (Android)

An Android app that translates whole books (PDFs and EPUBs) into modern,
casual **Hinglish** (Hindi in Roman script) with the free **Google Gemini
API**, page by page, in the background.

- **Page by page:** each PDF page is extracted on its own and translated (a
  normal page is one Gemini request), so page N of the output is page N of
  the original.
- **Structure kept:** headings, bullet points (with nesting), numbering and
  paragraph breaks are detected on every page and restored after translation.
- **Background:** a foreground service with a persistent "Translating page 45
  of 300..." notification keeps running with the screen locked.
- **Crash-proof:** every page is saved to Room the moment it is done; after a
  crash, force-stop or reboot it resumes at the first untranslated page.
- **Free-tier friendly:** requests are spaced out, rate-limit errors are
  waited out, and a used-up daily quota pauses the book to resume later.
- **Live preview:** Gemini's answer streams into the Compose UI as it arrives.
- **Export:** a new PDF with the original page breaks and page sizes, saved to
  Downloads. EPUBs are saved as a translated copy of the same EPUB.
- **Small:** ~6.5 MB APK.

Tech: Kotlin, Jetpack Compose (Material 3), Google AI client SDK for Android
(`com.google.ai.client.generativeai`), Room, a foreground service,
Coroutines/Flow, `pdfbox-android` for reading and Android's `PdfDocument` for
writing.

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
   contains the key. A build without a key shows "This build has no Gemini API
   key" and won't start translating.

**Keep the key private.** A key compiled into an APK can be extracted by
anyone who has that APK, so don't publish an APK with your key inside. If a key
leaks, delete it in AI Studio and create a new one.

### Which model

The model is the `GEMINI_MODEL_NAME` constant at the top of
[`TranslationRepository.kt`](app/src/main/java/com/example/hinglishpdf/data/TranslationRepository.kt)
(`gemini-3.5-flash-lite`, free tier). Retired models such as
`gemini-1.5-flash` (shut down 29 September 2025) and `gemini-2.0-flash` answer
"not found"; the app then stops with a message saying to change that constant.
Check current models and free-tier limits on
[the pricing page](https://ai.google.dev/gemini-api/docs/pricing) and in AI Studio.

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
          prompt = system prompt with the chunk in <input>:         │
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
is the specified prompt, character for character (a unit test checks it). Each
chunk replaces `[INSERT TEXT CHUNK HERE]` inside the `<input>` tags. The model
is set up with temperature 0.2, and safety filters set to `NONE` so ordinary
literature (violence, romance, medicine) isn't refused mid-book.

Because the prompt carries the chunk inside it, it is sent as the request
itself rather than as a separate system instruction, so Gemini receives exactly
that text.

### Getting the structure back

The chunk is written as Markdown, one block per paragraph. Gemini's answer is
split the same way; code fences, echoed `<input>` tags and a leading
"Hinglish:" label are removed first.

- **Block count matches:** block *i* is the translation of block *i*, and the
  app re-applies the original marker. A bullet stays a bullet even if Gemini
  drops the `-`.
- **Count doesn't match** (merged or split paragraphs): nothing is guessed.
  The chunk is translated again in two halves, recursively, until it lines up.
- **A block comes back in Devanagari:** it is retried on its own.
- **Gemini refuses some text:** that text stays in English instead of stopping
  the book.

### Free-tier limits and errors

[`TranslationRepository.kt`](app/src/main/java/com/example/hinglishpdf/data/TranslationRepository.kt)
handles Gemini's errors as follows:

| Situation | What the app does |
|---|---|
| Rate limit (429 per minute) | Waits as long as Gemini asks ("retry in 23s") or backs off 15 s → 5 min; slows the request pace |
| Network drop, timeout, 5xx | Same retry with backoff; the notification shows "retrying in 30 s" |
| Daily quota used up | Stops the book with a clear message; **Resume** tomorrow continues at the same page |
| Invalid key, unknown model, unsupported region | Stops with a message saying what to fix |
| Still failing after 8 retries | Stops; **Resume** continues at the same page |

Requests are spaced at least 4 s apart (at most 15 a minute), and the gap grows
after every rate-limit error.

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
        ├── HinglishApp.kt          App-wide singletons (DB, Gemini client, repository)
        ├── MainActivity.kt
        ├── service/
        │   ├── TranslationService.kt   Foreground service: extract → translate page by page → export
        │   ├── Notifications.kt        "Translating page 45 of 300..." + Pause, finished/failed
        │   └── LiveStatus.kt           Live page and streaming text for the screen
        ├── data/
        │   ├── TranslationRepository.kt  Gemini client, pacing, retries, page translation Flow
        │   ├── llm/HinglishPrompt.kt     The system prompt (verbatim) + answer parser
        │   ├── db/                       Room: books, pages (PK = bookId + pageNumber), DAOs
        │   ├── pdf/                      PDFBox extraction + layout analysis per page
        │   ├── epub/EpubBook.kt          EPUB reading and translated copy
        │   ├── document/                 Import, PDF export, blocks, plain-text formatting
        │   ├── export/BookExporter.kt    Writes the result to Downloads via MediaStore
        │   └── translate/BlockChunker.kt Page → chunks of whole blocks
        └── ui/                           Compose screen + ViewModel
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
