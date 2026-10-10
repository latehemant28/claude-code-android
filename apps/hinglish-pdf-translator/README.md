# BYOK Translator (Android)

> **Bring your own key (BYOK) · 100% Free & Private Translation**: bring your own AI key;
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
- **First launch:** a "Choose your language" sheet, then a spotlight
  tour of the main screen (AI key → language → choose a book).
- **Resilient:** each provider is paced for its free tier; a rate limit
  pauses 60 s ("Pausing for 60s to refresh limit...") and retries by itself;
  if a provider fails for good (no credit, bad key, daily quota), the book
  carries on with another provider you have a key for.
- **Any of four providers:** pick one from a dropdown; each has its own
  translator class (Strategy pattern) for its endpoint and JSON. **Get API
  Key** opens the provider's own key page inside the app; copy a key there,
  close it, and the key is pasted for you.
- **PDF or EPUB output:** an **Output format ( PDF | EPUB )** toggle in
  Settings (EPUB by default) decides what **Save to Downloads** writes,
  whatever the source.
- **Structure kept:** headings, bullet points (with nesting), numbering and
  paragraph breaks are restored after translation.
- **Model fallback:** a prioritized list of models per provider; a retired,
  out-of-quota or rate-limited model is swapped for the next one automatically.
- **Made for everyone, including people who cannot read:**
  - **It talks.** Every big button has a 🔊 that says what it does, and the
    button itself says it too when tapped (spoken help, on by default,
    switchable in Settings). The spoken help and the easy screens are in
    simple Hindi when the book is translated into Hindi or the phone is in
    Hindi / India, in English otherwise. It uses the phone's own
    text-to-speech voice; nothing extra is sent anywhere.
  - **Pictures first:** big coloured tiles with large icons and one or two
    words: 🔑 the key (only until it is set up), 🌐 the language (in its own
    script, e.g. हिन्दी), and one big **green** "किताब चुनें" (Choose a book)
    tile, which the spoken help calls "the green button".
  - **Listen (सुनें):** the translated book is read aloud by the phone, part
    by part, with three big buttons (back, play / pause, next), the part being
    read shown in large text, and slow / normal speed. It carries on where it
    stopped, and comes back after the app is closed. If the phone has no voice
    for the book's language, a button opens the voice download.
  - **Consent in voice and pictures:** before each file, a page → AI cloud
    picture and a short explanation that is read aloud; the answer is a big
    green ✅ "हाँ, भेजें" (Yes, send) or a big red ❌ "नहीं" (No).
  - **The key needs a helper once.** Creating an AI provider's API key means
    signing up on its website, which needs someone who can read. The app
    says so ("किसी जानकार से एक बार लगवा लें"); after that, everything is
    pictures and voice.
- **Simple, three tabs:** **Translate**, **My books** and **Settings**, in a
  deep indigo / purple Material 3 theme, light or dark with the phone.
- **Translate tab:** the **Bring your own key (BYOK)** title with the
  **100% Free & Private Translation** badge; two tiles side by side, the
  **AI key** (provider, ✓ Connected or ⚠ Tap to add a key) and the
  **language** (e.g. Hindi · हिन्दी); the big green **Choose a book
  (PDF/EPUB)** button; and the book being translated, with a progress ring,
  its page and **Pause** / **Read** / **Listen** buttons. 🔊 in the top bar
  says how it works; each tile says what it does when tapped (spoken help).
  The book's own language (From) and the output format are in Settings.
- **Consent before every upload:** choosing a file first shows **"Your
  file's text will be sent to AI"**: which provider gets the text, that only
  text is sent (pictures stay on the phone), that the provider's privacy
  terms apply, and to avoid confidential documents. The file picker opens
  only after ticking **"I agree to send this file's text to …"** and tapping
  **Agree & choose file**.
- **My books:** every book as a card: progress ring and status tag
  (Translating / Paused / Done) on the left, title and pages in the middle
  with **Pause** / **Resume**, **Read** and **Listen**; **Save to
  Downloads**, **Copy text** and **Delete** are in its ⋮ menu. Tapping a
  card shows its translated pages below the list.
- **In-app reader:** opens a finished book inside the app (PDF pages with
  `PdfRenderer`, EPUB chapters in a local WebView), edge to edge, with bars
  that hide while you read, dark mode and gliding text size / zoom.
- **Layout fidelity:** the translation keeps the source's headings,
  paragraphs, line breaks, lists and quotes, block for block; every block is
  checked, and anything missing, cut off or left untranslated is sent again.
- **Reading UI:** Devanagari fonts from Google Fonts (Mukta, Noto Serif
  Devanagari, Kalam) and an **Aa** sheet for font style and text size.
- **Background and crash-proof:** a foreground service with a "Translating
  page 45 of 300..." notification; every page is saved to Room the moment it
  is done, and a restart resumes at the first untranslated page.
- **Terms of Use:** a disclaimer that must be accepted before the first
  translation, and always available in **Settings**.
- **Small:** ~7.7 MB APK, including the embedded Noto fonts and Lottie.

Tech: Kotlin, Jetpack Compose (Material 3, downloadable Google Fonts), the
providers' HTTPS streaming APIs (HttpURLConnection + Gson, no provider SDK),
Room, a foreground service, Coroutines/Flow, `pdfbox-android` for reading,
Android's `PdfDocument` for writing PDFs, and `java.util.zip` for EPUBs.

> **Privacy:** the text of your books is sent to the provider you select to
> be translated. On Gemini's free tier, Google's terms say content may be
> used to improve its products. The app has no server of its own.

## Setup: pick a provider and add its API key

Step **① AI engine** on the dashboard shows the provider in use with a
green "Connected" light, or, with no key saved, **⚠️ API Key Required - Tap
to Configure** with a **Set up** button. Tapping it (or a
provider under **Settings → Choose Your AI Engine**, or **Select PDF / EPUB**
without a key) opens the settings sheet:

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
3. **Advanced** (folded away by default): **Model (optional)**;
   leave empty to use the provider's defaults in order, or type a model name
   to try first.

Keys are kept per provider, only on the phone (app-private storage, excluded
from backups), never in code or git.

**Signing in inside the app:** the in-app browser sends the same user agent
as Chrome on the phone (without the WebView's "; wv" mark, which Google and
some other sign-in pages refuse or show blank), supports sign-in popups
("Continue with Google" opens over the page, closes itself when done), keeps
cookies, DOM storage and the database API on, and resizes above the keyboard
(the hint and the video step aside while typing).

**Open in Chrome / External Browser:** the browser's header has a visible
"open in browser" icon, next to the video ▶ and Reload. It opens the page
you are on in the phone's default browser. Use it when the sign-in needs a
password manager, an email OTP or a refused Google sign-in. Finish there,
copy the key, and come back: the key is saved as soon as the app is in front
again, with no pasting.

**Leaving for an OTP (process death):** Android may kill the app while you
are in your email app. When you come back, everything is where you left it:

- The key sheet and its in-app browser are still open. Their visibility lives
  in the ViewModel's `SavedStateHandle` (`provider_sheet`, `key_browser`).
- The page is still the OTP screen, not the provider's home page.
  `WebView.saveState()` goes into the saved-instance state through a
  `rememberSaveable` saver, and `restoreState()` brings back the page and
  its back stack. Cookies are flushed whenever the app goes to the
  background, so the half-finished sign-in survives too.
- If the WebView can't restore its history, the last URL is reloaded instead.
  The history is capped at 200 KB so it never overflows the saved state.
- An open book in the reader is reopened, if its copy is still there.

Saving a key or dismissing the sheet clears this state, so the sheet does
not pop up again by itself.

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
| OpenAI | `gpt-4o-mini`, `gpt-4.1-mini`, `gpt-5-mini` |
| Anthropic | `claude-3-haiku-20240307` (retired by Anthropic on 19 Apr 2026), `claude-haiku-5-5`, `claude-haiku-4-5`, `claude-sonnet-5-5` |
| Groq | `llama-3.3-70b-versatile`, `openai/gpt-oss-120b` |

Each list starts with the fast, low-cost model picked for zero-config use.
A retired first model costs one failed request per app session (it answers
"not found" and is skipped from then on), so translation never depends on it.

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
specified text, trailing space included):

```
You are an expert, context-aware translator translating {Source_Language} books into natural, conversational {Target_Language} using the {Target_Script} script.

<rules>
1. Script & Tone: Use the {Target_Script} script. ...
2. Context-Awareness (Crucial): ... adapt the pronouns, honorifics, and formality style ...
3. Vocabulary: ... You can leave highly technical or specific global terms in the English script ...
4. Formatting & Labels: ... keep 'YOUTH:', 'PHILOSOPHER:', 'Chapter 1' exactly as they are in the original text.
5. Output: Output ONLY the translated text. ...
</rules>
```

`TranslationRepository.translatePage(blocks, source, target)` fills in the
book's languages (`TranslationPrompt.system`):

- **{Source_Language}**: the From language ("translating English books");
  Auto-Detect becomes "foreign-language".
- **{Target_Language}**: the To language.
- **{Target_Script}**: the To language's writing system (`Language.script`):
  Devanagari (Hindi, Marathi, Nepali), Gurmukhi (Punjabi), Urdu
  (Perso-Arabic Nastaliq), Arabic, Bengali, Gujarati, Tamil, Telugu, Kannada,
  Malayalam, Thai, Cyrillic (Russian), Hangul (Korean), Japanese (kanji and
  kana), Simplified Chinese, Vietnamese Latin, and Latin for the other
  European languages and Indonesian.

The app's output contract follows the prompt (translate 100% of the text;
answer with the same blocks, in the same order), so every translated
paragraph maps back to its place. Every provider receives the result as its
**system prompt**; each request carries only the chunk of
text. Temperature is 0.2 (unless a model refuses one), and Gemini's safety
filters are set to `BLOCK_NONE` so ordinary literature isn't refused mid-book.

Exports follow the target language: the file is named "Book (Spanish).epub",
the EPUB is labelled with the language code (`dir="rtl"` for Arabic and
Urdu), Noto Sans Devanagari is embedded for Devanagari languages only, the
PDF mirrors lists and indents for right-to-left text, and the few words the
app writes itself (Contents, Pages 1–20) are in Hindi for Hindi books and in
English otherwise.

### First launch

1. **"Choose your language"**: a bottom sheet with the 27 languages, each
   in its own script first (हिन्दी, Español...) with a radio button; tapping
   one says its name, **Continue** sets the **To** language on the main
   screen, and swiping the sheet away keeps Hindi. The language tile opens
   the same sheet later.
2. **Spotlight tour** ([`FirstLaunch.kt`](app/src/main/java/com/example/hinglishpdf/ui/FirstLaunch.kt)):
   the screen dims except one element at a time, with a pulsing ring and one
   tooltip card, in the order of the steps: the **AI key** → the
   **language** → the green **Choose a book** button. **Next** (or a tap
   anywhere) moves on, **Got it** ends it on the last step, **Skip** ends
   it at once. The list scrolls a target onto the screen when needed.

Both show by themselves only on the very first launch (flags written in
SharedPreferences the moment they appear; people updating see them once).
The tour can be replayed from the **?** in the dashboard's top bar or from
**Settings → Show the tutorial**. The dashboard always starts with the
**Bring your own key (BYOK)** title and its **100% Free & Private
Translation** badge.

All illustrations on screen (the badge, the AI engine chip in **Choose Your
AI Engine**, the spotlight's dimmed layer with its cut-out) are drawn with
Compose shapes and gradients, and the Lottie animations render in software
mode, so nothing depends on a GPU path that could show a black box.

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

A finished book shows **Read** in the Library (and **Read Now** on the
dashboard's latest-book card). It writes a
private reading copy in the selected output format (app cache, replaced each
time) and opens [`BookReaderScreen`](app/src/main/java/com/example/hinglishpdf/ui/reader/BookReaderScreen.kt):

- **PDF:** pages rendered with Android's `PdfRenderer`, scrolled vertically.
- **EPUB:** chapters in reading order in a local WebView with Previous / Next.
  Files are served straight from the EPUB (`https://book.local/...`), with
  scripts, file access and network access off; links never leave the app.
- **Clutter-free:** the book fills the screen. The top bar (back, title,
  dark mode, **Aa**) and the bottom bar (chapter navigation) slide away as
  soon as you scroll; a tap on the page brings them back, and they hide again
  after a few seconds.
- **Controls:** a dark mode toggle (PDF pages are inverted, EPUB text gets a
  dark stylesheet) and an **Aa** panel with **A−**, a slider and **A+** for
  text size (EPUB) or zoom (PDF); the size glides to each new value. Both
  are remembered.

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
- **Every block is checked** ([`TranslationCheck`](app/src/main/java/com/example/hinglishpdf/data/translate/TranslationCheck.kt)):
  empty, far too short for the original (cut off part-way), or still in the
  source language (the whole block, or 8 or more words copied unchanged, as
  when an answer falls back to English under a heading). Those blocks are
  sent again together, with a strict reminder to translate all of it; what
  is still missing or cut short is then translated sentence by sentence.
  Short blocks (names, "Index", "Chapter 3") are not second-guessed.
- **An answer cut off by the model's length limit** (Gemini `MAX_TOKENS`,
  OpenAI / Groq `length`, Claude `max_tokens`) is never kept: the chunk is
  redone in halves, and a single long paragraph in parts.
- **The AI refuses some text**: it is split, and text that is still refused
  stays in the original language instead of stopping the book.

Every request carries an **output contract** after the translation prompt:
translate 100% of the text, answer with exactly the same blocks in the same
order, and keep short blocks (headings, chapter titles) as their own block.

### Reading the source faithfully

- **No false blank pages (PDF):** each page's text is assigned to that page
  by number. PDFBox skips a page with no content stream, which used to shift
  every later page's text one page early and leave a page with a heading or
  chapter title looking blank.
- **Running headers and footers** repeated on many pages are dropped, but
  never a line set larger than the body text (a chapter title at the top of
  its opening page often repeats the running header) and never the only text
  of a page.
- **Paragraphs stay separate (PDF):** besides spacing and first-line indents,
  a finished sentence on a line that stops well short of the right edge ends
  its paragraph, so books with block paragraphs, dialogue and one-line
  paragraphs are not merged.
- **Line breaks (EPUB):** each line before a `<br/>` (verse, addresses) is its
  own block, translated in place with the `<br/>` kept, and shown on its own
  line in the preview, the PDF export and plain text.

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

The **Output format ( PDF | EPUB )** toggle in Settings (EPUB
by default) decides what **Save to Downloads** (the book's ⋮ menu in the Library), and the automatic save when a
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
always available in **Settings → Terms of Use & Disclaimer**, and a link
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
        │   ├── settings/                 Provider + keys, languages, output format, Terms, tutorial flag,
        │   │                             spoken help, reading speed, listening position
        │   ├── voice/Voice.kt            The phone's text-to-speech voice (spoken help, Listen)
        │   ├── db/                       Room: books, pages (PK = bookId + pageNumber), DAOs
        │   ├── pdf/                      PDFBox extraction + layout analysis per page
        │   ├── epub/                     EpubBook (read, translated copy), EpubWriter (new EPUB 3)
        │   ├── document/                 Import, blocks, PDF export (PdfExporter, PdfPaginator, BundledFonts)
        │   ├── export/BookExporter.kt    Writes the result to Downloads via MediaStore
        │   └── translate/                BlockChunker (page → chunks of whole blocks),
        │                                 TranslationCheck (missing / cut-off / untranslated blocks)
        └── ui/                           TranslatorScreen (tabs, top bar, sheets) + ViewModel;
                                          DashboardTab (3 steps), LibraryTab, SettingsTab, Components
                                          (cards, gradient button, pill toggle, progress);
                                          UploadConsentDialog; Words (Hindi / English phrases, spoken
                                          help); listen/ = Listen player; language card, provider settings
                                          sheet, in-app key browser,
                                          Terms dialog; FirstLaunch (BYOK badge, language popup,
                                          spotlight tour); theme/ = indigo / slate palette and
                                          gradients; reader/ = in-app reader, fonts + Aa sheet
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
