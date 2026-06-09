package app.nock.android.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * Tap-to-toggle state of the voice widget.
 *
 *  * [Idle]      — nothing happening; tap starts recording.
 *  * [Starting]  — tap received, foreground service spinning up the recognizer.
 *  * [Recording] — recognizer is live; tap stops and finalizes.
 *  * [Stopping]  — stop received; the widget already renders its non-recording
 *                  pose while the transcript is handed to a background worker.
 *                  Parsing happens off this state machine, so this is brief —
 *                  the widget returns to [Idle] (and is fully tappable) at once.
 *
 * The service is the single source of truth — every transition flows through
 * [write], which persists into the Glance [PreferencesGlanceStateDefinition]
 * and triggers [VoiceAlarmWidget.updateAll] so every widget instance redraws.
 *
 * Persistence is intentional: if the process is killed mid-recording, the
 * widget would otherwise show a stale "recording" badge with no service to
 * stop it. The tap receiver resets stale state by always re-asking the
 * service via a fresh ACTION_TOGGLE intent — the service constructor starts
 * from `isRecording = false`, so a tap on a stale-recording widget simply
 * begins a new session.
 */
enum class VoiceWidgetState {
    Idle, Starting, Recording, Stopping;

    // Only the live-capture phases read as "recording" in the UI. [Stopping] is
    // deliberately excluded: once the user taps to stop, the widget flips back
    // to its non-recording pose immediately, even though the transcript is still
    // being parsed in the background. The state is kept distinct from [Idle]
    // (rather than just writing Idle) so a process killed mid-finalize still
    // restores to a sane, non-recording widget via persistence.
    val isActive: Boolean get() = this == Starting || this == Recording

    companion object {
        private val KEY = stringPreferencesKey("voice_widget_state")

        fun read(prefs: Preferences): VoiceWidgetState =
            prefs[KEY]?.let { name -> entries.firstOrNull { it.name == name } } ?: Idle

        suspend fun write(context: Context, state: VoiceWidgetState) {
            val manager = GlanceAppWidgetManager(context)
            val ids = manager.getGlanceIds(VoiceAlarmWidget::class.java)
            for (id in ids) {
                writeFor(context, id, state)
            }
            VoiceAlarmWidget().updateAll(context)
        }

        private suspend fun writeFor(context: Context, id: GlanceId, state: VoiceWidgetState) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply { this[KEY] = state.name }
            }
        }
    }
}
