package app.nock.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import app.nock.android.R

// Nock palette — ink-dark squircle, apricot foreground.
private val InkDark = Color(0xFF1B1622)

class VoiceAlarmWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val state = VoiceWidgetState.read(currentState<Preferences>())
                WidgetContent(context, state)
            }
        }
    }

    @Composable
    private fun WidgetContent(context: Context, state: VoiceWidgetState) {
        // Equalizer · 2 bars: dots when idle, bars while active (the design's
        // off/on poses). The transition morph from the prototype isn't viable
        // in a RemoteViews widget — we land on the two key frames instead.
        val iconRes = if (state.isActive) R.drawable.ic_widget_eq_bars
        else R.drawable.ic_widget_eq_dots
        val contentDescription = context.getString(
            if (state.isActive) R.string.widget_voice_recording_label
            else R.string.widget_voice_alarm_label
        )

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(InkDark)
                .cornerRadius(24.dp)
                .clickable(actionSendBroadcast<VoiceWidgetToggleReceiver>()),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = contentDescription,
                modifier = GlanceModifier.fillMaxSize()
            )
        }
    }
}

class VoiceAlarmWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VoiceAlarmWidget()
}
