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
 *  * [Stopping]  — stop received; finalizing transcript (parsing + toast) in the background.
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

    val isActive: Boolean get() = this == Starting || this == Recording || this == Stopping

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
