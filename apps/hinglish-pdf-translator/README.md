# Hinglish / Minglish Book Translator (Android)

A 100% offline Android app that translates whole books (PDFs of 200+ pages,
and EPUBs) into **Hinglish** (Hindi) or **Minglish** (Marathi) written in the
Latin alphabet, with an on-device LLM.

- **Page by page:** each PDF page goes to the model as one prompt, and page N
  of the output is page N of the original.
- **Structure kept:** headings, bullet points (with nesting), numbering and
  paragraph breaks are detected on every page and restored after translation.
- **Background:** a foreground service with a persistent "Translating page 45
  of 300..." notification keeps running with the screen locked.
- **Crash-proof:** every page is saved to Room the moment it is done; after a
  crash, force-stop or reboot it resumes at the first untranslated page.
- **Thermal management:** a pause every 5 pages, longer when the phone
  reports heat.
- **Export:** a new PDF with the original page breaks and page sizes, saved to
  Downloads. EPUBs are saved as a translated copy of the same EPUB.
- **No network:** the manifest strips `INTERNET`, so nothing can leave the device.

Tech: Kotlin, Jetpack Compose (Material 3), LiteRT-LM and MediaPipe Tasks
GenAI, Room, a foreground service, Coroutines/Flow, `pdfbox-android` for
reading and Android's `PdfDocument` for writing.

## Build

Requirements: JDK 17+, Android SDK 35.

```bash
cd apps/hinglish-pdf-translator
./gradlew testDebugUnitTest assembleDebug
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

The build makes one APK per ABI: `arm64-v8a` for phones, `x86_64` for the
emulator. MediaPipe's native engine is about 27 MB per ABI, so a single APK
for both would be twice the size. `assembleRelease` (R8-shrunk, about 14 MB)
produces unsigned APKs; sign them with your own key before installing.

Or open the folder in Android Studio.

## Getting a model onto the phone

**Recommended: Llama 3.2 3B Instruct** (`.litertlm`, ~2.1 GB, 4096-token
context). Hindi is one of Llama 3.2's officially supported languages, so it
handles Roman Hindi much better than models of the same size. Qwen 2.5 3B
was considered, but Hindi is not among its main languages and there is no
ready-made phone build of it.

| Model | File | Size | Runtime |
|---|---|---|---|
| **Llama 3.2 3B Instruct** (recommended) | `model.litertlm` | ~2.1 GB | LiteRT-LM |
| Gemma 3 1B IT (int4) | `.task` | ~0.5 GB | MediaPipe |
| Qwen 2.5 1.5B Instruct | `..._ekv4096.task` | ~1.6 GB | MediaPipe |

Llama 3.2 3B needs a phone with 6 GB of RAM or more.

1. In the app, tap **Download Llama**. It opens the download in your browser
   (a community LiteRT-LM conversion of Meta's model, published under the
   Llama 3.2 Community License). The app itself never goes online.
2. Tap **Import model** and pick the downloaded `model.litertlm`. It is copied
   into the app's private storage, so you can delete the download afterwards.

**Embedding the model in the APK** (app size is not a concern): put
`model.litertlm` in `app/src/main/assets/` and build. On first launch the app
unpacks it to internal storage (both runtimes need a real file path), so it
needs about twice the model's size free once. Bundling a 2 GB asset has not
been tested here; if the build or install rejects an APK that large, use
Import model instead.

Alternatives: `adb push` a model to
`/sdcard/Android/data/com.example.hinglishpdf/files/models/` and tap
**Rescan**, or bundle a small one in `app/src/main/assets/`.

The token budget is read from the file name when it says (`..._ekv1280.task`
means 1280); otherwise 4096 for `.litertlm` and 2048 for `.task`. The amount
of text per prompt is sized from it automatically.

## Project structure

```
app/src/main/java/com/example/hinglishpdf/
├── HinglishApp.kt                  App-wide singletons (DB, model, service helpers)
├── MainActivity.kt
├── service/
│   ├── TranslationService.kt       Foreground service: extract → translate page by page → export
│   ├── ThermalGovernor.kt          Rest every 5 pages; longer when the phone reports heat
│   ├── Notifications.kt            "Translating page 45 of 300..." + Pause, finished/failed
│   └── LiveStatus.kt               Live label and streaming text for the screen
├── data/
│   ├── db/                         Room: books, pages (PK = bookId + pageNumber), DAOs
│   ├── pdf/
│   │   ├── PdfTextExtractor.kt     PDFBox lines with font size, weight and position, per page
│   │   └── PdfLayoutAnalyzer.kt    Headings, nested bullets, numbering, paragraphs per page
│   ├── epub/EpubBook.kt            EPUB reading and translated copy
│   ├── document/
│   │   ├── BookImporter.kt         Private copy of the book + its pages
│   │   ├── PdfExporter.kt          New PDF, one page per source page, same page size
│   │   └── DocBlock.kt, DocumentFormatter.kt
│   ├── export/BookExporter.kt      Writes the result to Downloads via MediaStore
│   ├── translate/
│   │   ├── PageTranslator.kt       One page → one prompt → answers mapped back to blocks
│   │   ├── BlockChunker.kt         Only splits a page that cannot fit the model's context
│   │   └── TargetLanguage.kt       Hinglish (Hindi) / Minglish (Marathi)
│   └── llm/
│       ├── HinglishPrompt.kt       Guidelines + your page instruction + tagged lines
│       ├── LlmTranslator.kt        Picks LiteRT-LM (.litertlm) or MediaPipe (.task/.bin)
│       ├── LiteRtLmEngine.kt, MediaPipeEngine.kt
│       └── ModelController.kt, ModelFileManager.kt
└── ui/                             Compose screen + ViewModel
```

## How it works

```
Select PDF/EPUB ─► BookImporter.copyIn ─► Room: book (QUEUED) ─► TranslationService
                                                                   │
  1. Extract pages once (PDFBox + PdfLayoutAnalyzer) ─► Room: pages │ (in one transaction)
  2. loop: page = first page with translations IS NULL              │
        PageTranslator: whole page → one prompt                     │
        [1] # Chapter 4                                             │
        [2] It was a cold morning.                                  │
        [3] - Bring a coat                                          │
        [4] 2. Leave early                                          │
        answer matched back by [ID]; skipped/Devanagari lines retried
        Room: save page N immediately                               │
        ThermalGovernor: pause every 5 pages / when hot             │
  3. PdfExporter: page N → page N, original size ─► Downloads       │
```

### The prompt (every page)

Your guidelines come first: keep technical terms, proper nouns and jargon
in English; everyday words in Roman Hindi or Marathi; Latin letters only;
a natural bilingual tone; keep the structure. Then the line-ID rules with a
worked example, then the page instruction **word for word**, with
"Hinglish/Minglish" filled in from your choice:

> Translate the following text into natural, conversational Hinglish using ONLY
> the Latin/English alphabet. Keep technical terms in English. Do not output
> Devanagari script. Preserve all paragraphs, bullet points, and line breaks
> exactly as they appear in the source text. Output ONLY the translated text:
> [the page, one tagged line per heading/bullet/paragraph]

The structure is not left to the model: the app re-applies each block's
original marker, so a bullet stays a bullet even if the model drops the `-`.

### Fault tolerance

- `pages` has primary key `(bookId, pageNumber)`. A page's `translations`
  column is NULL until it is done, and the translation is written the moment
  the page finishes.
- Resuming means "first page where translations IS NULL". There is no cursor
  that could go stale.
- The service is `START_STICKY`. If Android kills it, Android restarts it and
  it resumes. After a crash or reboot, opening the app restarts it.
- Pause (in the notification or the app) marks the book PAUSED; Resume
  continues at the next untranslated page.
- A partial wake lock keeps the CPU running with the screen off.

### Thermal management

`ThermalGovernor` pauses 8 s after every 5 pages. If the phone reports
*moderate* heat (`PowerManager.currentThermalStatus`), it pauses 20 s after
every page. At *severe* or worse, it waits in 30 s steps (up to 10 minutes)
until the phone cools down.

### Export

`PdfExporter` writes one output page per source page at the source page's
size, with the page number in the footer. Headings, bullets and numbering are
laid out like the original. Hinglish runs longer than English, so text that
would overflow is scaled down to fit its own page rather than spilling onto
the next. Pages with no text (pictures, blank pages) stay as pages, so the
numbering never shifts. The file goes to **Downloads** through MediaStore, so
no storage permission is needed on Android 10+.

## Limitations

- **PDF structure is inferred.** PDFs store positioned text, not headings or
  lists, so structure is recovered from font sizes, bold text, bullet glyphs,
  numbering and indentation. It works well on documents exported from Word,
  Google Docs or LibreOffice; unusual layouts (multi-column magazines, tables)
  come out as plain paragraphs. Each output page is rebuilt from that
  structure; it is not a pixel copy of the original page.
- **EPUB output is a true copy** (same chapters, styles, images, table of
  contents, lists), but bold or links *inside* a sentence are flattened,
  because the model rewrites the whole sentence. A block that is entirely bold
  or a link keeps its formatting.
- **Scanned PDFs** contain images, not text; the app reports "No text found".
  Run OCR on them first.
- **Speed** depends on the phone. Llama 3.2 3B writes roughly 5–20 tokens/s
  on recent phones, so a dense page takes about 1–4 minutes and a 300-page
  book can take most of a day (more with cooling pauses). Keep the phone
  charging.
- **Images are not copied** into the translated PDF; pages that held only
  images stay in place as pages with a note.
- **Battery optimisation:** some phone brands (Xiaomi, Oppo, Vivo...) kill
  background apps aggressively even with a foreground service. If translation
  stops when the screen is off, set the app's battery usage to "Unrestricted".
- **Small models are imperfect.** Lines that come back in Devanagari or go
  missing are retried one at a time; if a retry also fails, the original text
  is kept for that line rather than losing it.
