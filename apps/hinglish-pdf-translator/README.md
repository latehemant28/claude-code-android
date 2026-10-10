# BYOK Translator (Android)

> **BYOK Model - 100% Free & Private Translation**: bring your own AI key;
> the app adds no subscription, no markup and no server of its own.

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
- **First launch:** a "Select your Target Language" popup, then a spotlight
  tour of the main screen (language → AI key → upload).
- **Resilient:** each provider is paced for its free tier; a rate limit
  pauses 60 s ("Pausing for 60s to refresh limit...") and retries by itself;
  if a provider fails for good (no credit, bad key, daily quota), the book
  carries on with another provider you have a key for.
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
- **Clean main screen:** a one-line AI status banner, From / To, Output
  Format and, right below, the big **Translate a book** card; provider, key
  and model live in a settings sheet.
- **In-app reader:** **Read Now** on a finished book opens it inside the app
  (PDF pages with `PdfRenderer`, EPUB chapters in a local WebView) with dark
  mode and text size / zoom.
- **Reading UI:** Devanagari fonts from Google Fonts (Mukta, Noto Serif
  Devanagari, Kalam) and an **Aa** sheet for font style and text size.
- **Background and crash-proof:** a foreground service with a "Translating
  page 45 of 300..." notification; every page is saved to Room the moment it
  is done, and a restart resumes at the first untranslated page.
- **Terms of Use:** a disclaimer that must be accepted before the first
  translation, and always available from the ⋮ menu.
- **Small:** ~7.7 MB APK, including the embedded Noto fonts and Lottie.

Tech: Kotlin, Jetpack Compose (Material 3, downloadable Google Fonts), the
providers' HTTPS streaming APIs (HttpURLConnection + Gson, no provider SDK),
Room, a foreground service, Coroutines/Flow, `pdfbox-android` for reading,
Android's `PdfDocument` for writing PDFs, and `java.util.zip` for EPUBs.

> **Privacy:** the text of your books is sent to the provider you select to
> be translated. On Gemini's free tier, Google's terms say content may be
> used to improve its products. The app has no server of its own.

## Setup: pick a provider and add its API key

The banner at the top of the main screen shows the AI engine in use
("🤖 Using: Groq"), or, with no key saved, a highlighted **⚠️ API Key
Required - Tap to Configure**. Tapping it (or ⋮ → **AI provider & API key**,
or **Select PDF / EPUB** without a key) opens the settings sheet:

1. **AI provider** dropdown: Google Gemini, OpenAI, Anthropic Claude or Groq.
2. **Get API Key** opens that provider's official key dashboard in an in-app
   browser (a full-screen WebView; the user never leaves the app):

   | Provider | Key page | Cost |
   |---|---|---|
   | Google Gemini | https://aistudio.google.com/app/apikey | Free tier (rate-limited) |
   | OpenAI | https://platform.openai.com/api-keys | Paid per use |
   | Anthropic Claude | https://console.anthropic.com/settings/keys | Paid per use |
   | Groq | https://console.groq.com/keys | Free tier (small limits) |

   No reading needed:

   - **Hint:** a small looping animation above the page shows a hand tapping
     the provider's own button ("Create API key", "Create new secret key",
     "Create Key", "Create API Key"), then **Copy**.
   - **Video guide:** the floating **▶** plays a silent 15-second guide (sign
     in → create key → Copy → saved) in the top half of the screen while the
     website stays usable in the bottom half: watch and do.
   - **Auto-capture:** the moment a key is copied, it is recognised by its
     shape ([`ApiKeyDetector`](app/src/main/java/com/example/hinglishpdf/data/ai/ApiKeyDetector.kt):
     `AIza…`/`AQ.…` Gemini, `sk-…` OpenAI, `sk-ant-…` Anthropic, `gsk_…`
     Groq). The browser and the sheet close, the key is saved (for the
     provider it belongs to, even if another one was selected), the phone
     gives two strong buzzes, and a large **✅ API Key Saved Successfully!**
     animation plays. The key is then wiped from the clipboard.

   Copies are seen two ways: a tiny script that runs only on the providers'
   key pages (an origin-restricted `WebMessageListener` from androidx.webkit,
   not a general JavaScript bridge) catches the page's Copy button, and the
   system clipboard listener catches everything else. A copied key of an
   unknown shape gets a one-tap **Use this key** bar instead; the key field's
   paste button still works by hand. A key copied outside the app (e.g. after
   the browser fallback below) is saved the moment the settings sheet is
   back in front.
3. **Advanced Settings** (folded away by default): **Model (optional)**;
   leave empty to use the provider's defaults in order, or type a model name
   to try first.

Keys are kept per provider, only on the phone (app-private storage, excluded
from backups), never in code or git.

**Signing in inside the app:** the in-app browser sends the same user agent
as Chrome on the phone (without the WebView's "; wv" mark, which Google and
some other sign-in pages refuse or show blank), supports sign-in popups
("Continue with Google" opens over the page, closes itself when done), keeps
cookies, DOM storage and the database API on, and resizes above the keyboard
(the hint and the video step aside while typing). If a sign-in is still
refused, the browser's ⋮ menu has **Sign-in blocked? Open in browser**; the
key copied there is saved when you come back.

> Google's OAuth policy asks apps to sign users in through the system browser
> (Custom Tabs), not an embedded WebView. Hiding the WebView mark works today
> but Google may detect it or tighten the rule; the open-in-browser fallback
> is the supported path if that happens.

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

`./gradlew assembleRelease` gives an R8-shrunk, unsigned APK (~7.7 MB); sign
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

### First launch

1. **"Select your Target Language"**: a popup with the 27 languages (native
   names shown). Tapping one sets the **To** language on the main screen;
   **Not now** keeps Hindi.
2. **Spotlight tour** ([`FirstLaunch.kt`](app/src/main/java/com/example/hinglishpdf/ui/FirstLaunch.kt)):
   the screen dims except one element at a time, with a pulsing ring and one
   line of help: the **language** card → the **AI key** banner → the
   **Select PDF / EPUB** button. A tap anywhere moves on; **Skip tour** ends
   it. The list scrolls a target onto the screen when needed.

Both show by themselves only on the very first launch (flags written in
SharedPreferences the moment they appear; people updating see them once).
The tour can be replayed from ⋮ → **Show the tutorial**. The main screen
always starts with the **BYOK Model - 100% Free & Private Translation** badge.

The key guides (`res/raw/key_guide_<provider>.json`, 15 s) and the success
check (`key_saved.json`) are generated the same way by
[`tools/key_guide_lottie.py`](tools/key_guide_lottie.py). They are schematic
drawings with each provider's real button wording, not screenshots of the
websites (those change often and are not ours to ship); the hand and copy
icons are Material Design icons (Apache 2.0).

The animations are written as Lottie JSON from code with
[`tools/lottie_kit.py`](tools/lottie_kit.py) (shapes, gradients, trim paths,
letters as vector outlines from the Noto fonts, SIL OFL); a unit test renders
frames of each to check they parse and draw.

### In-app reader

A finished book shows **Read Now** next to **Save to Downloads**. It writes a
private reading copy in the selected output format (app cache, replaced each
time) and opens [`BookReaderScreen`](app/src/main/java/com/example/hinglishpdf/ui/reader/BookReaderScreen.kt):

- **PDF:** pages rendered with Android's `PdfRenderer`, scrolled vertically.
- **EPUB:** chapters in reading order in a local WebView with Previous / Next.
  Files are served straight from the EPUB (`https://book.local/...`), with
  scripts, file access and network access off; links never leave the app.
- **Controls:** a dark mode toggle (PDF pages are inverted, EPUB text gets a
  dark stylesheet) and an **Aa** panel with a text size (EPUB) or zoom (PDF)
  slider. Both are remembered.

The "finished" notification also opens the app instead of an external viewer.

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
| Every page (smart throttling) | A pause after each translated page, and the same minimum spacing between requests, sized for the free tiers: **OpenAI 20 s** (3 requests a minute), **Anthropic 12 s** (5), **Gemini 4 s** (15), **Groq 2 s** (30) |
| Rate limit (429) | Another model of the provider is tried first; if all are limited, the book **pauses 60 s** (longer if the provider asks), showing **"Pausing for 42s to refresh limit..."** counting down, then retries by itself, **with no retry limit** |
| "limit: 0" (no quota for this model and key) | The model is skipped for the session, like a retired one |
| Network drop, timeout, 5xx, overloaded, unreadable answer | Retried with backoff (15 s → 5 min), up to 8 times |
| Fails for good: no billing credit (402 / 429 "insufficient quota"), refused (401 / 403), region, no usable model, daily quota used up, or still failing after 8 retries | **Smart fallback**: if you have a key for another provider, the book switches to it and carries on with no page lost (Gemini first, then Groq, OpenAI, Anthropic; the app's selection follows, so the banner shows the engine at work). With no other key it stops with a message saying what to fix; **Resume** continues at the same page |

Models need no setup: with the **Model** field empty, each provider's best
default is used (and the next one if it is retired or limited). See
[Which model](#which-model-automatic-fallback).

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
    ├── AndroidManifest.xml         INTERNET, foreground service (specialUse), wake lock, notifications, vibrate
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
        └── ui/                           Compose screen + ViewModel, language card, status banner +
                                          provider settings sheet, in-app key browser, Terms dialog;
                                          FirstLaunch (BYOK badge, language popup, spotlight tour);
                                          reader/ = in-app reader, fonts + Aa sheet
tools/lottie_kit.py                     Helpers for writing Lottie JSON from code
tools/key_guide_lottie.py               Generates res/raw/key_guide_*.json and key_saved.json
```

## Limitations

- **Not yet run on a phone.** The build, lint, the unit tests (providers'
  requests and errors against a local server, fallback, prompt, parser,
  chunking, pagination, EPUB output validated with EPUBCheck, Room resume and
  the version 1 → 2 migration, the language popup, the spotlight tour and the
  dashboard rendered with Robolectric and the real ViewModel, the EPUB reader,
  the Lottie animations rendered frame by frame, pacing, the 60 s countdown
  and the provider failover with a fake AI) and
  live requests to all four providers' real endpoints (each correctly
  rejected a dummy key) pass. A real translation with a valid key, the in-app
  browser (including key capture on the real provider pages, which the tests
  simulate with the clipboard), PDF export and the PDF reader (Robolectric
  cannot run Android's PDF classes) still need to be tried on a device.
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
