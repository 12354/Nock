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

### 3.1 Available stages

The app supports four stages. The **order and selection** is a global setting
the user configures (see §3.4). Each stage may appear at most once in the
chain, and any stage may be omitted entirely if it doesn't suit the user.

- **Silent notification** — appears in the status bar, no sound, no vibration.
  When configured as the first stage, this also acts as a **pre-alarm
  heads-up**: it fires *before* the reminder's scheduled time so the user can
  pre-empt the noisy stages. Optionally mirrored as a *silent* Telegram message
  (per-group opt-in, see §6).
- **Normal notification + sound** — standard notification with the device's
  default notification sound and vibration.
- **Telegram message** — pushed to the user's Telegram chat via a
  user-configured bot. Reaches the user even when the phone is across the room.
- **Loud alarm** — full-screen alarm-clock-style takeover. Pierces Do Not
  Disturb. Plays the configured alarm sound (default = device's system alarm
  sound; user-pickable in Settings).

### 3.2 Acknowledgment

- Only an **explicit "Done" button** stops escalation.
- "Done" is presented as a notification action *and* as a button inside the
  full-screen alarm activity.
- Swiping a notification away does **not** count as Done.

### 3.3 Snooze

Snooze is designed so it can't become an infinite-silence trap.

- **Snooze advances to the next stage.** Pressing snooze suppresses the current
  stage's notification/sound and waits until the *next* stage in the chain
  would have fired anyway — at which point the next stage fires normally.
  In other words, snooze ≡ "skip ahead to whatever's coming next."
- **At the final (last-configured) stage**, where there is no "next stage,"
  snooze pauses for a configurable duration (default **10 minutes**, global
  setting, per-group overridable) before re-firing the same stage.
- Snooze is available as a notification action and as a button in the
  full-screen alarm activity.

### 3.4 Stage order, selection, and timing

- **Global stage chain.** The user defines the ordered list of stages (a subset
  of the four, no repeats) in Settings. This is the default chain for all
  groups. The recommended default is:

  1. Silent  *(fires 10 min before scheduled time as pre-alarm heads-up)*
  2. Normal notification + sound  *(fires at scheduled time)*
  3. Telegram  *(fires +5 min after scheduled time)*
  4. Loud alarm  *(fires +10 min after scheduled time, then repeats every
     10 min until Done)*

- **Per-group override.** A group can override both the stage chain *and* the
  timing offsets (e.g. *Pets* uses the default; *Self-care* uses
  Silent → Normal only, with no Telegram or alarm).
- **Timing model.** Each stage in the chain has an **offset relative to the
  reminder's scheduled time**. The first stage's offset can be negative (e.g.
  silent pre-alarm at −10 min), zero, or positive. The model is absolute (not
  "delay since previous stage") so it's deterministic and easy to reason about.

### 3.5 Critical-task fallback: keep firing until Done

- The **last stage in the configured chain repeats forever**, on a fixed cycle,
  until Done is pressed.
- The cycle interval defaults to the same 10-minute "final-stage snooze"
  duration (global setting, per-group overridable).
- There is no "give up and log as missed" behavior. If the loud alarm is the
  user's last stage (the recommended default), the loud alarm keeps re-firing
  every 10 min indefinitely.

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

- A dedicated **Android notification channel per stage type** so the user (and
  the system Settings UI) can independently control behavior per stage:
  - `nock_silent` — IMPORTANCE_LOW (no sound, no vibration)
  - `nock_normal` — IMPORTANCE_DEFAULT (system notification sound + vibration)
  - `nock_alarm` — IMPORTANCE_HIGH with `USAGE_ALARM`, bypasses DND
- The Telegram stage does not need an Android channel (it's an outbound HTTPS
  call, not a local notification).

### 5.2 Do Not Disturb

- **Always overrides DND**, like the system alarm-clock app. Implemented via
  alarm-category audio attributes + full-screen intent on the alarm stage.
- This is intentional: missing a critical reminder defeats the whole point.

### 5.3 Scheduling on the device

- Use **`AlarmManager.setAlarmClock()`** for the loud-alarm stage — it's the
  only API on modern Android that reliably fires while Doze/standby is active.
- Earlier escalation stages can use `setExactAndAllowWhileIdle()` for lower
  battery impact.
- A foreground **`AlarmService`** owns the active alarm sound while the user is
  interacting with the full-screen alarm activity.

### 5.4 Boot behavior

- `BOOT_COMPLETED` receiver reschedules all future reminders.
- Any reminder whose scheduled time passed while the phone was off **fires
  immediately on boot** (so I don't miss things if my battery dies overnight).
- A reminder that fires on boot restarts at the *first* stage of its chain, not
  whatever stage it would have reached — to avoid jolting the user with a
  sudden alarm.

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

- **User-provided bot.** The user creates their own Telegram bot via
  @BotFather, then pastes the bot token + chat ID into the app's Settings.
  No shared backend, no third party between the user and Telegram.
- The app sends messages directly to the Telegram Bot API from the device.
- A "Send test message" button in Settings confirms the configuration works.
- Telegram is optional. If unconfigured or the Telegram stage isn't in the
  chain, the chain simply advances past it.
- **Silent-stage Telegram mirror (per-group opt-in).** A group can opt in to
  also send a Telegram message at the silent stage. When enabled, that message
  uses `disable_notification: true` so it appears in the chat without pinging
  the user — useful for seeing the heads-up on a laptop without an audible
  alert. Off by default.

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
- Spoken Text-to-Speech reminders — alarm is sound-only in v1
- Voice input for adding reminders
- Onboarding tutorial
- Backup formats beyond Google Drive
- Localization beyond English
- Repeating the same stage twice in a chain (each stage at most once in v1)

---

## 12. Decisions log

Resolved during planning — captured here as the source of truth:

- **App name:** *Nock*.
- **Default stage chain:** Silent → Normal → Telegram → Loud alarm.
- **Default timing:** Silent at −10 min (pre-alarm heads-up), Normal at 0,
  Telegram at +5 min, Loud alarm at +10 min (then repeats every 10 min).
- **Snooze model:** advances to next stage; at the final stage, snoozes for
  10 min (global default, per-group overridable).
- **Alarm sound:** device's system default alarm sound, user-pickable in
  Settings via the system ringtone picker.
- **TTS:** not in v1. Alarm is sound-only.
- **Silent-stage Telegram mirror:** per-group opt-in, off by default.
- **Stage selection:** any of the 4 stages can be omitted from the chain;
  no stage may appear twice.

---

## 13. Build milestones

A rough sequence — each milestone should leave the app installable and
exercisable end-to-end on the next slice of functionality.

1. **M1 — Skeleton.** Empty Compose app, Hilt, Room schema, Today screen
   listing hard-coded reminders.
2. **M2 — One-shot reminders firing.** Add reminder via form, AlarmManager
   schedules it, single silent notification appears at the right time. Done
   button removes it.
3. **M3 — Full escalation chain.** All three local stages (silent, normal,
   loud alarm) driven by per-reminder offsets. Snooze advances to next stage.
   Last-stage repeat loop. Done stops the chain at any stage.
4. **M4 — Recurring + interval schedules.** Daily/weekly/monthly recurrence and
   interval-from-last-completion. Boot receiver reschedules everything.
5. **M5 — Groups + per-group escalation override + pause.**
6. **M6 — Globally reorderable stage chain** with per-group override; Settings
   UI to omit and reorder stages.
7. **M7 — Telegram integration** (user bot token + per-group silent-mirror
   opt-in).
8. **M8 — Natural-language quick-add bar.**
9. **M9 — Google Drive App Folder sync.**
10. **M10 — Polish, Material You theming, first signed release on GitHub.**

Stretch (post-v1): home-screen widget, history view, TTS spoken reminders,
additional push channels.
