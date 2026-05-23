# Nock — Escalating Reminders for ADHD Brains

> A private Android app that delivers reminders gradually, from a silent
> heads-up to a screen-takeover alarm, so important tasks never slip — but
> the loud alarm only fires when the gentler nudges have already failed.

---

## 1. Purpose & motivation

**Problem.** I have ADHD. I currently rely on the system alarm app to remind me
of critical tasks (e.g. feeding the dog), because alarms are loud enough that I
*never* miss them. The downside: even when I already know a task is coming up,
the alarm still blasts at full volume, which is jarring and annoying.

**The core insight.** Reminders should *escalate*. If I notice the soft
notification and handle the task, the loud alarm should never fire. The alarm
exists as the **last line of defense**, not the first.

**Outcome I want.** Same reliability as the system alarm app (zero missed
critical tasks), with a much gentler average daily experience.

**Scope.** This is a personal tool. Just me, possibly a small private circle
(partner / family) later. Closed source. Sideloaded.

---

## 2. Target user

- **Primary user:** me (the author), and possibly 1–3 trusted people I share the
  APK with.
- **Usage frequency:** opened occasionally — most interaction happens *through
  notifications* (acknowledging tasks), not by opening the app. The app must
  feel invisible until needed, then unmissable.
- **Critical UX constraint:** ADHD-friendly. That means:
  - Quick capture (low friction to add a new reminder).
  - Hard to dismiss accidentally — auto-dismissing notifications is a real risk.
  - No infinite-snooze trap — snoozing must not let me bury a task indefinitely.
  - Low visual noise.

---

## 3. Core concept: escalating reminders

A reminder progresses through stages over time. Each stage is a more
attention-demanding way of reaching me. The chain stops only when I press an
explicit **Done** button.

### 3.1 Escalation stages (in order, low → high)

1. **Silent notification** — appears in the status bar, no sound, no vibration.
   Also serves as the **pre-emptive heads-up**: if I notice it, I can mark the
   task done before any noise happens. Optionally mirrored as a *silent*
   Telegram message.
2. **Normal notification + sound** — standard notification with the device's
   default notification sound and vibration.
3. **Telegram message** — pushed to my Telegram chat via a user-configured bot.
   This reaches me even when my phone is across the room (I usually have
   Telegram on my laptop).
4. **Loud alarm + spoken TTS** — full-screen alarm-clock-style takeover.
   Pierces Do Not Disturb. Speaks the task aloud via Text-to-Speech (e.g.
   *"Feed the dog!"*) so I hear it even if the phone isn't visible. **Repeats
   indefinitely until I press Done.**

### 3.2 Acknowledgment

- Only an **explicit "Done" button** stops escalation.
- "Done" is presented as a notification action *and* as a button inside the
  full-screen alarm activity.
- Swiping a notification away does **not** count as Done.

### 3.3 Snooze

- Snooze pauses *all visible/audible output* for a chosen duration (default
  10 min, configurable).
- **The escalation timer keeps running in the background.** So if I snooze
  during the silent stage and only acknowledge 20 minutes later, when the
  snooze ends I may already be at the loud-alarm stage.
- This is intentional: it prevents the failure mode of snoozing silent
  notifications forever and never doing the task.
- Snooze is available as a notification action and inside the alarm activity.

### 3.4 Escalation timing

- **Global default** escalation schedule (e.g. silent → +5 min → normal sound →
  +5 min → Telegram → +5 min → loud alarm).
- **Per-group override.** Each group can define its own escalation schedule
  (e.g. *Pets* escalates fast; *Self-care* escalates slowly).
- Timing for each stage is defined as **offset from the reminder's scheduled
  time** (not "delay since previous stage"), so the model is deterministic and
  easy to reason about.

### 3.5 Critical-task fallback

If even the loud-alarm stage is ignored, the alarm **keeps re-firing forever**
until I press Done. There is no "give up and log as missed" behavior.

---

## 4. Reminders & scheduling

### 4.1 Schedule types

- **Recurring at fixed times** — e.g. *"Feed dog every day at 08:00 and 18:00"*.
  Supports daily / weekly (by day-of-week) / monthly cadences.
- **One-shot** — single fire at a specific date/time.
- **Interval from last completion** — e.g. *"Remind me every 8 hours starting
  from when I last marked this done"*. Useful for medication-style tasks where
  the absolute time matters less than the gap between doses.

### 4.2 Groups

- A small set of broad groups (~5–10), e.g. *Pets, Meds, Self-care,
  Household, Work*.
- Each group owns:
  - Its own escalation schedule (or inherits global default).
  - A pause state (see §6).
  - A display color/icon (for the dashboard and notifications).
- A reminder belongs to exactly one group.

### 4.3 Quick-add UX

Adding a reminder must be friction-free. The Add screen has:

- A **natural-language input bar at the top**, e.g. typing
  *"Feed dog every day at 8am and 6pm — Pets"* parses into a structured
  reminder with name, schedule, and group.
- A **structured form** below, pre-filled by the parser. I can correct anything
  the parser got wrong before saving.
- The parser handles common patterns (times, "every day", "weekly on Tue",
  "every 8 hours", date strings). It is best-effort — the form is the source of
  truth.

### 4.4 Pause

- **Per-group pause** toggle. Pausing a group prevents any new reminders from
  that group from firing until unpaused.
- Already-firing escalations are *not* cancelled by pausing the group — they
  must still be marked Done. (Pause only prevents *new* fires.)
- Optional auto-resume timer ("pause Pets for 4 hours").

---

## 5. Notifications, alarms & system integration

### 5.1 Channels

- A dedicated **Android notification channel per escalation stage** so I (or
  Android settings) can independently control behavior per stage:
  - `nock_silent` — IMPORTANCE_LOW
  - `nock_normal` — IMPORTANCE_DEFAULT
  - `nock_telegram_mirror` — IMPORTANCE_MIN (optional, for the silent Telegram
    mirror at stage 1)
  - `nock_alarm` — IMPORTANCE_HIGH with `USAGE_ALARM`, bypass DND

### 5.2 Do Not Disturb

- **Always overrides DND**, like the system alarm-clock app. Implemented via
  alarm-category audio attributes + full-screen intent on the alarm stage.
- This is intentional: missing a critical reminder defeats the whole point.

### 5.3 Scheduling on the device

- Use **`AlarmManager.setAlarmClock()`** for the loud-alarm stage — it's the
  only API on modern Android that reliably fires while Doze/standby is active.
- Earlier escalation stages can use `setExactAndAllowWhileIdle()` for lower
  battery impact.
- A foreground **`AlarmService`** owns the active alarm sound + TTS while the
  user is interacting with the full-screen alarm activity.

### 5.4 Boot behavior

- `BOOT_COMPLETED` receiver reschedules all future reminders.
- Any reminder whose scheduled time passed while the phone was off **fires
  immediately on boot** (so I don't miss things if my battery dies overnight).
- The escalation chain restarts at stage 1 (silent) on boot, not at whatever
  stage it would have reached — to avoid waking me with a sudden alarm.

### 5.5 Permissions required

- `POST_NOTIFICATIONS` (Android 13+)
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`
- `RECEIVE_BOOT_COMPLETED`
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (for the alarm
  service)
- `WAKE_LOCK`
- `USE_FULL_SCREEN_INTENT`
- `INTERNET` (Telegram, Drive sync)
- `GET_ACCOUNTS` (Google Drive auth)

The first-run flow guides me through granting each one and explains *why*.

---

## 6. Telegram integration

- **User-provided bot.** I create my own Telegram bot via @BotFather, paste the
  bot token + my chat ID into the app's settings. No shared backend, no third
  party between me and Telegram.
- The app sends messages directly to the Telegram Bot API from the device.
- A "Send test message" button in settings confirms the configuration works.
- Telegram is optional. If unconfigured, that escalation stage is silently
  skipped and the chain advances to the next stage.
- The silent-stage mirror message uses `disable_notification: true` so it
  doesn't ping me.

---

## 7. Data & sync

### 7.1 Local storage

- **Room (SQLite)** on-device, single source of truth while the app is running.
- Schema (initial sketch):
  - `groups(id, name, color, icon, escalation_schedule_json, paused_until)`
  - `reminders(id, group_id, name, schedule_type, schedule_json,
    escalation_override_json, last_completed_at, next_fire_at)`
  - `active_escalations(id, reminder_id, started_at, current_stage,
    snoozed_until)`
  - `settings(key, value)` — global defaults, Telegram config, sync state

### 7.2 Cloud sync

- **Google Drive App Folder** (`drive.appdata` scope) — invisible to the user,
  doesn't clutter their Drive UI.
- The app periodically (and on significant changes) writes a single JSON
  snapshot of the database to the App Folder.
- On startup, the app checks for a newer snapshot than what's local and merges
  it.
- Conflict policy for v1: **last-write-wins** at the snapshot level. Multi-user
  shared editing is out of scope.
- Sync is best-effort. Reminders work offline.

### 7.3 History

- **No history / dashboard view** in v1. Keep the app minimal.
- The database still records `last_completed_at` (needed for interval-based
  schedules) — it's just not surfaced in the UI.

---

## 8. UI / look & feel

- **Jetpack Compose + Material 3**.
- **Material You** dynamic colors (adapts to the user's wallpaper).
- Dark mode follows system.
- Three primary screens:
  1. **Today** — list of upcoming and currently-firing reminders. Each row has
     a Done button. This is the launch screen.
  2. **Reminders** — full list grouped by group, with add/edit.
  3. **Settings** — global escalation defaults, Telegram config, Google Drive
     sync status, permissions check.
- A **home-screen widget** (stretch goal) showing today's upcoming reminders
  with one-tap Done.

---

## 9. Tech stack

Recommended best-fit, given the heavy reliance on Android-specific APIs
(AlarmManager, full-screen intents, foreground services, notification channels):

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Persistence:** Room
- **Scheduling:** `AlarmManager` (`setAlarmClock` for the alarm stage,
  `setExactAndAllowWhileIdle` for earlier stages). **Not** WorkManager — its
  ~15 minute minimum and Doze deferral make it unsuitable for the precision
  this app needs.
- **Async:** Kotlin coroutines + Flow
- **DI:** Hilt
- **Telegram:** raw HTTPS calls via OkHttp (the Telegram Bot API is trivial)
- **TTS:** Android's built-in `TextToSpeech`
- **Natural-language parsing:** a small custom parser for common patterns; no
  external service.
- **Cloud sync:** Google Drive REST API (App Folder scope) via the
  Google Drive Android client library
- **Min SDK:** 29 (Android 10) — keeps coverage broad while letting us use
  modern notification APIs
- **Target SDK:** latest stable

---

## 10. Distribution & release

- **GitHub Releases** as signed APK.
- Private repo, sideload only.
- No Play Store, no F-Droid (avoids review overhead; this is a personal tool).
- Versioning: semver. Release notes in the GitHub Release body.

---

## 11. Out of scope (v1)

The following are deliberately deferred — captured here so we don't
scope-creep:

- History / stats dashboard
- Wear OS companion / watch face
- Location- or event-triggered reminders (wifi connect, geofence, etc.)
- Nested groups, tags
- Sub-tasks / dependencies between reminders
- Per-reminder escalation override (only per-group override in v1)
- Multi-user shared lists / real-time multi-device sync (snapshot sync only)
- Multi-channel push (SMS, email, ntfy.sh, Discord) — only Telegram in v1
- Configurable acknowledgment difficulty (puzzles, QR codes, shake-to-dismiss)
- Voice input for adding reminders
- Onboarding tutorial
- Backup formats beyond Google Drive
- Localization beyond English

---

## 12. Open questions / decisions still to make

- **App name.** Working title is *Nock* (matching the repo). Confirm or pick
  another.
- **Default escalation schedule.** Proposed: silent → +5 min → normal →
  +5 min → Telegram → +5 min → loud alarm. Confirm intervals.
- **Default snooze duration.** Proposed: 10 minutes.
- **Alarm sound.** Proposed: device's default alarm sound, with the option to
  pick a custom one in Settings.
- **TTS voice / language.** Proposed: system default voice in the device
  language.
- **Pre-emptive Telegram mirror.** Should the silent stage always mirror to
  Telegram (silently), or only when explicitly enabled per group?

---

## 13. Build milestones

A rough sequence — each milestone should leave the app installable and
exercisable end-to-end on the next slice of functionality.

1. **M1 — Skeleton.** Empty Compose app, Hilt, Room schema, Today screen
   listing hard-coded reminders.
2. **M2 — One-shot reminders firing.** Add reminder via form, AlarmManager
   schedules it, single silent notification appears at the right time. Done
   button removes it.
3. **M3 — Full escalation chain.** Stages 1–4 (silent → normal → Telegram →
   loud alarm), driven by per-reminder offsets. Snooze with background
   escalation. Done stops the chain at any stage.
4. **M4 — Recurring + interval schedules.** Daily/weekly/monthly recurrence and
   interval-from-last-completion. Boot receiver reschedules everything.
5. **M5 — Groups + per-group escalation override + pause.**
6. **M6 — Telegram integration with user bot token.**
7. **M7 — TTS on the alarm stage.**
8. **M8 — Natural-language quick-add bar.**
9. **M9 — Google Drive App Folder sync.**
10. **M10 — Polish, Material You theming, first signed release on GitHub.**

Stretch (post-v1): home-screen widget, history view, additional push channels.
