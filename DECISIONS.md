# Decisions log — Nock v1

Decisions made while implementing plan.md that were not pinned down in the
plan. Sync back into plan.md if you want any of these to become canonical.

## Identity

- **Application ID / package:** `app.nock.android` (matches the suggestion).
- **App display name:** "Nock".
- **versionCode 1**, **versionName "1.0"**.

## Toolchain & build

- **JDK:** Build uses JDK 17 (plan's requirement). Container only had JDK 21
  preinstalled, so `apt-get install openjdk-17-jdk-headless` was used.
- **Android Gradle Plugin:** 8.7.3 + Gradle 8.10.2.
- **Kotlin:** 2.0.21 with the standalone Compose compiler plugin
  (`org.jetbrains.kotlin.plugin.compose`).
- **KSP** for Room and **kapt** for Hilt — Hilt does not yet ship a KSP
  processor that's stable for application targets.
- **Compose BOM:** 2024.11.00. Material 3 1.3.1.
- **compileSdk 35, targetSdk 35, minSdk 29** as required.
- **Tests:** JUnit4 only. No instrumented tests, no UI tests — explicitly out
  of scope for v1.

## Signing

- A debug keystore is checked in at `keystore/debug.keystore` (`storepass
  android / alias androiddebugkey / keypass android`). It signs **both**
  debug and release builds for v1 — the plan said "debug keystore is fine
  for v1". When you move to public release, rotate this out.

## Default groups

Seeded on first launch (Pets, Meds, Self-care, Household, Work, Errands)
with Material-You-friendly accent colors. Edit/delete/reorder freely from
the Reminders screen — they are not special.

| Group     | Icon name (Material icon)  | Color       |
|-----------|---------------------------|-------------|
| Pets      | Pets                      | `#FFB070`   |
| Meds      | Medication                | `#FF6B6B`   |
| Self-care | FavoriteBorder            | `#B388FF`   |
| Household | Home                      | `#80CBC4`   |
| Work      | Work                      | `#8AB4F8`   |
| Errands   | ShoppingBag               | `#F6BF26`   |

Icons are stored as the Material icon **name** (string). v1 doesn't render
the icons in lists yet — group identity uses the first letter + color
swatch. Wiring up an icon picker is a small polish task.

## Default escalation chain

Matches §12 of plan.md exactly:

- Silent     @ −10 min
- Normal     @   0 min
- Telegram   @ +5 min
- Loud alarm @ +10 min (repeats every 10 min until Done)

Stored in `settings(key='stage_chain', value=<json>)`. Per-group override
is a nullable JSON column on `groups`.

## Snooze semantics

Implemented exactly as §3.3:

- Stage N (not last): snooze just suppresses the current stage's
  notification + sound. Stage N+1's alarm has already been scheduled
  (we always advance the DB cursor at the moment the alarm fires), so the
  chain continues at the next stage's planned absolute time.
- Stage N (last): snooze cancels current notification + sound, then
  re-schedules the same stage at `now + repeatIntervalMs`.

## Done semantics

- Only the explicit "Done" action stops the chain. Notification dismissal
  (swipe) and alarm-activity dismissal (back gesture / home) do **not**.
- The ongoing alarm notification is `setOngoing(true)` so the user cannot
  swipe it away.
- After Done: cancel pending alarm + active notification + foreground
  service, mark `lastCompletedAt = now`, then immediately schedule the
  next fire for recurring/interval schedules.

## Boot behavior

- `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`
  all trigger `EscalationEngine.rescheduleAll()`.
- For active escalations, the stored next-fire-at and stage index are
  re-armed with `AlarmManager`. For non-active reminders whose stored
  `nextFireAt` is in the past, a fresh escalation chain starts now,
  beginning at stage 0 (per plan §5.4). The synthetic `startedAt` is
  shifted so the chain rolls out at its normal spacing rather than
  back-to-back.

## Last-stage repeat

Schedules another fire of the same stage at `now + repeatIntervalMs`. No
"give up" branch — the loop is unbounded by design. Repeat interval is
global with per-group override (10 min default).

## Telegram

- User pastes bot token + chat ID into Settings. We POST directly to
  `api.telegram.org/bot<token>/sendMessage` with OkHttp.
- "Test send" button uses a hardcoded test text.
- Silent-stage mirror is **off by default per group**; when on, the
  silent-stage Telegram payload sets `disable_notification: true`.

## Drive sync

- Uses Google Sign-In + `DRIVE_APPDATA` scope through the official Drive
  Android client (`google-api-services-drive` v3-rev20240914-2.0.0). The
  newer client jars dropped `AndroidHttp` — switched to `NetHttpTransport()`.
- Snapshot file name: `nock-snapshot.json` in `appDataFolder`.
- Conflict policy: last-write-wins at the snapshot level.
  `pullIfNewer()` only merges if `remote.savedAtMs >
  local.KEY_DRIVE_LAST_REMOTE_MS`.
- On import, local DB is wiped and replaced wholesale, then
  `rescheduleAll()` is run. This is the simplest safe approach for a
  single-user setup.
- `GoogleSignIn` API is deprecated by Google in favor of Credential Manager,
  but the new API doesn't yet have a clean Drive App Folder story. Kept
  the deprecated path with a `@SuppressWarnings`-equivalent (compiler
  warnings only, no errors).

## Natural-language parser

Scope is the patterns explicitly named in §4.3:

- Times: `8am`, `8:30am`, `08:00`, `18:30`, `8am and 6pm`.
- Cadence: `every day`, `daily`, `weekly on Tue`, `on Mon`, `every 8 hours`,
  `every 45 min`.
- Dates: `tomorrow`, `today`, `2026-04-15`, `Jan 15`, etc.
- Group hint: trailing `— GroupName` or `#GroupName`.

Best-effort, as the plan says. The structured form is the source of truth
and is pre-filled by what was parsed.

## ViewModel / UseCase split

No separate UseCase layer — the codebase is small, so ViewModels call
`NockRepository` and `EscalationEngine` directly. Adding UseCases now
would be a premature abstraction.

## Navigation

- Compose Navigation. Three tabs (Today / Reminders / Settings) +
  `edit?id=N` route for create/edit. `id=0` means "new reminder".

## Room migrations

- v1 schema; `fallbackToDestructiveMigration()` enabled. The plan said
  destructive is fine for v1, no production data yet.

## Permissions UX

- `MainActivity` requests `POST_NOTIFICATIONS` on first launch (API 33+)
  and opens the system "Schedule exact alarms" settings page on API 31+
  if the permission isn't already granted. There is no dedicated
  onboarding screen; the system dialogs are the UX.

## Things deliberately deferred (matches §11)

- History / stats dashboard — `last_completed_at` is recorded, not shown.
- Home-screen widget.
- TTS.
- Voice input.
- Onboarding tutorial.
- Per-reminder escalation override (only per-group in v1).
- Snapshot-level multi-device merge — last-write-wins only.
- Wear OS, geofences, sub-tasks, additional channels (SMS, ntfy, etc.).
- Localization.
- Repeating the same stage twice in a chain (`EscalationChain.init`
  enforces uniqueness).
- Configurable acknowledgment difficulty (puzzles / QR codes etc.).

## Off-plan polish that snuck in

- Snooze button on the silent-stage notification: technically the silent
  stage's UX is "fire-and-forget" but I kept the snooze action visible on
  every stage's notification so it behaves consistently. If you'd rather
  hide it for the silent stage specifically, edit
  `NotificationPresenter.baseBuilder`.
- "Today" tab shows a friendly empty state when there are no reminders.
- Settings screen shows the global stage chain editor that lets you
  reorder, remove, re-add, and change the offset of each stage. Per-group
  override surface is the "Custom chain" toggle inside each group's
  editor.
