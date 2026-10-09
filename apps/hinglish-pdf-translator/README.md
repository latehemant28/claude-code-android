# Hinglish PDF Translator (Android)

A 100% offline Android app: pick a PDF or EPUB, and an on-device LLM
translates it into conversational Hinglish (Hindi written in the Latin
alphabet). The headings, bullet points, numbering and paragraph breaks of
the original are kept, on screen and in the saved file.

- **Kotlin + Jetpack Compose (Material 3)**
- **PDF:** `com.tom-roush:pdfbox-android`, with layout analysis to recover structure
- **EPUB:** `java.util.zip` + `org.jsoup:jsoup`; the output is the same EPUB with translated text
- **On-device inference:** LiteRT-LM (`.litertlm`, e.g. Llama 3.2 3B) and MediaPipe (`.task`)
- **Concurrency:** Coroutines and Flow, end to end
- **No network:** the manifest strips `INTERNET`, so nothing can leave the device

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

Alternatives: `adb push` a model to
`/sdcard/Android/data/com.example.hinglishpdf/files/models/` and tap
**Rescan**, or bundle a small one in `app/src/main/assets/`.

The token budget is read from the file name when it says (`..._ekv1280.task`
means 1280); otherwise 4096 for `.litertlm` and 2048 for `.task`. The amount
of text per prompt is sized from it automatically.

## How it works

```
Select PDF / EPUB (SAF)
   │
   ▼
DocumentLoader ── copies the file, detects the format
   │   PDF:  PdfTextExtractor (lines + fonts + positions) → PdfLayoutAnalyzer
   │   EPUB: EpubBook.read (spine order, XHTML elements)
   ▼
List<DocBlock>   HEADING(level) · BULLET(depth) · NUMBERED(marker) · PARAGRAPH · QUOTE · CODE
   │
   ▼
BlockChunker ── packs whole blocks into prompt-sized chunks
   │
   ▼
for each chunk: HinglishPrompt.build →  [1] ## Heading
   │                                    [2] - bullet
   │                                    [3] 2. numbered step
   │   LlmTranslator.generate ── Flow<String> (streamed to the screen)
   │   HinglishPrompt.parse   ── answers matched back by [ID]; skipped or
   │                             Devanagari lines are retried one by one
   ▼
TranslatorViewModel ── StateFlow ──► TranslatorScreen (renders the structure)
   │
   ▼
Save: EPUB → same EPUB, text replaced in place (EpubBook.writeTranslated)
      PDF  → new PDF with the same headings/lists/paragraphs (PdfExporter)
Copy: plain text with •, ◦, 1., indentation and blank lines (DocumentFormatter)
```

### The prompt

Every chunk is sent with these guidelines (see
[`HinglishPrompt.kt`](app/src/main/java/com/example/hinglishpdf/data/llm/HinglishPrompt.kt)):

- **Vocabulary:** keep technical terms, proper nouns and industry jargon in
  English; translate everyday verbs, connectors and descriptive words into
  Roman Hindi (kaam, lekin, zaroori, samajh).
- **Script:** 100% Latin letters, no Devanagari.
- **Flow:** natural conversation between modern bilingual speakers.
- **Formatting:** keep headings, bullet points, numbering and paragraph breaks.

The structure is not left to the model: each block goes in as one tagged
line and the app re-applies the original markers to whatever comes back, so
a bullet stays a bullet even if the model drops the `-`.

### Main files

| File | Role |
|---|---|
| [`data/document/DocumentLoader.kt`](app/src/main/java/com/example/hinglishpdf/data/document/DocumentLoader.kt) | Picked file → `SourceDocument` (PDF or EPUB) |
| [`data/pdf/PdfTextExtractor.kt`](app/src/main/java/com/example/hinglishpdf/data/pdf/PdfTextExtractor.kt) | PDFBox lines with font size, weight and position |
| [`data/pdf/PdfLayoutAnalyzer.kt`](app/src/main/java/com/example/hinglishpdf/data/pdf/PdfLayoutAnalyzer.kt) | Headings, nested lists, paragraphs; drops page numbers and running headers |
| [`data/epub/EpubBook.kt`](app/src/main/java/com/example/hinglishpdf/data/epub/EpubBook.kt) | EPUB reading and in-place translated copy |
| [`data/translate/BlockChunker.kt`](app/src/main/java/com/example/hinglishpdf/data/translate/BlockChunker.kt) | Prompt-sized chunks that never split a block's structure |
| [`data/llm/HinglishPrompt.kt`](app/src/main/java/com/example/hinglishpdf/data/llm/HinglishPrompt.kt) | Guidelines, tagged lines, answer parsing |
| [`data/llm/LlmTranslator.kt`](app/src/main/java/com/example/hinglishpdf/data/llm/LlmTranslator.kt) | Picks LiteRT-LM or MediaPipe from the model file |
| [`data/llm/LiteRtLmEngine.kt`](app/src/main/java/com/example/hinglishpdf/data/llm/LiteRtLmEngine.kt) / [`MediaPipeEngine.kt`](app/src/main/java/com/example/hinglishpdf/data/llm/MediaPipeEngine.kt) | The two runtimes, GPU first with CPU fallback |
| [`data/TranslationRepository.kt`](app/src/main/java/com/example/hinglishpdf/data/TranslationRepository.kt) | The whole pipeline as one Flow of events |
| [`data/document/PdfExporter.kt`](app/src/main/java/com/example/hinglishpdf/data/document/PdfExporter.kt) | Writes the translated PDF |
| [`ui/TranslatorViewModel.kt`](app/src/main/java/com/example/hinglishpdf/ui/TranslatorViewModel.kt) / [`TranslatorScreen.kt`](app/src/main/java/com/example/hinglishpdf/ui/TranslatorScreen.kt) | State and Material 3 UI |

## Limitations

- **PDF structure is inferred.** PDFs store positioned text, not headings or
  lists, so structure is recovered from font sizes, bold text, bullet glyphs,
  numbering and indentation. It works well on documents exported from Word,
  Google Docs or LibreOffice; unusual layouts (multi-column magazines, tables)
  come out as plain paragraphs. A saved PDF is rebuilt from that structure,
  not a pixel copy of the original pages.
- **EPUB output is a true copy** (same chapters, styles, images, table of
  contents, lists), but bold or links *inside* a sentence are flattened,
  because the model rewrites the whole sentence. A block that is entirely bold
  or a link keeps its formatting.
- **Scanned PDFs** contain images, not text; the app reports "No text found".
  Run OCR on them first.
- **Speed** depends on the phone: Llama 3.2 3B writes roughly 5–20 tokens/s
  on recent phones, so a 20-page document can take 15–30 minutes. The screen
  stays awake while translating; keep the app in the foreground.
- **Small models are imperfect.** Lines that come back in Devanagari or go
  missing are retried one at a time; if a retry also fails, the original text
  is kept for that line rather than losing it.
