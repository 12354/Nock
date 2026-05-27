package app.nock.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
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
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import app.nock.android.R

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
        val background = when (state) {
            VoiceWidgetState.Idle -> GlanceTheme.colors.primaryContainer
            VoiceWidgetState.Recording -> GlanceTheme.colors.errorContainer
            VoiceWidgetState.Starting, VoiceWidgetState.Stopping -> GlanceTheme.colors.secondaryContainer
        }
        val iconTint = when (state) {
            VoiceWidgetState.Idle -> GlanceTheme.colors.onPrimaryContainer
            VoiceWidgetState.Recording -> GlanceTheme.colors.onErrorContainer
            VoiceWidgetState.Starting, VoiceWidgetState.Stopping -> GlanceTheme.colors.onSecondaryContainer
        }
        val contentDescription = context.getString(
            if (state.isActive) R.string.widget_voice_recording_label
            else R.string.widget_voice_alarm_label
        )

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(4.dp)
                .background(background)
                .cornerRadius(20.dp)
                .clickable(actionSendBroadcast<VoiceWidgetToggleReceiver>()),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_mic),
                contentDescription = contentDescription,
                colorFilter = ColorFilter.tint(iconTint),
                modifier = GlanceModifier.size(24.dp)
            )
        }
    }
}

class VoiceAlarmWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VoiceAlarmWidget()
}
