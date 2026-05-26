package app.nock.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.ui.graphics.vector.ImageVector
import app.nock.android.domain.model.StageType

fun stageIcon(type: StageType): ImageVector = when (type) {
    StageType.SILENT -> Icons.Outlined.NotificationsOff
    StageType.TELEGRAM -> Icons.Outlined.Send
    StageType.ALARM_VIBRATE -> Icons.Outlined.Vibration
    StageType.ALARM -> Icons.Outlined.Alarm
}
