# Hinglish PDF Translator (Android)

A 100% offline Android app: pick a PDF, and an on-device LLM translates it
into conversational Hinglish (Hindi written in the Latin alphabet), streaming
the output into the UI as it is generated.

- **Kotlin + Jetpack Compose (Material 3)**
- **PDF text extraction:** `com.tom-roush:pdfbox-android`
- **On-device inference:** `com.google.mediapipe:tasks-genai` (MediaPipe LLM Inference API)
- **Concurrency:** Coroutines and Flow, end to end
- **No network:** the manifest strips `INTERNET`, so nothing can leave the device

## Build

Requirements: JDK 17+, Android SDK 35.

```bash
cd apps/hinglish-pdf-translator
./gradlew testDebugUnitTest assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio.

## Getting a model onto the phone

MediaPipe needs a converted model file (`.task`, or `.bin` for older
conversions). Good choices, smallest first:

| Model | Size | Notes |
|---|---|---|
| Gemma 3 1B IT (int4) `.task` | ~0.5 GB | Fast on most phones; decent Hinglish |
| Gemma 2B IT (int4/int8) `.bin`/`.task` | 1.3–2.6 GB | Better fluency |
| Llama 3.2 3B Instruct (q8) `.task` | ~3 GB | Best quality; needs 8 GB+ RAM |

Download one from the LiteRT community on Hugging Face (accept the model's
licence first), then use **any one** of these:

1. **Import in the app** (easiest): tap **Import model** and pick the file.
   It is copied into the app's private storage.
2. **adb push** to app-specific storage (no permissions needed), then tap **Rescan**:
   ```bash
   adb shell mkdir -p /sdcard/Android/data/com.example.hinglishpdf/files/models
   adb push gemma3-1b-it-int4.task /sdcard/Android/data/com.example.hinglishpdf/files/models/
   ```
3. **Bundle it** in `app/src/main/assets/`. It is copied to internal storage on
   first launch. Only practical for small models; APKs over ~2 GB need
   Play Asset Delivery instead.

**Match `maxTokens` to your model.** `LlmConfig.maxTokens` (default 2048, in
`LlmTranslator.kt`) must not exceed the KV cache the model was exported with.
A file named `..._ekv1280.task` supports 1280 tokens, so set `maxTokens = 1280`
and lower the chunk size to about 250 words.

## How it works

```
Select PDF (SAF)
   │
   ▼
PdfTextExtractor ── Flow<PageText> ──► "Extracting text: page 3 of 12"
   │  (PDFBox, Dispatchers.IO, page by page)
   ▼
TextChunker.chunk(text, maxWords = 400)
   │  paragraphs → sentences → word windows, never above the limit
   ▼
for each chunk:  LlmTranslator.translate(chunk) ── Flow<String> (tokens)
   │  fresh LlmInferenceSession per chunk, prompt = INSTRUCTION + chunk
   ▼
TranslationRepository ── Flow<TranslationEvent> ──► TranslatorViewModel
                                                      │ StateFlow<TranslatorUiState>
                                                      ▼
                                              TranslatorScreen (Compose)
```

| File | Role |
|---|---|
| [`HinglishApp.kt`](app/src/main/java/com/example/hinglishpdf/HinglishApp.kt) | Initialises PDFBox, holds app-wide singletons |
| [`MainActivity.kt`](app/src/main/java/com/example/hinglishpdf/MainActivity.kt) | Hosts the Compose UI |
| [`data/pdf/PdfTextExtractor.kt`](app/src/main/java/com/example/hinglishpdf/data/pdf/PdfTextExtractor.kt) | SAF URI → per-page text, on IO, disk-backed for large PDFs |
| [`data/text/TextChunker.kt`](app/src/main/java/com/example/hinglishpdf/data/text/TextChunker.kt) | Word-limited chunking (unit-tested) |
| [`data/llm/HinglishPrompt.kt`](app/src/main/java/com/example/hinglishpdf/data/llm/HinglishPrompt.kt) | The exact instruction prepended to every chunk |
| [`data/llm/ModelFileManager.kt`](app/src/main/java/com/example/hinglishpdf/data/llm/ModelFileManager.kt) | Finds, imports or unbundles the model file |
| [`data/llm/LlmTranslator.kt`](app/src/main/java/com/example/hinglishpdf/data/llm/LlmTranslator.kt) | MediaPipe engine: GPU→CPU fallback, streaming, cancellation |
| [`data/TranslationRepository.kt`](app/src/main/java/com/example/hinglishpdf/data/TranslationRepository.kt) | The extract → chunk → translate pipeline as one Flow |
| [`ui/TranslatorViewModel.kt`](app/src/main/java/com/example/hinglishpdf/ui/TranslatorViewModel.kt) | UI state, model lifecycle, job control |
| [`ui/TranslatorScreen.kt`](app/src/main/java/com/example/hinglishpdf/ui/TranslatorScreen.kt) | Material 3 screen: pickers, progress, output, copy |

### Design notes

- **Each chunk gets its own session.** Earlier chunks never fill the context
  window, and memory stays flat no matter how long the PDF is.
- **Context guard.** Before generating, the prompt is measured with
  `sizeInTokens`; if it would leave less than ~55% of `maxTokens` for the
  answer, you get a clear error instead of a native crash or a truncated
  translation.
- **Streaming without O(n²) work.** The output is kept as one string per
  chunk and rendered in a `LazyColumn`, so each new token rebuilds only the
  current chunk, not the whole document.
- **Safe cancellation.** Cancel calls `cancelGenerateResponseAsync()` and waits
  for the native side to stop before closing the session.
- **Permissions:** none. Files are opened through the Storage Access Framework,
  and `INTERNET` is removed with `tools:node="remove"`. Optional
  `<uses-native-library>` entries let MediaPipe use the GPU via OpenCL.

## Limitations

- **Scanned PDFs** contain images, not text; the app reports "No text found".
  Run OCR on them first.
- **Speed** depends on the phone: a 1B model on a recent phone writes roughly
  10–30 tokens/s, so a 20-page document takes several minutes. The screen stays
  awake while translating; keep the app in the foreground.
- **Small models are imperfect:** sometimes one slips into Devanagari or adds a
  preamble ("Here is the translation:"). Larger models follow the instruction
  more reliably.
- **API status:** Google has marked the MediaPipe LLM Inference API as
  deprecated in favour of LiteRT-LM. It still works (this project pins 0.10.35),
  and all of the MediaPipe code is in `LlmTranslator.kt`, so moving to LiteRT-LM
  means rewriting that one class.
