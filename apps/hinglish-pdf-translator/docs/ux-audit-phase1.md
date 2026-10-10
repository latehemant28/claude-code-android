# UX Phase 1: clarity audit (Norman)

For every screen: the actions it offers, how a user can tell they exist
(signifiers), and the feedback each gives, before and after Phase 1.
"≤100 ms" means visible feedback in the same frame as the tap.

## Main screen

| Action | Signifier | Feedback before | Feedback now |
|---|---|---|---|
| Pick an AI provider | Dropdown with "AI provider" label, arrow | Menu; "Key saved / No key yet" per entry | Same, plus cost per provider; step 1 gets a ✓ once a key is saved |
| Get API Key | Tonal button with key icon | In-app browser opens | Same |
| Paste / type key, Save key | Field with paste icon; Save button | Save greyed out under 20 chars, no reason given | Input is cleaned (quotes, "Bearer ", `API_KEY=`, spaces); a key that still can't be right says why under the field; Save shows a snackbar ≤100 ms |
| Model name (✓ to apply) | Field with ✓ when changed | Snackbar | Same; quotes and spaces removed |
| Custom AI address | Address field | Refused without https:// | `api.example.com/v1` and `http://` become https://; "Will use …" shown |
| Select PDF / EPUB | Primary button, upload icon, under step 2 | Greyed out without a key, no reason; picker only offered PDF/EPUB types | Always enabled: without an AI it says "Connect an AI in step 1 first" and scrolls there; spinner "Opening the book…" ≤100 ms; any file can be picked, recognised by content |
| Output format | Segmented PDF / EPUB under step 3 | Selection only | Selection plus one line on what each gives |
| Open a book | (none: tapping the card only selected it) | Buttons appeared only after tapping | Card shows its stage map, headline, progress, time left and next action; "Details" button and the card open the project screen |
| Pause / Resume / Open file | Only after selecting the card | No immediate feedback for Pause; Resume silent | Always visible on the card; "Pausing…" spinner ≤100 ms; "Continuing from page N…" ≤100 ms |
| Text style (Aa), menu (⋮) | Top bar | Sheet / menu | Same ("Settings" label corrected to "More options") |

## Project screen (new)

| Action | Signifier | Feedback |
|---|---|---|
| See where the book is | Stage map: Uploaded > Parsed > Translated > Checked > Rebuilt > Delivered, icons ✓ / spinner / ⚠ / ○ / ⊖, current stage tinted | Updates live; "Checked" says it comes in a later update (no false promise) |
| Fix a problem | Guidance card (what happened, what to do), buttons | Each button leads to the fix: Check the API key / Use another AI (back to step 1 with a hint), Open the provider's site, Type a model name, Read the Terms, Choose another file, Save again, Try again; "Details" shows the original message; "Your N translated pages are kept" |
| Pause / Resume | Buttons | ≤100 ms ("Pausing…", "Continuing from…") |
| Save to Downloads | Tonal button | "Saving…" spinner ≤100 ms, then a snackbar with the file name (or guidance if it fails) |
| Copy text | Outlined button | Snackbar |
| Open file | Outlined button | Opens the reader app, or explains that none is installed and where the file is |
| Delete | Red outlined button | Book leaves the list ≤100 ms with "Undo"; deleted for good only when the message goes away |
| Back | Arrow, system back | Returns to the list |

## Long operations: progress and time left

| Operation | Indicator | Estimate |
|---|---|---|
| Opening a picked file | Spinner in the button | (seconds) |
| Analysing the book (parser) | Bar, "Analysing page x of y" | From the pace so far, padded by a third and rounded up |
| Reading pages | Bar, "Reading page x of y" | Same |
| Translating | Bar, "Translating page x of y" (card, project, notification) | Same; "Estimating time…" until two pages are done; waiting out a rate limit is shown as waiting, not as an error |
| Quality checks | Not built yet: shown as unavailable | — |
| Building / saving the file | Indeterminate bar, "Saving…" | "Usually under a minute" |

## Errors

Every error is shown as guidance (`ui/status/ErrorGuide.kt`): a title that
does not blame the user, what to do next, and buttons to do it. Progress is
never discarded: Resume continues from the first untranslated page. Failure
notifications use the same words.

## Dialogs and notifications

| Screen | Actions | Feedback |
|---|---|---|
| Terms of Use | Tick box, I Agree, Not now | I Agree enabled only after ticking; the action that needed the Terms continues |
| In-app key browser | Close, Reload, Open in browser | Copied key pasted into the field with a snackbar |
| "Can't read this file" (new) | Choose another file, Close | Replaces a raw snackbar error |
| Progress notification | Pause | Now with time left |
| Finished notification | Tap opens the file | — |
| Needs-you notification | Tap opens the app | Guidance title and explanation instead of the raw error |

## Not covered in Phase 1

- Dates and language names: no screen asks for them yet; the intake form
  (Phase 3) will accept them flexibly.
- Quality checks: the stage exists on the map but the engine is not built.
