# AI Job Assistant — mobile

Native iOS/Android app built with Expo (SDK 57), expo-router and TypeScript. It talks
to the existing Spring Boot API only; nothing in `frontend/`, `src/`, `pom.xml` or
`infra/` is touched by this project.

## Run on a phone with Expo Go

1. Start the backend on the computer as usual (port 8080).
2. Install **Expo Go** (supporting SDK 57) on the phone; join the same Wi-Fi.
3. From this folder:

   ```bash
   npm install
   npx expo start --go --lan
   ```

4. Scan the QR code. The app calls `http://<computer LAN IP>:8080/api/v1`
   automatically. If the phone can't reach it, allow port 8080 through the Windows
   firewall, or set `EXPO_PUBLIC_API_BASE_URL` (see `.env.example`).

## Checks

```bash
npm run typecheck
npm test
npx expo-doctor
```

`npx expo export` produces exported JS bundles and assets for verification. It does
not produce an APK or IPA.

## Structure

```
app/                         expo-router routes (thin: wire screens to navigation)
  (auth)/login, register
  (app)/(tabs)/dashboard, jobs, resumes, tailor, profile   bottom tabs (控制台 职位 简历 改写 我的), signed-in only
  (app)/catalog/[catalogJobId]                     catalog posting detail
  (app)/job/[jobId]                                personal job record detail
  (app)/resume/[resumeId], .../quality             resume file detail, quality score
  (app)/draft/new, [draftId], [draftId]/edit       resume builder
  (app)/soon                                       unused placeholder; nothing links to it
src/api/                     fetch client (Bearer JWT, 401 handling), DTO copies, endpoints
src/auth/                    AuthContext, SecureStore session, cache guard, password rules
src/dashboard/, src/jobs/, src/resumes/, src/profile/   rules and React Query hooks
src/builder/                 resume builder model, session and leave guards, fields
src/tailor/                  tailoring rules, flows, local records, downloads; enhanced/ = ENHANCED
src/screens/, src/components/
src/i18n/                    zh-CN / en strings (same keys in both)
test-utils/                  test-only fixtures and render helpers (never imported by app code)
```

## Visitors and login

- First launch without a session opens `/welcome` (no request at all): features, clearly
  labelled example content, and entries to log in or register. `/browse` is the public job
  search and `/catalog/[catalogJobId]` the public posting; both use only public GET routes
  (`/job-catalog/search`, `/cities`, `/job-catalog/{id}`). `GET /jobs` is never called for
  a visitor.
- Account features send the visitor to `/login?returnTo=<path>`; after logging in the
  `(auth)` layout redirects to that path (`src/auth/returnTo.ts`, same safety rules as the
  website: in-app paths only).
- Signed-in-only routes with nobody signed in: an expired session returns to the same
  screen after logging in again; password change / account deletion show their notice on
  login; a plain logout lands on `/welcome`. Cache isolation on sign-out is unchanged.

## Auth notes

- `POST /auth/login` returns the JWT; `POST /auth/register` does not, so the app logs
  in right after registering.
- The token is stored with `expo-secure-store` (Keychain / Keystore).
- There is no refresh endpoint. Any 401 on an authenticated call clears the session
  and returns to the login screen with a "session expired" notice. A 401 from login
  itself is shown as wrong credentials.
- On logout or an account switch every React Query cache is cancelled and cleared;
  builder and tailoring results check `useSessionGuard` before touching state or storage.

## Jobs module

- Two id spaces never mix: catalog postings use the string `catalogJobId`
  (`freehire:<slug>`); personal records from `GET /jobs` use a numeric `id`. A personal
  record stores only a hash of the slug, so 已收藏 / 已投递 and the dashboard open the
  personal detail screen (`GET /jobs/{id}`), not the catalog detail.
- Un-saving is `DELETE /jobs/{personalJobId}`: the catalog has no separate unsave
  route, and deleting the record also removes its applied state.
- One `['jobs', userId]` query feeds the dashboard, 已收藏 and 已投递. Every save or
  applied change invalidates it; catalog caches are patched in place and marked stale
  without re-running upstream searches.

## Resumes module

- Two id spaces never mix: uploaded/generated files use `ResumeResponse.id`
  (`GET /resumes`); resume-builder drafts use the draft id (`GET /resume-builder/drafts`).
  Quality score, download and delete only ever take a file id.
- Upload is `POST /resumes` multipart with the file in the `file` part. The app checks
  name, extension (.pdf/.docx), MIME type and the 10 MB limit before sending.
- Download is an authenticated `GET /resumes/{id}/download` (Bearer header, never a URL
  token), written to the app cache under a sanitized name, then shared with
  `expo-sharing`.
- Delete is `DELETE /resumes/{id}`; a PDF with an internal Word working copy uses
  `DELETE /resumes/{id}/family`, as the website does.
- `['resumes', userId]` and `['resume-drafts', userId]` are shared with the dashboard.

## PDF recognized content (确认识别结果)

- Route `/resume/[resumeId]/structure` (params `from=tailor&jobId&mode` when opened from
  tailoring). Entries: a PDF file's detail, and the 改写 tab when canonicalize answers 400.
  返回改写 goes back to the tab underneath, or reopens it with the same resume, job and mode.
- `GET /resumes/{id}/structure` (PDF only, 400 otherwise). The first read may extract the
  layout and run AI classification on the server, so it uses the 180 s timeout.
- `PUT /resumes/{id}/structure` stores the body as the whole structure: no version, no
  merge. The app sends back the loaded object with only the edited fields changed
  (`src/resumes/structure.ts`), so layoutProfile, provenance, source block ids and
  unassigned text are kept. The website's only rule is a non-empty name; the app also warns
  that canonicalize needs a skill or an experience/project bullet.
- Saving changes the stored recognition and puts the PDF back to PENDING_REVIEW; the PDF
  file is not touched. The internal DOCX copy is rebuilt only by the next canonicalize.
- The server keeps no structure version, so the app stores
  `aijob.structureSavedAt.<user>.<resume>` after a successful save. Pending AGGRESSIVE and
  ENHANCED proposals for that PDF made before it are shown as outdated and cannot be
  confirmed; ENHANCED "check and generate again" prepares the DOCX again first. Restoring a
  proposal never canonicalizes.
- Limit: a structure edited on the website is not known to the app.

## Resume builder (生成简历)

- Routes: `/draft/new` (create), `/draft/[draftId]` (summary, delete),
  `/draft/[draftId]/edit` (editor). API: `POST/GET /resume-builder/drafts`,
  `GET/PUT/DELETE /resume-builder/drafts/{id}`, `POST /resume-builder/drafts/{id}/generate`.
- `src/builder/model.ts` mirrors the backend: `normalizeContent` (ResumeBuilderDraftContent
  .normalize), professional-link migration, and `validateForm` with the limits of
  ResumeBuilderDraftValidator. The server still validates every write.
- Saving is explicit. `PUT` carries the loaded `version`; a 409 freezes saving until the
  user reloads, so a newer version is never overwritten. 已保存 only appears after a
  successful response for the exact form on screen; failed saves keep the input.
- Generating saves unsaved edits first, then posts one idempotency key per action. A
  missing or gateway response keeps that key, and 再次确认 resends it, so the backend
  returns the same file instead of creating a second one. Definite failures drop it.
- Leaving with unsaved edits (back button, Android back, iOS swipe) asks first
  (`useLeaveGuard`).
- Production status (read-only EC2 diagnosis, 2026-09-14): **the route is missing**. The
  backend logged `NoResourceFoundException: No static resource api/v1/resume-builder/drafts`
  for the app's requests, and the running `/app/app.jar` has no `ResumeBuilderController`.
  The server checkout is a clean `281e8dd`; the running image has no build revision metadata
  (class evidence matches `281e8dd`). The database has migrations up to site-feedback only:
  `resume_builder_drafts`, `resume_builder_generation_requests` and `job_search_history` do
  not exist; every other table/column the app needs is present. The app shows the failure
  with `HTTP <status> · <path>`, never as an empty list.
- The app's builder depends on uncommitted backend changes (`professionalLinks`, template
  layout, target-role sync); no existing commit matches. A release needs a new reviewed commit
  of those backend changes plus the matching website changes, and `infra/aws/release.sh`,
  which runs nine gated migrations, including ALTER/MODIFY on existing `users`, `jobs`,
  `job_sources`, `job_catalog` and `job_source_records`. The reviewed plan with the
  server evidence to collect, backup, order, verification and rollback is kept outside the
  repository (not executed).
- Not edited in the app, but preserved on save: section order, career materials
  (求职材料). Preview (`POST .../preview`) and duplicate are not used.

## Tailor to a job (按职位改写)

- The 改写 bottom tab (`/tailor?jobId&resumeId&mode&at`), between 简历 and 我的. The job and
  resume are chosen inside the tab. Shortcuts: the dashboard button (switches tab, keeps the
  current choices), a personal job's detail and a saved catalog posting (preselect the job).
  Resume lists and file details have no tailoring button. A tab stays mounted, so a
  preselecting shortcut sends a new `at` and the screen opens again with those choices; if a
  request is running or answers are unsaved the app asks first. Personal jobs only
  (`/jobs/{id}`), and the job needs a description (the server answers 422 otherwise).
- Resumes: DOCX directly; a PDF first goes through `GET /resumes/{id}/structure` and
  `POST /resumes/{id}/canonicalize`, and the internal DOCX copy is tailored, as the website does.
- CONSERVATIVE: `POST /jobs/{jobId}/tailor?resumeId&mode=CONSERVATIVE` writes the file at once.
- AGGRESSIVE: the same route with `mode=AGGRESSIVE` returns a PENDING proposal and no file
  (24 h TTL). Only `POST /jobs/{jobId}/tailor-proposals/{id}/confirm` with every proposed
  `selectedNodeIds` writes the file. Discarding never calls confirm.
- The proposal id is kept per account and job (`src/tailor/pendingProposal.ts`) and reopened
  with `GET .../tailor-proposals/{id}`. A lost confirm response is checked with that GET
  (PENDING -> confirm again; 404 -> confirmed, expired or missing; the file cannot be retrieved).
- Downloads use only `/api/v1/jobs/{jobId}/tailored/{uuid}.docx|pdf` from the response,
  with the Bearer header (`src/tailor/files.ts`).
- AI calls use a 180 s client timeout (`TAILOR_TIMEOUT_MS`).

### ENHANCED (深度增强)

- A third product mode, identified by `productMode=ENHANCED` (its `mode` is CONSERVATIVE).
  Code: `src/tailor/enhanced/` (rules ported from the website helpers, the flow hook, the UI).
- Flow: prepare DOCX (PDF: structure -> canonicalize, once) -> `POST .../tailor-enhanced/opportunity`
  -> questions (max 5; "did / did not / skip"; "did" needs real detail) -> answers sent one by
  one to `.../tailor-enhanced/answers` (stops at the first failure; only unsaved or changed
  answers are resent) -> scan again -> `POST .../tailor-proposals?productMode=ENHANCED` (no file)
  -> review entry and skill cards (select, edit, restore AI text) -> confirm with
  `selectedEntryKeys/editedEntries/selectedSkillReviewIds/editedSkillReviews` -> file.
- Evidence (`/resumes/{id}/evidence`) can be corrected or deleted from the review; the server
  does not recompute the proposal, so it is marked stale and cannot be confirmed.
- Records (`src/tailor/records.ts`): ENHANCED pending `aijob.tailor.<user>.<job>.ENHANCED`
  (ids, prepared DOCX id, expiry, stale flag), separate from the AGGRESSIVE key; known
  results `aijob.tailorResult.<user>.<job>.<mode>` keep a validated download path on this
  device only -- the server has no tailoring history.

## Coverage compared with the website

Checked against `frontend/src/App.tsx` routes, each page's API imports, and the backend
controllers.

Fully wired in the app: login/register, dashboard, job catalog search/detail/save/applied,
saved and applied personal jobs (detail, delete, applied), resume upload/list/detail/
download/delete/quality score, 我的 (name, email, password, language, feedback, logout,
account deletion), resume builder (create, edit, save, generate, delete), tailoring
(CONSERVATIVE, AGGRESSIVE, ENHANCED with evidence).

On the website, not in the app:

| Website | Source | Backend |
|---|---|---|
| Create / edit / bulk-delete personal jobs | `pages/JobsPage.tsx` (`createJob`, `updateJob`, `bulkDeleteJobs`) | `POST /jobs`, `PUT /jobs/{id}`, `DELETE /jobs/bulk` |
| Job-fit analysis and requirement-profile refresh on the tailor page | `pages/TailorPage.tsx` (`analyzeTailorFit`, `refreshJobRequirementProfile`) | `POST /jobs/{jobId}/tailor-analysis[/profile/refresh]` |
| AGGRESSIVE per-change selection, editing and "find more improvements" | `pages/TailorPage.tsx` (`ResultPanel`, `continueTailorProposal`) | `.../confirm` with `editedChanges`, `.../continue` |
| Resume builder live preview and duplicate | `pages/ResumeBuilderEditorPage.tsx`, `ResumeBuilderListPage.tsx` | `POST .../preview`, `POST .../duplicate` |
| Public home page with feedback summary | `pages/HomePage.tsx` | `GET /feedback/summary` |

Not possible with the current backend: a tailoring history or retrieving a tailored file
without the download path returned at the time; looking up a CONSERVATIVE run whose
response was lost; idempotent proposal creation; checking whether ENHANCED is enabled
before calling it; continue for ENHANCED.

## Installable test builds

`npx expo export` is not an installable build. Current state (2026-09):

- Present: `app.config.ts` with name, version 1.0.0, icons, bundle id / package
  `com.tyhstar.aijobassistant`; no native modules outside Expo SDK.
- Missing: `eas.json`, `eas-cli`, an Expo login for this project, and on this computer
  a JDK 17 and the Android SDK (so no local Android build).
- API URL: a release build does not use the LAN fallback; it needs
  `EXPO_PUBLIC_API_BASE_URL=https://<public host>/api/v1` at build time. The deployed site
  is HTTPS behind Caddy (`infra/aws`), which release builds need (cleartext HTTP is
  blocked by default on Android release builds and by iOS ATS).

### Android APK for testers (EAS preview profile)

`eas.json` already has the `preview` profile: `distribution: internal`,
`android.buildType: apk`, and `EXPO_PUBLIC_API_BASE_URL=https://3.99.54.214.sslip.io/api/v1`
(the deployed site, HTTPS through Caddy). The address is compiled into the app at build
time: if the site's host or EC2 public IP changes, update `eas.json` and build again.
`appVersionSource: local` keeps the version from `app.config.ts`.

Linked project: `@tangyaohang/ai-job-assistant` (projectId
`cbcc33de-f3b2-4fd4-9034-486a836a04d8`, set as `owner` and `extra.eas.projectId` in
`app.config.ts`). The Android keystore was generated by EAS on the first build and is kept in
that Expo account.

`mobile/` is an untracked folder inside the repository's git root, so builds run without git
and upload only `mobile/` (respecting `mobile/.gitignore`; `node_modules` and `.git` are never
uploaded). Uncommitted code is built as it is on disk; nothing needs committing:

```bash
EAS_NO_VCS=1 EAS_PROJECT_ROOT="<absolute path to mobile>" npx eas-cli@latest build -p android --profile preview
```

Steps for another machine or account, run in `mobile/`:

1. Install the CLI: `npm install -g eas-cli` (or prefix every command with `npx eas-cli@latest`).
2. Log in with the account that will own the app: `eas login`, then check with `eas whoami`.
3. Link the project: `eas init`. This creates the project on expo.dev and returns a
   project id. Because the config is `app.config.ts`, add it by hand if the CLI asks:
   `extra: { eas: { projectId: '<id>' } }` (and optionally `owner: '<account>'`).
   Do not change `android.package` (`com.tyhstar.aijobassistant`).
4. Build the APK remotely: `eas build -p android --profile preview`. On the first build
   answer "yes" to letting EAS generate the Android keystore; it is stored in the Expo
   account and reused, so later builds install as updates of the same app.
5. Install on a phone: open the build page link or QR code printed at the end (also under
   expo.dev → project → Builds) on the Android phone, download the `.apk`, and allow
   installing from this browser/file manager when Android asks. On a computer with adb:
   `adb install <file>.apk`. `eas build:run -p android --latest` installs it on an
   emulator.
6. First launch: log in with a test account (not a real user's), then follow the device
   acceptance checklist below. A "configuration error" on login means the build did not
   receive `EXPO_PUBLIC_API_BASE_URL`.

Local check of what the preview build bundles (no build, no login):

```bash
EXPO_PUBLIC_API_BASE_URL=https://3.99.54.214.sslip.io/api/v1 npx expo export --platform android --clear
```

Keep `--clear`: without it Metro can reuse a cached bundle made without the variable, and
the exported code then has no API address (seen once here). EAS builds start from a clean
machine, so they are not affected.

A local Android build instead needs JDK 17 + Android SDK, then `npx expo prebuild` and
`npx expo run:android --variant release` (creates the ignored `android/` folder).

iOS (always a remote build from Windows): a paid Apple Developer account; for ad hoc
installs register each device with `eas device:create`, then
`eas build -p ios --profile preview` (EAS creates certificates and profiles when asked);
or use a production profile with `eas submit -p ios` to TestFlight.

## Device acceptance checklist

Status: **auto** = covered by Jest (listed file) or a checked export; **device** = still
to verify on a phone, not done yet; **backend** = the server does not support it.
Nothing below has been confirmed on a real device yet.

### Login and session
- [auto `LoginScreen`, `AuthContext`] wrong password message, session restore, 401 signs out with notice
- [auto `sessionAndLanguage`, `signedInLayout`] cache cleared on logout / account switch; signed-out redirect
- [device] cold start keeps the session; token expiry or password change elsewhere returns to login
- [device] logout then log in as another account: no jobs, resumes, drafts, tailoring records of the first

### Dashboard
- [auto `DashboardScreen`, `dashboardModel`] empty onboarding, sections, entries to jobs/resumes/draft/quality/tailor
- [device] every card and button opens the right screen and back returns to the dashboard

### Jobs
- [auto `JobsScreen`, `CatalogJobDetailScreen`, `JobDetailScreen`] search, pagination, save/unsave, applied, delete, gone postings, tailoring entry after save
- [device] keyboard over search and city fields; pull to refresh; Android back and iOS swipe from details

### Resumes
- [auto `ResumesScreen`, `ResumeFileScreen`, `resumeShare`, `displayName`] type/size checks, upload progress, Chinese names, share, delete family, tailoring entry
- [device] pick a PDF/DOCX with a Chinese name; the list shows it readably; share sheet opens the file with the Chinese name
- [device] quality score for an uploaded PDF and a generated DOCX

### 我的
- [auto `ProfileScreen`, `profileApi`] name/email/password errors, feedback empty vs failed, logout and deletion confirmations
- [device] language switch persists after restart; do not run account deletion on a real account

### Resume builder
- [auto `DraftEditorScreen`, `builderModel`, `leaveGuard`, draft tests] save/reopen, failed save keeps input, 409 conflict freeze, generate once, unknown result reuses the key, account switch
- [device] long form scrolls with the keyboard; unsaved edits prompt on back button, Android back and iOS swipe
- [device] edit the same draft on the website, then save in the app: conflict notice, no overwrite
- [device] generated DOCX appears under 上传的简历 and shares with the name

### Tailor to a job
- [auto `TailorScreen`, `tailorModel`] CONSERVATIVE direct file; AGGRESSIVE preview without file, discard, confirm once, 404/409, restore
- [auto `EnhancedTailor`, `enhancedRules`] ENHANCED questions, partial answer failure and retry, selection/edit body, evidence change blocks old suggestion, restore without re-canonicalize, lost confirm check, known downloads, account switch
- [device] each mode with a DOCX and a PDF on the real backend; downloads of DOCX and PDF share correctly
- [device] ENHANCED: correct/delete a detail, leave the app, reopen: the suggestion is still blocked
- [device] airplane mode during run / answers / confirm: no automatic resubmit; check status after reconnecting
- [device] long AI run (1–2 min) keeps the screen responsive; leaving asks first
- [backend] retrieve an earlier tailored file without its saved download path; tailoring history

### PDF recognized content
- [auto `ResumeStructureScreen`, `resumeStructure`] read vs not found vs not PDF vs failed; name required; PUT keeps unedited fields; failed save keeps input; leave prompt reason; account switch drops read and save
- [auto `TailorScreen`, `EnhancedTailor`] incomplete recognition opens the review with job/resume/mode; rerun prepares from the saved content; outdated proposals cannot be confirmed; ENHANCED regenerate prepares again
- [device] first open of a newly uploaded PDF (server recognition, may take a minute); Chinese text shows correctly
- [device] edit, leave without saving (back button, Android back, iOS swipe): asked first; save, then back to tailoring and start again
- [device] PDF with incomplete recognition: fix the name / add a bullet, save, then CONSERVATIVE and ENHANCED succeed
- [backend] a structure changed on the website is not detected by the app (no server version)

### General
- [auto] Android and iOS `expo export` bundles build
- [device] safe areas on a notched iPhone and gesture-navigation Android; Chinese and English layouts do not clip
- [device] offline: each list shows a request failure with retry, not an empty state

## Status

- Done: scaffold, login/register, dashboard, jobs, resumes, 我的, resume builder,
  tailor to a job (CONSERVATIVE, AGGRESSIVE, ENHANCED), PDF recognized content review.
- Not in the app: see "Coverage compared with the website". 求职材料 is withdrawn.
- Device acceptance: not recorded yet.
