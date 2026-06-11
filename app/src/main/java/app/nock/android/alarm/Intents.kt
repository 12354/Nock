package app.nock.android.alarm

object IntentExtras {
    const val EXTRA_ESCALATION_ID = "escalation_id"
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_REMINDER_NAME = "reminder_name"
    const val EXTRA_GROUP_NAME = "group_name"
    const val EXTRA_ACTION = "action"
    const val EXTRA_VIBRATION_ONLY = "vibration_only"

    // Marks a scheduled alarm whose due stage is a loud (full-screen) one, so
    // EscalationReceiver can launch the takeover synchronously while it still
    // holds the alarm broadcast's background-activity-launch grant.
    const val EXTRA_IS_LOUD_STAGE = "is_loud_stage"

    const val ACTION_FIRE = "fire"
    const val ACTION_DONE = "done"
    const val ACTION_SNOOZE = "snooze"
    const val ACTION_STOP_ALARM = "stop_alarm"

    // Complete a reminder by id (not by live escalation) — the Done button on a
    // room reminder's window notification, which may be showing before any
    // escalation has started.
    const val ACTION_COMPLETE_REMINDER = "complete_reminder"
}
