# Hinglish Book Translator (Android)

A 100% offline Android app that translates whole books (PDFs of 200+ pages,
and EPUBs) into modern, casual **Hinglish** (Hindi written in Roman script)
with **Qwen 2.5 1.5B Instruct** running on the phone's **GPU**.

- **Page by page, in micro-chunks:** each PDF page is extracted on its own,
  cut into 100–150 word micro-chunks for the model, and stitched back
  together, so page N of the output is page N of the original.
- **Structure kept:** headings, bullet points (with nesting), numbering and
  paragraph breaks are detected on every page and restored after translation.
- **GPU inference:** MediaPipe's GPU delegate (OpenCL), temperature 0.2. The
  CPU is never used unless you switch it on yourself.
- **Background:** a foreground service with a persistent "Translating page 45
  of 300..." notification keeps running with the screen locked.
- **Crash-proof:** every page is saved to Room the moment it is done; after a
  crash, force-stop or reboot it resumes at the first untranslated page.
- **Live preview:** each micro-chunk streams into the Compose UI as it is
  generated.
- **Export:** a new PDF with the original page breaks and page sizes, saved to
  Downloads. EPUBs are saved as a translated copy of the same EPUB.
- **No network:** the manifest strips `INTERNET`, so nothing can leave the device.

Tech: Kotlin, Jetpack Compose (Material 3), MediaPipe Tasks GenAI, Room,
a foreground service, Coroutines/Flow, `pdfbox-android` for reading and
Android's `PdfDocument` for writing.

## The model: Qwen 2.5 1.5B Instruct

| | |
|---|---|
| File | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task` from [litert-community](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct) |
| Size | ~1.6 GB, int8 weights (the smallest build published; there is no 4-bit one) |
| License | Apache-2.0, no sign-in needed, so it can be bundled in the app |
| Speed (GPU, S25 Ultra, Google's benchmark) | ~1,670 tokens/s reading the prompt, ~31 tokens/s writing |
| Context | 1280 tokens; a micro-chunk prompt plus its answer needs under ~1,000 |
| Sampling | temperature 0.2, top-k 40, a fresh session per micro-chunk |

**Why the GPU and not the NPU:** MediaPipe has no NPU backend. The Snapdragon
NPU needs a model compiled for each chip with Qualcomm's AI Hub/Genie
toolchain, and Qualcomm's own Qwen 2.5 page is marked deprecated. Google's
NPU builds exist only for Gemma on Pixel's Tensor chip. The GPU delegate is
the fastest accelerator available for Qwen today.

**No silent CPU fallback:** if the GPU cannot load the model, the app shows
an error and an **Allow CPU** switch (off by default) instead of quietly
running slowly.

### Getting the model onto the phone

Pick **one**:

1. **In the app:** tap **Download Qwen**. It opens the download in your
   browser, once only, and the app itself never goes online. Then tap
   **Import model** and pick the downloaded `.task` file. It is copied into the
   app's private storage, so you can delete the download afterwards.
2. **Bundled in the APK** (app size up to ~1.7 GB):
   `./gradlew assembleRelease -PembedModel`. The *build machine* downloads the
   model into `app/src/main/assets/` once and packs it into the arm64 APK. On
   first launch the app unpacks it to internal storage (MediaPipe needs a real
   file path), so the phone needs ~3.3 GB free that first time.
3. **adb:** push the `.task` file to
   `/sdcard/Android/data/com.example.hinglishpdf/files/models/` and tap
   **Rescan**.

## Build

Requirements: JDK 17+, Android SDK 35.

```bash
cd apps/hinglish-pdf-translator
./gradlew testDebugUnitTest assembleDebug          # tests + debug APKs
./gradlew assembleRelease                          # R8-shrunk, ~16 MB per ABI
./gradlew assembleRelease -PembedModel             # with Qwen inside (~1.65 GB)
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

The build makes one APK per ABI: `arm64-v8a` for phones, `x86_64` for the
emulator (only arm64 when the model is embedded). Release APKs are unsigned;
sign them with your own key before installing.

## Project structure

```
app/
├── build.gradle.kts                Dependencies, ABI splits, -PembedModel download task
├── proguard-rules.pro
└── src/main/
    ├── AndroidManifest.xml         Foreground service (specialUse), wake lock, notifications, no INTERNET
    └── java/com/example/hinglishpdf/
        ├── HinglishApp.kt          App-wide singletons (DB, model, service helpers)
        ├── MainActivity.kt
        ├── service/
        │   ├── TranslationService.kt   Foreground service: extract → translate page by page → export
        │   ├── ThermalGovernor.kt      Rest every 5 pages; longer when the phone reports heat
        │   ├── Notifications.kt        "Translating page 45 of 300..." + Pause, finished/failed
        │   └── LiveStatus.kt           Live page, micro-chunk and streaming text for the screen
        ├── data/
        │   ├── db/                     Room: books, pages (PK = bookId + pageNumber), DAOs
        │   ├── pdf/
        │   │   ├── PdfTextExtractor.kt     PDFBox lines with font size, weight and position, per page
        │   │   └── PdfLayoutAnalyzer.kt    Headings, nested bullets, numbering, paragraphs per page
        │   ├── epub/EpubBook.kt        EPUB reading and translated copy
        │   ├── document/
        │   │   ├── BookImporter.kt     Private copy of the book + its pages
        │   │   ├── PdfExporter.kt      New PDF, one page per source page, same page size
        │   │   └── DocBlock.kt, DocumentFormatter.kt
        │   ├── export/BookExporter.kt  Writes the result to Downloads via MediaStore
        │   ├── translate/
        │   │   ├── BlockChunker.kt     Page → 100-150 word micro-chunks of whole blocks
        │   │   └── PageTranslator.kt   Micro-chunks → model → stitched page, as a Flow
        │   └── llm/
        │       ├── HinglishPrompt.kt   The system prompt (verbatim) + answer parser
        │       ├── MediaPipeEngine.kt  Qwen on the GPU delegate, temperature 0.2
        │       ├── LlmTranslator.kt    Owns the one loaded engine
        │       ├── ModelController.kt, ModelFileManager.kt, AppSettings.kt
        └── ui/                         Compose screen + ViewModel
```

## How it works

```
Select PDF/EPUB ─► BookImporter.copyIn ─► Room: book (QUEUED) ─► TranslationService
                                                                   │
  1. Extract pages once (PDFBox + PdfLayoutAnalyzer) ─► Room: pages │ (one transaction)
  2. loop: page = first page with translations IS NULL              │
        BlockChunker: page → micro-chunks of 100-150 words          │
        for each micro-chunk:                                       │
            SYSTEM_PROMPT with the chunk as Markdown:               │
                # Chapter 4                                         │
                It was a cold morning.                              │
                - Bring a coat                                      │
                2. Leave early                                      │
            Qwen on the GPU streams tokens ─► service ─► UI         │
            answer matched back block by block                      │
        stitch the blocks ─► Room: save page N immediately          │
        ThermalGovernor: pause every 5 pages / when hot             │
  3. PdfExporter: page N → page N, original size ─► Downloads       │
```

### The prompt

[`HinglishPrompt.SYSTEM_PROMPT`](app/src/main/java/com/example/hinglishpdf/data/llm/HinglishPrompt.kt)
is the specified prompt, character for character (a unit test checks it).
The micro-chunk replaces `[INSERT MICRO-CHUNK HERE]`. The chunk is written as
plain Markdown, one block per paragraph (`# heading`, `- bullet`, `2. step`),
which the prompt's "strictly preserve the original document's formatting,
paragraphs, and bullet points" rule asks the model to keep.

### Getting the structure back

The answer is split into blocks at blank lines and at lines that start a new
heading, bullet or numbered item, and a leading "Hinglish:" label (which the
prompt's examples invite) is dropped.

- If the block count matches the micro-chunk, block *i* is the translation of
  block *i*, and the app re-applies the original marker. A bullet stays a
  bullet even if the model drops the `-`.
- If the count does not match (the model merged or split paragraphs), nothing
  is guessed: each block of that micro-chunk is translated on its own.
- A block that comes back in Devanagari is retried on its own too. If it
  still fails, the original text is kept rather than lost.

### Threads

Inference and parsing run on `Dispatchers.Default` (`PageTranslator` is a
`Flow` with `flowOn(Dispatchers.Default)`). The service publishes progress to
a `StateFlow`. The ViewModel prepares the live page on `Dispatchers.Default`
(conflated, so a slow frame never holds back the model) and Compose collects
it on the main thread.

### Fault tolerance

- `pages` has primary key `(bookId, pageNumber)`. A page's `translations`
  column is NULL until all of its micro-chunks are done, and the page is
  written as soon as they are.
- Resuming means "first page where translations IS NULL". There is no cursor
  that could go stale. A crash mid-page redoes only that page.
- The service is `START_STICKY`. If Android kills it, Android restarts it and
  it resumes. After a crash or reboot, opening the app restarts it.
- A partial wake lock keeps the CPU feeding the GPU with the screen off.

### Export

`PdfExporter` writes one output page per source page at the source page's
size, with the page number in the footer. Headings, bullets and numbering are
laid out like the original. Hinglish runs longer than English, so text that
would overflow is scaled down to fit its own page rather than spilling onto
the next. Pages with no text stay as pages, so the numbering never shifts.
The file goes to **Downloads** through MediaStore, which needs no storage
permission on Android 10+ (this app's minimum).

## Limitations

- **Not yet run on a phone.** The build, the unit tests (prompt, parser,
  micro-chunking, page stitching, Room resume) and the EPUB/PDF structure
  checks pass. GPU loading, real Qwen output quality and the notification
  flow still need a device.
- **GPU support varies by phone.** Recent Snapdragon, Dimensity and Exynos
  phones with OpenCL should work. If a phone's GPU cannot run the model, the
  app says so; the **Allow CPU** switch is the fallback.
- **PDF structure is inferred.** PDFs store positioned text, not headings or
  lists, so structure is recovered from font sizes, bold text, bullet glyphs,
  numbering and indentation. It works well on documents exported from Word,
  Google Docs or LibreOffice; unusual layouts (multi-column magazines, tables)
  come out as plain paragraphs. Each output page is rebuilt from that
  structure; it is not a pixel copy of the original page.
- **Images are not copied** into the translated PDF; pages that held only
  images stay in place as pages with a note.
- **EPUB output** keeps chapters, styles, images and lists, but bold or links
  *inside* a sentence are flattened, because the model rewrites the whole
  sentence.
- **Scanned PDFs** contain images, not text; the app reports "No text found".
  Run OCR on them first.
- **Battery optimisation:** some phone brands (Xiaomi, Oppo, Vivo...) kill
  background apps aggressively even with a foreground service. If translation
  stops when the screen is off, set the app's battery usage to "Unrestricted".
