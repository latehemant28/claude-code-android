# Hindi Book Translator (Android)

An Android app that translates whole books (PDFs and EPUBs) **from any
language into any of 27 languages** (Hindi by default), in natural,
contemporary language, the way educated native speakers talk today, page by
page, in the background, with the AI provider you choose: **Google Gemini**,
**OpenAI**, **Anthropic Claude** or **Groq**.

- **From / To dropdowns:** "From: Auto-Detect / English / Hindi / ..." and
  "To: Hindi / Spanish / French / ..."; each book keeps the pair it was added
  with.
- **Modern, sense-for-sense:** one prompt for every language pair that asks
  for context first, everyday vocabulary (no Sanskritized Hindi, no archaic
  Spanish), adapted idioms, and untouched names and speaker labels.
- **First-launch tutorial:** three swipeable slides (Compose HorizontalPager)
  shown once, on the very first launch.
- **Any of four providers:** pick one from a dropdown; each has its own
  translator class (Strategy pattern) for its endpoint and JSON. **Get API
  Key** opens the provider's own key page inside the app; copy a key there,
  close it, and the key is pasted for you.
- **PDF or EPUB output:** an **Output Format: [ PDF | EPUB ]** toggle (EPUB
  by default) decides what **Save to Downloads** writes, whatever the source.
- **Structure kept:** headings, bullet points (with nesting), numbering and
  paragraph breaks are restored after translation.
- **Model fallback:** a prioritized list of models per provider; a retired,
  out-of-quota or rate-limited model is swapped for the next one automatically.
- **Reading UI:** Devanagari fonts from Google Fonts (Mukta, Noto Serif
  Devanagari, Kalam) and an **Aa** sheet for font style and text size.
- **Background and crash-proof:** a foreground service with a "Translating
  page 45 of 300..." notification; every page is saved to Room the moment it
  is done, and a restart resumes at the first untranslated page.
- **Terms of Use:** a disclaimer that must be accepted before the first
  translation, and always available from the ⋮ menu.
- **Small:** ~6.9 MB APK, including the embedded Noto fonts.

Tech: Kotlin, Jetpack Compose (Material 3, downloadable Google Fonts), the
providers' HTTPS streaming APIs (HttpURLConnection + Gson, no provider SDK),
Room, a foreground service, Coroutines/Flow, `pdfbox-android` for reading,
Android's `PdfDocument` for writing PDFs, and `java.util.zip` for EPUBs.

> **Privacy:** the text of your books is sent to the provider you select to
> be translated. On Gemini's free tier, Google's terms say content may be
> used to improve its products. The app has no server of its own.

## Setup: pick a provider and add its API key

At the top of the screen:

1. **AI provider** dropdown: Google Gemini, OpenAI, Anthropic Claude or Groq.
2. **Get API Key** opens that provider's official key dashboard in an in-app
   browser:

   | Provider | Key page | Cost |
   |---|---|---|
   | Google Gemini | https://aistudio.google.com/app/apikey | Free tier (rate-limited) |
   | OpenAI | https://platform.openai.com/api-keys | Paid per use |
   | Anthropic Claude | https://console.anthropic.com/settings/keys | Paid per use |
   | Groq | https://console.groq.com/keys | Free tier (small limits) |

   Sign in, create a key, tap the site's **Copy** button, and close the
   browser (✕). If the clipboard holds something that looks like a key, it is
   pasted into the key field; check it and tap **Save key**. The paste button
   in the field does the same by hand.
3. **Model (optional):** leave empty to use the provider's defaults in order,
   or type a model name to try first.

Keys are kept per provider, only on the phone (app-private storage, excluded
from backups), never in code or git.

**Google sign-in inside apps:** Google blocks some sign-ins in embedded
browsers ("This browser or app may not be secure"). If that happens, tap the
open-in-browser button in the in-app browser's top bar, copy the key in
Chrome, come back and use the paste button.

### Gemini key at build time (optional)

A Gemini key can also be compiled in from `local.properties` (git-ignored):

```properties
sdk.dir=/path/to/Android/sdk
GEMINI_API_KEY=my_actual_key_here
```

`app/build.gradle.kts` reads it into `BuildConfig.GEMINI_API_KEY`, which takes
priority over a Gemini key pasted in the app. A key compiled into an APK can
be extracted by anyone who has that APK, so don't publish such an APK.

### The translator strategies

[`data/ai`](app/src/main/java/com/example/hinglishpdf/data/ai) holds one
`AITranslator` implementation per provider, all sharing
`HttpStreamingTranslator` (one streamed HTTPS request, server-sent events):

| Class | Endpoint | Prompt goes in | Text arrives in |
|---|---|---|---|
| `GeminiTranslator` | `…/v1beta/models/{model}:streamGenerateContent?alt=sse` (`x-goog-api-key`) | `systemInstruction` | `candidates[0].content.parts[].text` (thoughts skipped) |
| `OpenAITranslator` | `api.openai.com/v1/chat/completions` (Bearer) | `system` message | `choices[0].delta.content` |
| `GroqTranslator` | `api.groq.com/openai/v1/chat/completions` (Bearer) | `system` message | `choices[0].delta.content` |
| `AnthropicTranslator` | `api.anthropic.com/v1/messages` (`x-api-key`, `anthropic-version`) | `system` | `content_block_delta` events |

`AIProvider.createTranslator()` instantiates the right class for the
provider selected in the app. Each class also sorts its provider's error
answers into what the app should do (wait, swap model, stop with a message).
If a model rejects the temperature (some newer models accept only their
default), the request is repeated without one and the model is remembered.

### Which model: automatic fallback

Each provider has a prioritized list (`AIProvider.defaultModels`):

| Provider | Models, in order |
|---|---|
| Gemini | `gemini-1.5-flash`, `gemini-1.5-pro` (both retired by Google on 29 Sept 2025), `gemini-3.5-flash-lite`, `gemini-3.8-flash` |
| OpenAI | `gpt-4.1-mini`, `gpt-4o-mini`, `gpt-5-mini` |
| Anthropic | `claude-haiku-5-5`, `claude-sonnet-5-5`, `claude-haiku-4-5` |
| Groq | `llama-3.3-70b-versatile`, `openai/gpt-oss-120b` |

A model typed in the app's Model field is tried first. `FallbackTranslator`
tries them in order and moves on silently when a model fails in a way
another model can fix:

| Failure | What happens |
|---|---|
| Retired / not found, or "limit: 0" for this key | Skipped for the rest of the app session |
| Daily quota used up | Skipped for an hour, then tried again |
| Rate-limited (429) | Skipped until the provider says it can retry; earlier models are preferred again afterwards |
| Bad key, no credit, region, refused text, no internet | Not hidden: another model would not help |
| Every model rate-limited | Waits for the first one to free up |
| Every model retired | Stops with a message to type a current model name |

A model that fails *after* it started answering is never spliced with another
model's text; the whole request is retried. The provider card shows which
model actually answered.

## Build

Requirements: JDK 17+, Android SDK 35.

```bash
cd apps/hinglish-pdf-translator
./gradlew testDebugUnitTest assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew assembleRelease` gives an R8-shrunk, unsigned APK (~6.9 MB); sign
it with your own key before installing.

## How it works

```
Select PDF/EPUB ─► BookImporter.copyIn ─► Room: book (QUEUED) ─► TranslationService
                                                                   │
  1. Extract pages once (PDFBox + PdfLayoutAnalyzer) ─► Room: pages │ (one transaction)
  2. loop: page = first page with translations IS NULL              │
        TranslationRepository.translatePage(page):                  │
          BlockChunker: page → chunk(s) of whole blocks (≤800 words)│
          request = the chunk (system prompt = the prompt):          │
              # Chapter 4                                           │
              It was a cold morning.                                │
              - Bring a coat                                        │
              2. Leave early                                        │
          AITranslator for the selected provider (streamed) ─► UI   │
          answer matched back block by block                        │
        Room: save page N immediately                               │
  3. Output Format toggle: EpubWriter / EpubBook or PdfExporter ─► Downloads
```

### Languages and the prompt

The **From** and **To** dropdowns at the top of the screen set the language
pair for the next book added ([`Language.kt`](app/src/main/java/com/example/hinglishpdf/data/llm/Language.kt)):
Auto-Detect (From only), English, Hindi, Spanish, French, German, Portuguese,
Italian, Dutch, Russian, Turkish, Arabic, Urdu, Bengali, Marathi, Nepali,
Gujarati, Punjabi, Tamil, Telugu, Kannada, Malayalam, Japanese, Korean,
Chinese (Simplified), Indonesian, Vietnamese and Thai. ⇄ swaps them. The pair
is saved with the book (database version 2 added a `sourceLanguage` column;
books from earlier versions read as Auto-Detect → Hindi), so a book resumes
in its own languages even if the dropdowns change.

[`TranslationPrompt.TEMPLATE`](app/src/main/java/com/example/hinglishpdf/data/llm/TranslationPrompt.kt)
is the specified prompt, word for word (a unit test compares it with the
specified text, trailing spaces included):

```
You are a master literary and context-aware translator.
Translate the following text from {sourceLanguage} to {targetLanguage}.

CRITICAL TONE & STYLE RULES:
1. CONTEXT FIRST, TRANSLATE SECOND: ...
2. MODERN & CONVERSATIONAL: ...
3. NATURAL FLOW: ...
4. EMOTION SENSE-FOR-SENSE: ...

FORMATTING RULES:
1. STRICT RETENTION: ...
2. DO NOT TRANSLATE TAGS: ...
3. COMPLETENESS: ...
```

`TranslationRepository.translatePage(blocks, source, target)` fills in the
book's languages (`TranslationPrompt.system`; Auto-Detect becomes "its
original language (detect it automatically)") and every provider receives
the result as its **system prompt**; each request carries only the chunk of
text. Temperature is 0.2 (unless a model refuses one), and Gemini's safety
filters are set to `BLOCK_NONE` so ordinary literature isn't refused mid-book.

Exports follow the target language: the file is named "Book (Spanish).epub",
the EPUB is labelled with the language code (`dir="rtl"` for Arabic and
Urdu), Noto Sans Devanagari is embedded for Devanagari languages only, the
PDF mirrors lists and indents for right-to-left text, and the few words the
app writes itself (Contents, Pages 1–20) are in Hindi for Hindi books and in
English otherwise.

### First-launch tutorial

[`OnboardingScreen`](app/src/main/java/com/example/hinglishpdf/ui/onboarding/OnboardingScreen.kt)
is a three-slide Compose `HorizontalPager` on the icon's deep-purple
gradient, with the specified titles and texts:

| Slide | Title | Visual (animated, drawn in Compose) |
|---|---|---|
| 1 | Your Rules, Your Language | The app's PDF → अ artwork floating and tilting in 3D, with letters from ten scripts orbiting around it |
| 2 | Choose Your AI Engine | A processor chip wired to Gemini, OpenAI, Claude and Groq; a pulse runs to each "engine" in turn |
| 3 | Recommended AI Providers | A glowing "recommended" badge; the three recommendations as cards, the "Don't worry..." note and the **Let's Get Started!** button |

Next / page dots / Skip move through it; **Let's Get Started!** opens the
main screen. A flag in SharedPreferences is written the moment the tutorial
first appears, so it shows by itself only on the very first launch (also for
people updating from an older version, once). It can be replayed from the ⋮
menu → **Show the tutorial**, and ⋮ → **AI provider & API key** jumps to the
provider card. The visuals are drawn and animated in Compose instead of a
Lottie file: no extra library and no third-party animation to license.

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
Devanagari font is used. Exports use the bundled Noto fonts (see Export).

### Getting the structure back

The chunk is written as Markdown, one block per paragraph. The AI's answer is
split the same way; code fences, a leading "Modern Hindi:" label (the prompt's
examples use one) and quotes wrapped around the whole answer are removed first.
Speaker labels such as "YOUTH:" are part of the text and stay.

- **Block count matches:** block *i* is the translation of block *i*, and the
  app re-applies the original marker. A bullet stays a bullet even if the AI
  drops the `-`.
- **Count doesn't match** (merged or split paragraphs): nothing is guessed.
  The chunk is translated again in two halves, recursively, until it lines up.
- **A block comes back empty:** it is retried on its own.
- **The AI refuses some text** (or it is too long for one request): it is
  split, and text that is still refused stays in English instead of stopping
  the book.

### Limits and errors

[`TranslationRepository.kt`](app/src/main/java/com/example/hinglishpdf/data/TranslationRepository.kt)
paces requests and handles errors as follows:

| Situation | What the app does |
|---|---|
| Every chunk | A fixed pause after each translated chunk: **4.5 s** for Gemini and Groq (about 13 requests a minute, under Gemini's free 15), 1 s for OpenAI and Claude |
| Rate limit (429) | Another model of the provider is tried first; if all are limited, **waits** (60 s for Gemini, 30 s Groq, 20 s OpenAI/Claude, or longer if the provider asks) and tries again, **with no retry limit**. The notification shows the provider's own message |
| "limit: 0" (no quota for this model and key) | The model is skipped for the session, like a retired one |
| Daily quota used up | Stops the book with a clear message; **Resume** later continues at the same page |
| Network drop, timeout, 5xx, overloaded, unreadable answer | Retried with backoff (15 s → 5 min), up to 8 times, then stops; **Resume** continues at the same page |
| Invalid key, no billing credit, region, or no usable model | Stops with a message saying what to fix |

**Pause** closes the open connection at once, even mid-answer.

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

### Export: PDF or EPUB

The **Output Format: [ PDF | EPUB ]** toggle at the top of the screen (EPUB
by default) decides what **Save to Downloads**, and the automatic save when a
book finishes, write. Files go to **Downloads** through MediaStore, which
needs no storage permission on Android 10+ (this app's minimum). Pages not
translated yet keep their original text, so a paused book can be saved too.

| Source → output | How |
|---|---|
| EPUB → EPUB | The same EPUB with its text translated: chapters, styles, images and table of contents are kept; chapters and the package are re-labelled `hi` so readers pick a Hindi font |
| PDF → EPUB | [`EpubWriter`](app/src/main/java/com/example/hinglishpdf/data/epub/EpubWriter.kt) builds a new **EPUB 3**: UTF-8 XHTML chapters split at the book's top-level headings (or every 20 pages), nested `<ul>`/`<ol>` lists with the original markers, a table of contents (plus a legacy `toc.ncx`), a page list that maps to the original page numbers, `xml:lang="hi"`, and Noto Sans Devanagari embedded |
| PDF or EPUB → PDF | [`PdfExporter`](app/src/main/java/com/example/hinglishpdf/data/document/PdfExporter.kt) lays the text out on **A4 pages**: lines break properly and text flows on to the next page ([`PdfPaginator`](app/src/main/java/com/example/hinglishpdf/data/document/PdfPaginator.kt): headings stay with their text, no lone first or last lines). Drawn with the bundled **Noto Sans Devanagari** + **Noto Sans** (Latin), which the PDF embeds, so Hindi renders correctly in any viewer. From a PDF, "— 45 —" marks where page 45 of the original begins; from an EPUB, each chapter starts on a new page |

The generated EPUBs are checked in the unit tests with the W3C's official
validator, **EPUBCheck 5.1** (no errors, no warnings). The fonts are under the
SIL Open Font License (`app/src/main/assets/fonts/OFL.txt`).

### Terms of Use

Before the first translation (selecting a book, or Resume on a book from an
older version), the **Terms of Use** dialog appears with this disclaimer:

> Disclaimer: This app is purely a translation tool. Users are solely
> responsible for ensuring they have the necessary rights and permissions for
> any documents they upload and translate. The app does not claim ownership of
> any content, does not store files on our servers, and is strictly meant for
> personal use to comply with copyright laws.

**I Agree** is enabled only after ticking "I have read the disclaimer and I
agree to these Terms of Use". **Not now** closes the dialog and nothing is
translated. The acceptance (with its date) is saved on the phone; the
background service also refuses to run a book until it is given. The text is
always available from the ⋮ menu → **Terms of Use & Disclaimer**, and a link
under "Select PDF / EPUB".

## Project structure

```
app/
├── build.gradle.kts                Dependencies; GEMINI_API_KEY (local.properties) → BuildConfig
├── proguard-rules.pro
└── src/main/
    ├── AndroidManifest.xml         INTERNET, foreground service (specialUse), wake lock, notifications
    ├── assets/fonts/               Noto Sans Devanagari + Noto Sans (Latin subset) for exports, OFL.txt
    ├── res/mipmap-*/               Launcher icon: adaptive (purple background + PDF→अ artwork), legacy, round
    └── java/com/example/hinglishpdf/
        ├── HinglishApp.kt          App-wide singletons (DB, translation engine for the selected provider, settings)
        ├── MainActivity.kt
        ├── service/
        │   ├── TranslationService.kt   Foreground service: extract → translate page by page → export
        │   ├── Notifications.kt        "Translating page 45 of 300..." + Pause, finished/failed
        │   └── LiveStatus.kt           Live page and streaming text for the screen
        ├── data/
        │   ├── TranslationRepository.kt  Pacing, retries, page Flow (provider-independent)
        │   ├── ai/                       AITranslator strategies: Gemini, OpenAI, Groq, Anthropic;
        │   │                             AIProvider (key pages, models), FallbackTranslator
        │   ├── llm/                      Language (From / To list), TranslationPrompt (prompt + answer parser)
        │   ├── settings/                 Provider + keys, languages, output format, Terms, tutorial flag
        │   ├── db/                       Room: books, pages (PK = bookId + pageNumber), DAOs
        │   ├── pdf/                      PDFBox extraction + layout analysis per page
        │   ├── epub/                     EpubBook (read, translated copy), EpubWriter (new EPUB 3)
        │   ├── document/                 Import, blocks, PDF export (PdfExporter, PdfPaginator, BundledFonts)
        │   ├── export/BookExporter.kt    Writes the result to Downloads via MediaStore
        │   └── translate/BlockChunker.kt Page → chunks of whole blocks
        └── ui/                           Compose screen + ViewModel, language and provider cards,
                                          in-app key browser, Terms dialog; onboarding/ = tutorial;
                                          reader/ = fonts + Aa sheet
```

## Limitations

- **Not yet run on a phone.** The build, lint, the unit tests (providers'
  requests and errors against a local server, fallback, prompt, parser,
  chunking, pagination, EPUB output validated with EPUBCheck, Room resume and
  the version 1 → 2 migration, the tutorial and language pickers rendered with
  Robolectric) and
  live requests to all four providers' real endpoints (each correctly
  rejected a dummy key) pass. A real translation with a valid key, the in-app
  browser and the PDF rendering still need to be tried on a device.
- **Google sign-in in the in-app browser** may be refused by Google; use the
  open-in-browser button then (see Setup).
- **Model names change.** If every default model of a provider is retired,
  type a current one in the Model field.
- **Free-tier limits** cap requests per minute and per day. A long book may
  take more than one day; it resumes where it stopped.
- **PDF structure is inferred** from font sizes, bold text, bullet glyphs,
  numbering and indentation. Unusual layouts (multi-column, tables) come out as
  plain paragraphs. Output pages are newly typeset, not a copy of the original.
- **Images are not copied** into exports made from a PDF; picture-only pages
  keep their place (a "— 45 —" marker) with a note.
- **Scanned PDFs** contain images, not text; run OCR on them first.
- **Battery optimisation:** some brands (Xiaomi, Oppo, Vivo...) kill
  background apps aggressively. If translation stops with the screen off, set
  the app's battery usage to "Unrestricted".
