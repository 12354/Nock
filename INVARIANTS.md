# Alarm-system invariants — Nock

This document states the **invariants** the escalation/alarm subsystem is built
to preserve: properties that must hold at all times, across every entry point
(scheduling, firing, snooze, done, boot, time changes, group/chain edits,
sync), regardless of how the OS interleaves delivery. Each invariant cites the
code that enforces it and, where one exists, the test that pins it.

The relevant code lives in:

- `domain/escalation/EscalationEngine.kt` — the state machine.
- `domain/model/Stage.kt` — `EscalationChain` and stage-index math.
- `domain/model/Schedule.kt` — next-fire computation per schedule type.
- `alarm/AlarmScheduler.kt`, `alarm/AlarmService.kt`,
  `alarm/EscalationReceiver.kt` — OS scheduling, sound/vibration, delivery.
- `data/entity/ActiveEscalationEntity.kt` — the durable in-flight row.

Terminology: a **reminder** is a user-defined task with a `Schedule`. An
**occurrence** is one scheduled firing of that reminder. An **escalation** (one
`active_escalations` row) is the in-flight state machine that walks one
occurrence through the **chain** of **stages** (SILENT → TELEGRAM →
ALARM_VIBRATE → ALARM, by default).

---

## 1. Chain structure invariants

These are enforced at construction in `EscalationChain.init` and the
JSON-decode path, so no malformed chain can ever reach the engine.

- **I-1.1 — Non-empty.** Every chain has at least one stage.
  (`require(stages.isNotEmpty())`, `Stage.kt:15`.)

- **I-1.2 — No duplicate stage types.** Each of the four stage types
  (`SILENT`, `TELEGRAM`, `ALARM_VIBRATE`, `ALARM`) appears **at most once** in a
  chain. (`require(... toSet().size == stages.size)`, `Stage.kt:16`; test
  `ChainTest.cannot_repeat_a_stage_type`.) A chain may omit any stage entirely
  (`ChainTest.supports_partial_chain`).

- **I-1.3 — Offsets are relative to the occurrence's scheduled time** and are
  an *absolute* model, not "delay since previous stage." The first stage's
  offset may be negative (pre-trigger heads-up), zero, or positive. This is what
  makes stage timing deterministic and order-independent to reason about
  (`plan.md` §3.4; `stageDueAt` / `firstPendingStage` arithmetic in `Stage.kt`).

- **I-1.4 — `lastIndex` is always a valid index** (`stages.size - 1 ≥ 0`,
  given I-1.1), and every index the engine derives is clamped into
  `0..lastIndex` before use (`coerceIn`/`coerceAtMost` at `EscalationEngine.kt:212,222,224,481`).
  No stage lookup can go out of bounds even with a corrupt or shortened chain.

- **I-1.5 — The effective chain is well-defined for every group.**
  `effectiveChain(group) = group.overrideChain ?: settings.getStageChain()`,
  and `getStageChain()` falls back to `DefaultChain.CHAIN` if the stored JSON is
  missing or corrupt (`NockRepository.kt:89`, `SettingsRepository.kt:34-37`).
  There is no group without a usable chain.

---

## 2. Single-escalation-per-reminder invariant

- **I-2.1 — At most one active escalation per reminder.** The
  `active_escalations` table has a **unique index on `reminderId`**
  (`ActiveEscalationEntity.kt:18`). `upsert` REPLACEs on that index, so a
  reminder can never have two concurrent chains. Every arming path that could
  create a second one first tears the existing one down (see I-3.1).

- **I-2.2 — Event-triggered reminders don't double-fire.**
  `fireUnlockReminders` skips any `OnUnlock` reminder that already has an active
  row, so a re-unlock during the escalation window does not spawn a second chain
  (`EscalationEngine.kt:470`; test
  `fireUnlock_skips_reminder_already_escalating`).

---

## 3. Teardown-before-rearm invariant (no orphaned alarms / no stale side effects)

This is the central correctness property of the engine: an `active_escalations`
row deletion does **not** by itself cancel the `AlarmManager` alarm keyed to it,
stop a live ring, or undo Telegram messages already sent. So every path that
removes or replaces an escalation must perform the full teardown explicitly.

- **I-3.1 — Re-arming a reminder fully tears down its prior escalation first.**
  `startEscalationAt` cancels the old alarm, stops the alarm service if that
  escalation is the one ringing, enqueues its sent-Telegram ids for deletion,
  deletes the row, and flushes deletions — *before* arming the new chain
  (`EscalationEngine.kt:63-71`). Without this, a moved reminder would keep
  firing on its old time (alarms are keyed by escalation id, not reminder id)
  and strand its Telegram messages. Tests:
  `rearming_cancels_and_replaces_the_previous_escalation`,
  `rearming_deletes_the_previous_chains_sent_telegrams`,
  `moved_reminder_reschedules_to_new_trigger_without_firing`.

- **I-3.2 — Cancelling tears down alarm + notification + ring + Telegram.**
  `cancelActive` cancels the scheduled alarm, cancels the notification, stops
  the alarm service **only if this escalation is the one ringing**, deletes the
  sent Telegrams, and deletes the row (`EscalationEngine.kt:417-434`). Tests:
  `cancelActive_deletes_sent_telegram_messages`,
  `cancelActive_stops_alarm_only_when_this_escalation_is_ringing`,
  `cancelActive_leaves_other_ringing_alarm_untouched`.

- **I-3.3 — Deleting a group tears down its reminders' escalations *before* the
  FK cascade.** `cancelActiveForGroup` must run before `repo.deleteGroup`,
  because once the `ON DELETE CASCADE` removes the reminder and
  `active_escalations` rows, the engine can no longer find them to cancel their
  alarms — leaving orphaned alarms that still ring for a reminder in no list
  (`EscalationEngine.kt:376-380`, comment block; tests
  `cancelActiveForGroup_*`).

- **I-3.4 — Orphaned OS alarms self-heal on delivery.** If an alarm is delivered
  for an escalation id with no backing row (e.g. orphaned before the
  cancel-on-delete fix), `onAlarmFired` cancels any stuck notification, stops
  the ring if it's this id, and **does not reschedule** — so the orphan lapses
  for good after one fire (`EscalationEngine.kt:146-160`; tests
  `fire_orphaned_alarm_self_heals_a_stuck_ring`,
  `fire_orphaned_alarm_clears_notification_but_spares_other_ring`,
  `fire_unknown_escalation_does_not_reschedule`).

- **I-3.5 — A fired alarm whose reminder was deleted out-of-band drops the
  orphan row** after cleaning up its Telegram messages, and does not reschedule
  (`EscalationEngine.kt:161-172`; test `fire_with_missing_reminder_deletes_the_row`).

---

## 4. "Never fire ahead of schedule" invariant

The user must never get a Telegram or loud alarm *earlier* than the chain
prescribes. Several guards protect this:

- **I-4.1 — Arming skips already-past pre-trigger stages.** When the trigger is
  still in the future, arming starts at `firstPendingStage` — the earliest stage
  whose absolute fire time hasn't passed — so a SILENT @ −10 min on a reminder
  only 2 min away does **not** fire (and mirror a Telegram) the instant it's
  saved (`EscalationEngine.kt:82-100`, `Stage.kt:48-51`; tests
  `future_trigger_with_short_lead_skips_past_silent_pre_stage`,
  `firstPendingStage_skips_past_pre_stage_when_due_soon`,
  `far_future_trigger_starts_at_silent`).

- **I-4.2 — Every armed fire is floored at `now + 1s`.** No stage is ever
  scheduled in the past; `firstFire = max(scheduledAt + offset, now + 1_000L)`
  and the same floor is applied to each subsequent non-last stage
  (`EscalationEngine.kt:102,263`; tests `first_fire_is_floored_at_now_plus_one_second`,
  `non_last_next_fire_is_floored_at_now_plus_one_second`).

- **I-4.3 — Early delivery is corrected, not acted on.** If an alarm is
  delivered more than `SANITY_TOLERANCE_MS` (1 s) before its stored
  `nextFireAtMs` — clock moved backwards, stale PendingIntent, etc. — the engine
  **re-arms for the real time and bails** rather than firing the stage
  (`EscalationEngine.kt:211-215`; tests `fire_too_early_rearms_and_does_not_escalate`,
  `fire_within_tolerance_still_escalates`).

- **I-4.4 — A moved-to-a-different-future-occurrence alarm re-arms instead of
  firing stale.** If the reminder's authoritative `nextFireAt` now points at a
  different still-upcoming occurrence than the row's `startedAtMs` (beyond
  tolerance), the queued alarm is for the wrong date/time; the engine re-arms
  from the current trigger rather than ringing on the old schedule and re-sending
  Telegram (`EscalationEngine.kt:191-200`). The guard is deliberately scoped to
  the future-trigger case so a normal in-progress chain and an overdue boot
  replay are left alone.

---

## 5. "Catch up correctly when late" invariant

The dual of §4: when the OS delivers an alarm *late* (Doze, system busy, boot
replay), the user should see the stage they *should* be at now, not a stale
earlier one.

- **I-5.1 — Fire the latest stage due at `now`, never behind the cursor.** The
  index fired is `max(storedCursor, stageDueAt(startedAt, now))`, capped at
  `lastIndex` (`EscalationEngine.kt:222-224`; tests
  `late_fire_jumps_to_the_currently_due_stage`,
  `overdue_trigger_jumps_to_the_due_stage`). `stageDueAt` returns the latest
  stage whose offset ≤ elapsed, or 0 if none is due yet, and caps at the last
  stage for arbitrarily-late fires (`Stage.kt:30-37`; `ChainTest.stageDueAt_*`).

- **I-5.2 — Overdue reminders restart at the *first* stage on boot, not whatever
  stage they would have reached.** `startEscalationFromBoot` arms stage 0 with a
  synthetic `startedAt` shifted by the first offset so the chain rolls out at its
  normal spacing rather than blasting the loud stage immediately — avoiding a
  jolting alarm after the phone was off (`EscalationEngine.kt:117-138`, `plan.md`
  §5.4; tests `boot_arms_first_stage_at_now_plus_one_second`,
  `rescheduleAll_replays_overdue_time_based_reminder_via_boot_path`,
  `overdue_reminder_with_future_synthetic_start_still_fires`).

---

## 6. Chain-progression invariants

- **I-6.1 — The cursor advances monotonically through the chain** until the last
  stage. A non-last fire sets `nextStageIndex = idx + 1` and arms the next stage
  at `max(startedAt + nextOffset, now + 1s)` (`EscalationEngine.kt:260-271`; test
  `non_last_stage_advances_cursor_to_next_stage`).

- **I-6.2 — The last stage repeats forever on a fixed interval until Done.** At
  `idx == lastIndex`, the engine re-arms the *same* stage at
  `now + repeatIntervalMs` (default 10 min, per-group overridable). There is no
  "give up / log as missed" branch — the loop is unbounded by design
  (`EscalationEngine.kt:250-259`, `plan.md` §3.5; test
  `last_stage_reschedules_one_repeat_interval_out_and_appends_message`).

- **I-6.3 — There is always exactly one pending alarm for an active escalation.**
  Every fire path ends by scheduling exactly one next alarm (advance, repeat, or
  re-arm), and `nextFireAtMs`/`nextStageIndex` in the row always describe that
  pending alarm. `rescheduleAll` re-arms each active row from its stored
  `nextFireAtMs` and cursor-derived stage type (`EscalationEngine.kt:479-483`;
  test `rescheduleAll_rearms_existing_actives`).

---

## 7. Snooze invariants (no infinite-silence trap)

Snooze is designed so it can never let a task be buried indefinitely
(`plan.md` §3.3).

- **I-7.1 — Snooze on a non-last stage does *not* delay anything.** The next
  stage is already armed at its absolute time; snooze only suppresses the current
  stage's visuals/sound and leaves the cursor and `nextFireAtMs` untouched —
  i.e. snooze ≡ "skip ahead to whatever's coming next"
  (`EscalationEngine.kt:359-363`; tests `snooze_on_non_last_stage_does_not_reschedule`,
  `snooze_always_tears_down_current_alarm_visuals`).

- **I-7.2 — Snooze on the last stage pauses exactly one repeat interval from
  *now*.** It re-arms the same stage at `now + repeatIntervalMs`, using the
  current clock, not the baseline (`EscalationEngine.kt:350-358`; tests
  `snooze_on_last_stage_reschedules_one_repeat_interval_from_now`,
  `snooze_uses_current_clock_not_the_baseline`).

- **I-7.3 — Snooze never stops the chain.** Only Done does. Snooze always leaves
  an armed future alarm.

---

## 8. Done / acknowledgment invariants

- **I-8.1 — Only an explicit Done stops escalation.** Notification swipe and
  alarm-activity back/home do **not** count as Done; the alarm notification is
  `setOngoing` and undismissable while ringing (`plan.md` §3.2, `DECISIONS.md`;
  `AlarmService` foreground notification).

- **I-8.2 — Done tears down everything and removes the row.** Done cancels the
  alarm, cancels the notification, stops the alarm service, enqueues sent
  Telegrams for deletion, and deletes the active row
  (`EscalationEngine.kt:274-299`; test `done_tears_down_alarm_and_removes_row`).

- **I-8.3 — Done on a one-time schedule deletes the reminder; Done on a
  recurring/interval schedule arms the next occurrence.** `Schedule.isOneTime`
  (true for `OneShot` and `OnUnlock`) decides this; the next occurrence is
  computed from `nextFireFrom` and re-armed (`EscalationEngine.kt:300-324`; tests
  `done_on_one_time_reminder_deletes_the_reminder`,
  `done_on_recurring_reminder_rearms_next_occurrence`).

- **I-8.4 — Done during the pre-trigger window advances *past* the completed
  occurrence.** When Done is pressed before the trigger (e.g. acknowledging the
  −10 min SILENT heads-up), the next-occurrence search is anchored at
  `max(now, nextFireAt)` so it cannot return the *same* occurrence and re-arm a
  chain that would immediately jump to Telegram/alarm
  (`EscalationEngine.kt:308-322`; test
  `done_during_pretrigger_window_advances_past_the_completed_occurrence`).

- **I-8.5 — `lastCompletedAt` is set to the Done time** for non-one-time
  reminders, which is what interval-from-last schedules key their next fire off
  (`EscalationEngine.kt:320`, `Schedule.IntervalFromLast`).

---

## 9. Durability / ordering invariants (crash safety)

The receiver process is only kept alive by the alarm foreground service (plus a
short `goAsync` window), so the engine must commit durable state before any
slow, best-effort network work.

- **I-9.1 — Durable state is committed before the Telegram flush.** Done and
  Snooze drop/replace the row and re-arm the chain **before** calling
  `flushPendingTelegramDeletions` (a network call that can block for tens of
  seconds). If the process is killed mid-flush, the escalation state is already
  correct and only the deletion is deferred — never the re-arm
  (`EscalationEngine.kt:284-326,339-365`; tests
  `done_completes_and_rearms_before_attempting_telegram_cleanup`,
  `snooze_persists_rearm_before_attempting_telegram_cleanup`).

- **I-9.2 — Sent-Telegram ids are enqueued durably *before* the row holding
  them is dropped or cleared,** so a failed/interrupted delete can never lose
  them (`enqueueTelegramDeletions` precedes every row delete/clear;
  `EscalationEngine.kt:68,168,284,339,431`).

- **I-9.3 — Telegram deletion is exactly-once-eventually.** A queued id is
  removed only once `deleteMessage` reports the deletion resolved (succeeded or
  permanently un-deletable); transient failures stay queued and are retried on
  every later alarm tick, boot, and `rescheduleAll`
  (`EscalationEngine.kt:145,453-459,478`; tests
  `done_transient_failure_then_boot_retry_drains_queue`,
  `snooze_transient_delete_failure_keeps_id_queued_then_retry_drains_it`,
  `snooze_then_done_with_working_delete_removes_message_and_empties_queue`).

- **I-9.4 — A no-op stays a no-op.** `done`, `snooze`, `cancelActive`, and
  `scheduleNextFireForReminder` on an unknown escalation/reminder do nothing and
  schedule nothing (`EscalationEngine.kt:275,330,418,38`; tests
  `done_unknown_escalation_is_a_noop`, `snooze_unknown_escalation_is_a_noop`,
  `cancelActive_for_unknown_reminder_is_a_noop`,
  `scheduleNextFire_returns_null_for_unknown_reminder`).

---

## 10. Pause invariants

- **I-10.1 — A paused group never arms new escalations.** `startEscalationAt`,
  `startEscalationFromBoot`, `rearmGroup`, and the boot replay all bail when
  `group.isPaused(now)` (pausedUntil in the future)
  (`EscalationEngine.kt:75,119,395`; tests `paused_group_does_not_arm_anything`,
  `boot_does_nothing_for_paused_group`, `rearmGroup_paused_group_is_a_noop`).

- **I-10.2 — Pause only prevents *new* fires; already-firing escalations are not
  cancelled by pausing** — they must still be marked Done (`plan.md` §4.4). Pause
  owns arming, which is why `rearmGroup` skips paused groups rather than tearing
  their chains down without re-arming (`EscalationEngine.kt:393-394` comment).

- **I-10.3 — A just-expired pause arms normally.** `isPaused` is strict
  (`pausedUntilMs > now`), so the moment the window passes, arming resumes (test
  `pause_window_just_expired_arms_normally`).

---

## 11. Chain/timing-edit propagation invariants

- **I-11.1 — Editing a group's chain re-arms its reminders immediately,
  including in-flight escalations.** A chain edit only writes the group row;
  `rearmGroup` re-arms each reminder so the new chain takes effect now instead of
  at next fire/reboot — a user who changes the schedule does not expect the old
  one to keep firing (`EscalationEngine.kt:394-405`; tests
  `rearmGroup_rearms_in_flight_escalation_too`,
  `rearmGroup_replaces_not_started_escalation_with_current_chain`).

- **I-11.2 — Editing the global chain re-arms only groups *without* an
  override.** Groups carrying an `overrideChain` are unaffected by a global
  change and are left alone (`EscalationEngine.kt:411-415`,
  `rearmDefaultChainGroups`; test
  `rearmDefaultChainGroups_rearms_only_groups_without_override`).

- **I-11.3 — Each escalation walks the chain it snapshotted when armed.** The
  active row stores `chainSnapshotJson`; firing decodes it (falling back to the
  current settings chain if corrupt) so an in-flight chain is internally
  consistent even mid-edit (`EscalationEngine.kt:174,331,480`; test
  `snooze_falls_back_to_settings_chain_when_snapshot_is_corrupt`).

- **I-11.4 — `OnUnlock` reminders are never time-re-armed** by chain edits, boot,
  or time changes — they are event-triggered only
  (`EscalationEngine.kt:401,487`; test `rescheduleAll_never_fires_onUnlock_reminders`).

---

## 12. Schedule / next-fire invariants

These hold for `Schedule.nextFireFrom` (`Schedule.kt`).

- **I-12.1 — A returned next-fire is strictly in the future** for the recurring
  types (Daily/Weekly/Monthly use `candidate > now`), so the engine never arms a
  zero-or-negative-delay recurring occurrence (`Schedule.kt:99,52,...`).

- **I-12.2 — One-shot fires at most once.** `OneShot.nextFireFrom` returns null
  once `lastCompletedAt ≥ atEpochMs`; `OnUnlock` likewise once completed after
  its armed time (`Schedule.kt:22-25,79-82`).

- **I-12.3 — Interval-from-last never schedules in the past.** If
  `lastCompletedAt + interval < now`, it returns `now`; the first fire honors an
  explicit `startAtMs` (clamped to ≥ now) or falls back to `now + interval`
  (`Schedule.kt:60-70`).

- **I-12.4 — Monthly clamps to the last valid day of short months** (e.g. day 31
  in February maps to the 28th/29th) via
  `dayOfMonth.coerceAtMost(lengthOfMonth)` (`Schedule.kt:49`).

- **I-12.5 — `isOneTime` is total over schedule types.** It's an abstract member,
  so adding a schedule subtype forces an explicit one-time/recurring decision —
  preventing a new type from silently being treated as recurring in Done's
  delete-vs-rearm branch (`Schedule.kt:18`, comment).

---

## 13. OS-delivery / device invariants

- **I-13.1 — Loud stages use `setAlarmClock`; earlier stages use
  `setExactAndAllowWhileIdle`.** Only `setAlarmClock` reliably fires through
  Doze/standby, and it's what grants the background-activity-launch needed for
  the full-screen takeover; the gentler stages use the lower-battery exact alarm
  (`AlarmScheduler.kt:20-36`, `plan.md` §5.3).

- **I-13.2 — One PendingIntent per escalation id.** The PendingIntent request
  code is `escalationId.toInt()` and matching ignores extras, so `scheduleStage`
  (FLAG_UPDATE_CURRENT) replaces the same alarm and `cancel` targets exactly it
  — the `isLoud` extra is informational only (`AlarmScheduler.kt:38-57`, comment).

- **I-13.3 — The loud alarm always overrides DND** via `USAGE_ALARM` audio
  attributes + full-screen intent; this is intentional, since missing a critical
  reminder defeats the app's purpose (`AlarmService.kt:158-163`, `plan.md` §5.2).

- **I-13.4 — At most one alarm rings at a time, and `ringingEscalationId` tracks
  it.** Set on foreground start, cleared on stop/destroy and synchronously via
  `clearRingingState` so `MainActivity.onResume` can't bounce the user back into
  a stale alarm screen (`AlarmService.kt:76,205,244-257`). Teardown paths
  consult it so they silence *only* the escalation that is actually ringing
  (I-3.1, I-3.2).

- **I-13.5 — A stale alarm start self-cancels.** If Done/Snooze removed the
  escalation before the OS delivered the service start, `AlarmService` and
  `AlarmActivity` re-check the row and stop/finish rather than ring
  (`AlarmService.kt:90-103`). The full-screen takeover is launched
  synchronously from the receiver while the BAL grant is live, with the
  notification's full-screen intent as the lock-screen fallback
  (`EscalationReceiver.kt:35-37,61-80`).

- **I-13.6 — A fire never crashes the app.** The receiver swallows uncaught
  throws from `onAlarmFired` (the app-scope `SupervisorJob` has no exception
  handler), so one bad fire fails alone; the known recoverable case (denied FGS
  start) already degrades to a plain notification inside the engine
  (`EscalationReceiver.kt:43-58`).

---

## 14. Boot / time-change invariants

`rescheduleAll` runs on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`,
`TIMEZONE_CHANGED` (`DECISIONS.md`, `plan.md` §5.4).

- **I-14.1 — Existing actives are re-armed, not duplicated.** Every active row
  is re-armed from its stored `nextFireAtMs`/cursor; reminders that already have
  an active row are skipped in the second pass so no second chain is created
  (`EscalationEngine.kt:479-489`).

- **I-14.2 — Overdue time-based reminders fire (via the boot path, restarting at
  stage 0); not-yet-due ones are re-armed at their computed next fire.** Decided
  by `nextFireAt < now` (`EscalationEngine.kt:492-498`; tests
  `rescheduleAll_replays_overdue_time_based_reminder_via_boot_path`, and the I-5.2
  set).

- **I-14.3 — `OnUnlock` reminders are never fired by boot/time changes**
  (I-11.4; test `rescheduleAll_never_fires_onUnlock_reminders`).

- **I-14.4 — Boot/time-change is also a deletion-retry point** — `rescheduleAll`
  flushes the pending-Telegram-deletion queue first (`EscalationEngine.kt:477-478`,
  I-9.3).

---

## 15. Cross-cutting consistency invariant

- **I-15.1 — After any engine operation completes, the persisted state is
  self-consistent:** each `active_escalations` row has a valid in-range cursor,
  a `nextFireAtMs ≥ now`, exactly one matching pending `AlarmManager` alarm, and
  a chain snapshot it can be walked against; there is at most one row per
  reminder; and there are no scheduled alarms without a backing row that the
  engine knows how to retire (I-3.4). The fuzz suite exercises this across long
  random interleavings of fire/snooze/done/retime/reboot/spurious-delivery and
  asserts consistency after every step (`EscalationEngineFuzzTest.kt`:
  `assertConsistent`, `fuzz_alarm_lifecycle_across_many_random_runs`).

---

### How to use this document

When changing the engine, the bar is: **no edit may break an invariant above
without explicitly updating both this document and the test that pins it.** If
you add a new entry point (a new way to arm, fire, cancel, or edit), check it
against §3 (teardown-before-rearm), §4 (never fire early), §9 (durable-before-
network), and §10 (pause) — those four are where the subtle bugs live.
