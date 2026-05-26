package app.nock.android.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.ui.graphics.vector.ImageVector
import app.nock.android.R
import app.nock.android.domain.model.StageType

fun stageIcon(type: StageType): ImageVector = when (type) {
    StageType.SILENT -> Icons.Outlined.NotificationsOff
    StageType.TELEGRAM -> Icons.Outlined.Send
    StageType.ALARM_VIBRATE -> Icons.Outlined.Vibration
    StageType.ALARM -> Icons.Outlined.Alarm
}

@StringRes
fun stageTypeLabel(type: StageType): Int = when (type) {
    StageType.SILENT -> R.string.stage_type_silent
    StageType.ALARM_VIBRATE -> R.string.stage_type_alarm_vibrate
    StageType.ALARM -> R.string.stage_type_alarm
    StageType.TELEGRAM -> R.string.stage_type_telegram
}
