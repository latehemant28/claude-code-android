# BDO Visit Tracker

A mobile-first app for recording business development visits, tracking follow-ups, and generating
the **Annexure I: Visit Details for Liability Business Mobilization** Excel report.

It runs on **Android and iPhone** from one codebase, in three forms:

| Form | Android | iPhone / iPad | Cost / requirements |
|---|---|---|---|
| **Native app** (Capacitor) | APK built automatically by GitHub Actions; install directly | Xcode project in `ios/`; build on a Mac | Android: free. iPhone: a Mac, and an Apple ID (free, re-sign every 7 days) or Apple Developer Program (US$99/yr, TestFlight/App Store) |
| **Home-screen web app** (PWA) | Chrome → *Install app* | Safari → Share → *Add to Home Screen* | Free; needs the app hosted on HTTPS (GitHub Pages) |
| **Browser** | Any browser | Safari | Free, but use one of the above for daily work |

In every form, your records are stored **on your phone only**, in the app's built-in database
(IndexedDB), behind an app passcode. No server, cloud database or third-party service ever receives
visit data, and no AI service is involved. In the native apps, the data is also kept out of Google and
iCloud automatic backups.

---

## 1. Features

| Area | What works |
|---|---|
| **Visit entry** | One tap from the dashboard. Fields: department, official name and designation, date, time, opportunities (several), remarks, zone, and branch. Under **More details**: status, purpose, location, contact, business generated, accounts sourced and deposits mobilised. Date and time fill in automatically (Asia/Kolkata) and can be edited. |
| **Fast typing** | Searchable department and official lists learned from past visits, with "Add new" for unknown entries. Picking a department fills in its zone and branch; picking an official fills in the designation. Opportunity chips: tap common ones or type your own. Quick due-date buttons (+1, +3, +7, +15 days). |
| **Drafts** | A new visit is autosaved while you type. If the app closes, the entry is restored next time. |
| **Validation** | Required fields are checked on screen and again before anything is written to the database. Each error message says how to fix it. |
| **Duplicates** | Saving a second visit to the same department on the same date shows a warning with the existing visit. You can still save it, and nothing is deleted. Repeat visits on different dates save normally. |
| **History** | Each visit keeps the department name and the official's designation as they were that day. Later changes do not rewrite old visits. |
| **Timestamps** | Visit date and time are stored separately from *record created* and *last updated*, and the visit page shows both. |
| **Visits list** | Search across department, official, opportunity and remarks. Filter by today, week, month, a chosen month, financial year (April–March), custom range, zone, department, opportunity, status or follow-up state. Sort newest or oldest first. Select visits to export. |
| **Edit and delete** | Full editing. Deleting a visit asks for confirmation and also removes its follow-ups and attachments. |
| **Follow-ups** | Several per visit. Each has an action, a responsible person, a due date, a status, completion notes and a result. You can complete, reschedule (history and reason are kept), reopen or delete. The tracker has *Overdue & today*, *Upcoming* and *Completed* tabs. The dashboard and navigation badge show what is due. |
| **Attachments** | Photos, PDFs and Office files (up to 10 MB each) on the visit page, stored on the device. |
| **Excel report** | A genuine `.xlsx` file built with ExcelJS. Export by today, week, month, chosen month, custom range, financial year, all visits, or selected visits. Steps: confirm the count, preview, fix incomplete visits, generate, then save or share. Where the file goes: browser → Downloads; native Android → Documents › BDO Visit Tracker; native iPhone → Files › On My iPhone › BDO Visit Tracker. **Share / Send…** opens the phone's share sheet (WhatsApp, Mail, Drive, Save to Files). |
| **Dashboard** | Shows visits today and this month. For a chosen period it shows visits, departments, opportunities, accounts sourced, deposits mobilised and conversions, plus follow-ups due today and overdue, and recent visits. Opportunities (leads) are always counted separately from business. |
| **Reports** | Visits by month, opportunity, zone, department and status for any period, shown as plain tables. |
| **Security** | A passcode is required on first use. Only a salted PBKDF2-SHA-256 hash of it is stored. The app locks on reopen and after a configurable idle or background time. Repeated wrong attempts trigger a lockout. Search engines are told not to index the site. |
| **Backup** | A full JSON backup (including attachments) restores by merging or replacing in a single transaction. An all-fields Excel export is available for analysis. Settings shows the date of the last backup. |
| **Offline** | Everything, including Excel export, works with no network once the app has been opened online once. |

### Not implemented / limitations (please read)

- **No push notifications.** Follow-up reminders appear in the app (dashboard banner, red badge,
  tracker) when you open it. Nothing reaches the phone's notification bar.
- **iPhone testing.** The iOS app is compiled by CI on a Mac, and the web app was checked at
  iPhone size with a Safari user agent. Neither has been run on a real iPhone or in Safari's WebKit
  engine. Test on your iPhone before relying on it.
- **Android testing.** The APK was built and inspected, but this environment cannot run an
  emulator, so it has not been launched on a device. The same web code passed all browser tests.
- **No cloud sync between devices.** This is deliberate: it keeps official data off third-party servers.
  Use backup and restore to move data to a new phone. If your bank later approves a server, the data
  layer (`src/lib/repo.ts`) is the single place to connect one (for example Supabase/PostgreSQL).
- **Single user per phone/browser.** The passcode keeps others out of the app on your phone. It does
  not encrypt the database file itself. The phone's screen lock and Android device encryption are the
  main protection, so keep both enabled.
- **A forgotten passcode cannot be recovered.** The only option is to erase the data on that device and
  restore a backup.
- **No reference images were provided** with the request. The report follows the column list and
  description given: 7 columns, merged title, signature blocks. If your official sheet differs (fonts,
  widths, extra header lines), send a copy and the layout in `src/lib/excel.ts` can be matched exactly.

---

## 2. Data model (IndexedDB via Dexie, `src/lib/db.ts`)

| Table | Key fields | Purpose |
|---|---|---|
| `visits` | `id` (auto), `departmentName`, `officialName`, `officialDesignation`, `contactNumber`, `visitDate` (YYYY-MM-DD), `visitTime` (HH:mm), `opportunities[]`, `remarks`, `zone`, `branch`, `location`, `purpose`, `followUpRequired`, `status`, `businessGenerated`, `depositsMobilized`, `accountsSourced`, `departmentId`, `officialId`, `createdAt`, `updatedAt` (ISO UTC) | One row per visit. Department and official details are snapshots, kept as they were on the day of the visit. |
| `followUps` | `id`, `visitId`, `action`, `responsible`, `dueDate`, `status` (Pending/Completed/Cancelled), `completionNotes`, `result`, `completedAt`, `rescheduleHistory[]`, timestamps | Many per visit |
| `departments` | `id`, `name`, `key` (unique, lower-case), `zone`, `branch`, `useCount`, `lastUsedAt` | Suggestions |
| `officials` | `id`, `name`, `designation`, `departmentName`, `contactNumber`, `key` (unique), `useCount` | Suggestions |
| `opportunityTypes` | `id`, `name`, `key` (unique), `useCount` | Learned opportunity list |
| `attachments` | `id`, `visitId`, `name`, `type`, `size`, `data` (Blob) | Supporting documents |
| `kv` | `settings`, `lock` (passcode hash), `draft:new`, `lastBackupAt` | Preferences and app state |

Visit dates and times are stored as plain local values in the **Asia/Kolkata** zone. A visit therefore
never moves to another day if the phone's time zone changes. The database is the source of truth, and
Excel files are generated from it on demand. Changes to this schema must add a new `this.version(n)`
block in `db.ts`, never edit version 1.

---

## 3. Generating the Excel report

1. Tap **Excel** in the bottom bar (or **Generate Excel** on the dashboard).
2. Choose the visits: *Today*, *This week*, *This month*, *A specific month*, *Custom date range*,
   *Financial year*, or *All visits*. To hand-pick visits, open **Visits**, tap **Select**, tick
   the visits you want, and tap **Export N selected visits**.
3. Optionally tick **Include a "Time of Visit" column**. Without it you get the standard 7-column
   Annexure I. The recorded time stays saved either way.
4. Open **Signature names** to enter your name and the DGM & Zonal Manager's name and designation.
   These are saved for future reports. Leave them blank to sign by hand.
5. Check the summary (period and number of visits) and the preview. If a visit is missing a report
   field, it is listed with an **Edit** link. Fix it, or tick *Export anyway*.
6. Tap **Generate & download Excel**. The file (for example `Annexure-I_Visit-Details_2026-10.xlsx`)
   goes to your Downloads folder. Tap **Share…** to send it by WhatsApp, Gmail, Drive and so on.

The report layout:
- A merged title row and a period line.
- Bold, shaded column headings with borders.
- Wrapped text, with row heights sized to fit long remarks.
- Real Excel dates in `dd-mm-yyyy` format and sequential S.No.
- A4 landscape, fit to one page wide, with the headings repeated on every printed page and "Page X of Y" in the footer.
- Signature blocks for the Business Development Manager and the DGM & Zonal Manager.

Internal fields (contact number, deposits, record IDs and so on) are never included.

---

## 4. Backup and recovery

Your records exist only on your phone, so **take a backup every week**:

- **Settings → Download full backup (.json)**: everything, including follow-ups, attachments and
  settings. Store it where your bank's data-handling policy allows, such as an official drive or
  encrypted storage. Do not put it in public or personal cloud folders unless that is permitted.
- **Settings → Download all data as Excel**: every field in readable form, for analysis or audit.
- **To restore**, go to Settings → Restore from backup file… and choose one of:
  - **Merge**: adds visits that are not already on the device. Running it twice is safe.
  - **Replace everything**: erases the device data and restores the backup exactly.

  Either way, the restore is all-or-nothing: if it fails, nothing changes.
- **New phone:** install the app, set a passcode, then restore the latest backup.
- **The native app and the web app are separate.** Each has its own records, so use one. Moving
  between them, or reinstalling, works through backup → install → restore. **Uninstalling the native
  app deletes its data**, so take a backup first.
- The app asks the browser for *persistent storage* so Android does not clear the data when space
  runs low. Settings shows whether this was granted. Clearing Chrome's "site data" for the app
  **will** delete the records.

---

## 5. Running and deploying

Requirements: Node.js 20 or later.

```bash
cd bdo-visit-tracker
npm ci            # install dependencies
npm run dev       # development server at http://localhost:5173
npm test          # unit tests (data layer, validation, backup, Excel layout)
npm run build     # production build into dist/
npm run e2e       # end-to-end test in Chromium at phone size (needs a build first)
```

### Option A: GitHub Pages (recommended, free HTTPS)

1. On GitHub, go to **Settings → Pages → Build and deployment** and set *Source* to **GitHub Actions**.
2. Go to **Actions → Deploy BDO Visit Tracker → Run workflow**. The workflow runs the tests, builds the app and publishes it.
3. The app will be at `https://<your-username>.github.io/<repo-name>/`.

The published site contains only the app's code. Each phone keeps its own records locally, so the URL
does not expose any visit data, and someone opening it on another device sees an empty app asking
them to set a passcode. If the repository is public, the *code* is public, which is harmless.

Any other static HTTPS host also works, such as Netlify, Cloudflare Pages or an internal web
server: upload the contents of `dist/`. HTTPS (or localhost) is required for the passcode crypto
and offline mode.

### Option B: run on the phone itself with Termux

```bash
pkg install nodejs git
git clone <this repo> && cd <repo>/bdo-visit-tracker
npm ci && npm run build
npx vite preview --port 4173
```

Then open `http://localhost:4173` in Chrome on the same phone. This must be `localhost`, not a LAN IP.

### Option C: native Android app (APK)

Every push that changes `bdo-visit-tracker/` runs the **BDO Visit Tracker native apps** workflow.

1. On GitHub, open **Actions → BDO Visit Tracker native apps**, then the latest green run, and download
   the **bdo-visit-tracker-android** artifact. It is a zip containing the APK.
2. Copy the APK to the phone and open it. Allow "Install unknown apps" for your file manager or
   browser when Android asks.
3. Open **BDO Visit Tracker** from the app drawer and set your passcode.

**Set up a release signing key once, before you store real data.** Without one, CI builds a *debug*
APK. A debug APK works, but it is easier to inspect over USB debugging, and each new debug build may
refuse to install over the previous one. To sign every build with your own key:

```bash
keytool -genkeypair -keystore bdo-release.jks -alias bdo -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 bdo-release.jks      # copy the output
```

Then, in the repository on GitHub, go to **Settings → Secrets and variables → Actions** and add:
`BDO_ANDROID_KEYSTORE_BASE64` (the base64 text), `BDO_ANDROID_KEYSTORE_PASSWORD`,
`BDO_ANDROID_KEY_ALIAS` (`bdo`) and `BDO_ANDROID_KEY_PASSWORD`. Later builds are signed release APKs
that install over each other and keep your data. **Keep `bdo-release.jks` and its password safe and
off the phone:** if you lose them, future updates cannot install over the app.

To build the APK locally, you need Node 20+, JDK 21 and the Android SDK (`ANDROID_HOME`). Run
`npm run android:apk`; the output is `android/app/build/outputs/apk/debug/app-debug.apk`.

### Option D: native iPhone app

Apple only allows apps on an iPhone if they are signed on a Mac. The CI workflow proves the project
compiles, but it cannot put the app on your phone.

1. On a Mac with **Xcode 16 or later**: `git clone` the repo, `cd bdo-visit-tracker`, `npm ci`,
   then `npm run ios:open`. Xcode opens the project.
2. In Xcode, select the **App** target, then **Signing & Capabilities**. Choose your Team (sign in with
   your Apple ID) and, if Xcode asks, change the bundle identifier to something unique.
3. Connect the iPhone, select it as the run destination and press **Run**. On the iPhone, go to
   **Settings → General → VPN & Device Management** and trust your developer certificate.

With a free Apple ID, the app stops opening after **7 days** until you press Run again. With the
Apple Developer Program you can use **TestFlight** (90-day builds, no cable), or publish privately
through your organisation. Your bank's IT team may already have an enterprise/MDM channel for this.

**No Mac?** Use the home-screen web app (Option A plus the steps below). On iPhone it has the same
features. Files are saved through Safari's download prompt and the **Share / Send…** button.

### Installing the web app on your phone

- **Android (Chrome):** open the HTTPS address and tap **Install** on the dashboard banner, or
  **⋮ → Install app**.
- **iPhone (Safari, iOS 15 or later):** open the HTTPS address in **Safari** (not Chrome or Gmail's
  browser), then tap **Share (□↑) → Add to Home Screen → Add**. Always open the app from the new icon.
  Safari deletes the data of *websites* not used for 7 days, but Home Screen apps are exempt.
- To save a report on iPhone: tap **Generate**. If Safari asks, tap Download; the file goes to
  **Files › Downloads**. Or tap **Share / Send…** and choose Mail, WhatsApp or *Save to Files*.

> Records belong to the app/browser where they were created. A visit saved in the Home Screen app is
> not visible in Safari or Chrome tabs, or in the native app, and vice versa. Always use the same one.

---

## 6. Testing that was run

- `npm test`: 15 unit tests. They cover creating a visit with all fields, persistence across a database
  reconnect, editing (createdAt kept, updatedAt changed), field-specific validation, repeat visits and
  same-day duplicate detection, department-name consistency, historical snapshots, search and every
  filter, date-and-time sorting, delete cascade, follow-up create/complete/reschedule with
  overdue/due-today counts, metric separation, backup round-trip and merge without duplicates, and the
  Excel layout (40-row report: title, merges, headings, serials, dates, wrap, borders, print setup,
  signatures, no internal fields, optional time column).
- `npm run e2e`: 53 checks in real Chromium at a 390×844 phone size, plus an iPhone Safari user agent. They cover:
  - passcode setup and lock;
  - automatic date and time;
  - saving a fully filled visit;
  - the duplicate warning;
  - data persisting through a reload;
  - wrong-passcode rejection;
  - editing;
  - search and filters;
  - follow-up reschedule and complete;
  - the overdue banner;
  - export by date range, with time, and of selected visits, with each downloaded `.xlsx` re-opened
    and every row checked;
  - backup download;
  - another browser profile seeing no data;
  - no horizontal scrolling on any screen;
  - the iPhone install guidance;
  - no page errors.
- Android: the debug and signed-release APKs were built locally. The manifest was checked for
  cloud backup off, storage permission limited to Android 10 and older, and the bundled web app.
  The release APK was confirmed signed and not debuggable. iOS: compiled by the CI workflow
  (see the Actions tab).
- The generated `.xlsx` files were also opened and printed to PDF with LibreOffice to check the print
  layout. They were not opened in Microsoft Excel itself during development. Please open one sample in
  Excel before the first official submission.
