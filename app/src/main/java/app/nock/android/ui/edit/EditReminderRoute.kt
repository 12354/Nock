@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nock.android.ui.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.text.input.KeyboardType
import app.nock.android.R
import app.nock.android.domain.model.VibrationPattern
import app.nock.android.domain.model.VibrationPulse
import app.nock.android.ui.components.NockDatePickerDialog
import app.nock.android.ui.components.NockTimePickerDialog
import app.nock.android.ui.components.stageIcon
import app.nock.android.ui.components.stageTypeLabel
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditReminderRoute(
    reminderId: Long,
    onDone: () -> Unit,
    onDeleted: (app.nock.android.domain.model.Reminder) -> Unit = {},
    onImportFromCalendar: () -> Unit = {},
    onManageRooms: () -> Unit = {},
    vm: EditReminderViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(reminderId) { vm.load(reminderId) }
    val scope = rememberCoroutineScope()

    // The editor saves on exit (close button and system back alike), matching
    // the group editor — backing out must not silently discard edits. A brand-new
    // untouched reminder is skipped inside saveOnExit.
    val saveAndClose: () -> Unit = {
        scope.launch {
            vm.saveOnExit()
            onDone()
        }
    }
    androidx.activity.compose.BackHandler(onBack = saveAndClose)

    // Coming back from the rooms screen must show rooms created there.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refreshRooms()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(if (reminderId == 0L) R.string.edit_title_new else R.string.edit_title_edit)) },
                navigationIcon = {
                    IconButton(onClick = saveAndClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (reminderId == 0L) {
                        IconButton(onClick = onImportFromCalendar) {
                            Icon(
                                Icons.Outlined.CalendarMonth,
                                contentDescription = stringResource(R.string.edit_import_from_calendar)
                            )
                        }
                    }
                    if (reminderId != 0L) {
                        IconButton(onClick = {
                            scope.launch {
                                val deleted = vm.delete()
                                onDone()
                                if (deleted != null) onDeleted(deleted)
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    TextButton(onClick = {
                        scope.launch { if (vm.save()) onDone() }
                    }) { Text(stringResource(R.string.save)) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuickAddCard(state, vm::updateNl)

            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

            OutlinedTextField(
                value = state.name,
                onValueChange = vm::updateName,
                label = { Text(stringResource(R.string.edit_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            // Location and buffer are editable only for reminders imported from the
            // calendar; location drives the traffic-aware leave-by (changing it
            // re-routes), buffer sets this reminder's own heads-up lead.
            if (state.isCalendarReminder) {
                OutlinedTextField(
                    value = state.location,
                    onValueChange = vm::updateLocation,
                    label = { Text(stringResource(R.string.edit_location_label)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Place, contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                TripBufferField(state.bufferMin, vm::updateBufferMin)
            }

            GroupSelector(
                groups = state.groups,
                selectedId = state.groupId,
                onSelect = vm::updateGroup
            )

            ScheduleKindSelector(state.scheduleType, vm::updateKind)

            when (state.scheduleType) {
                ScheduleKind.ONESHOT -> OneShotEditor(state.oneShotMs, vm::updateOneShot)
                ScheduleKind.DAILY -> TimesListEditor(
                    stringResource(R.string.edit_times_label),
                    state.dailyTimesMinutes
                ) { vm.updateDailyTimes(it) }
                ScheduleKind.WEEKLY -> {
                    WeekdaySelector(state.weeklyDays) { vm.updateWeeklyDays(it) }
                    Spacer(Modifier.height(4.dp))
                    TimesListEditor(stringResource(R.string.edit_times_label), state.weeklyTimesMinutes) { vm.updateWeeklyTimes(it) }
                }
                ScheduleKind.MONTHLY -> MonthlyEditor(
                    day = state.monthlyDay,
                    timeMin = state.monthlyTimeMinutes,
                    onDay = vm::updateMonthlyDay,
                    onTime = vm::updateMonthlyTime
                )
                ScheduleKind.INTERVAL -> IntervalEditor(
                    hours = state.intervalHours,
                    startMs = state.intervalStartMs,
                    onHoursChange = vm::updateIntervalHours,
                    onStartChange = vm::updateIntervalStart
                )
                ScheduleKind.ON_UNLOCK -> OnUnlockEditor()
                ScheduleKind.ROOM -> RoomScheduleEditor(
                    rooms = state.rooms,
                    selectedRoomId = state.roomId,
                    afterMinutes = state.roomAfterMinutes,
                    fallbackMin = state.roomFallbackMin,
                    graceMin = state.roomGraceMin,
                    onRoom = vm::updateRoom,
                    onAfter = vm::updateRoomAfter,
                    onFallback = vm::updateRoomFallback,
                    onGrace = vm::updateRoomGrace,
                    onManageRooms = onManageRooms
                )
            }

            VibrationReminderCard(
                enabled = state.simpleVibration,
                pattern = state.vibrationPattern,
                onToggle = vm::updateSimpleVibration,
                onAdd = vm::addVibrationPulse,
                onRemove = vm::removeVibrationPulseAt,
                onPreview = vm::previewVibration,
            )

            // A regular reminder has no escalation chain, so the group-chain summary
            // is meaningless for it — hide it and let the vibration card stand alone.
            if (!state.simpleVibration) {
                EscalationSummary(state)
            }
        }
    }
}

/**
 * The "regular reminder" option: a single gentle vibration nudge, no escalation.
 * A per-reminder toggle; when on, the user arranges the buzz from short and long
 * pulses and can feel it with Test. Distinct from the group's escalation chain.
 */
@Composable
private fun VibrationReminderCard(
    enabled: Boolean,
    pattern: List<VibrationPulse>,
    onToggle: (Boolean) -> Unit,
    onAdd: (VibrationPulse) -> Unit,
    onRemove: (Int) -> Unit,
    onPreview: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Vibration,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.edit_regular_vibration_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.edit_regular_vibration_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }

            AnimatedVisibility(visible = enabled) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.edit_regular_vibration_pattern_label)
                            .uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pattern.forEachIndexed { idx, pulse ->
                            PulseChip(pulse = pulse, onRemove = { onRemove(idx) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { onAdd(VibrationPulse.SHORT) },
                            label = { Text(stringResource(R.string.edit_regular_vibration_add_short)) },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) }
                        )
                        AssistChip(
                            onClick = { onAdd(VibrationPulse.LONG) },
                            label = { Text(stringResource(R.string.edit_regular_vibration_add_long)) },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) }
                        )
                        AssistChip(
                            onClick = onPreview,
                            label = { Text(stringResource(R.string.edit_regular_vibration_test)) },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) }
                        )
                    }
                }
            }
        }
    }
}

/** A single pulse in the pattern editor: a short dot or a long bar, tap-to-remove. */
@Composable
private fun PulseChip(pulse: VibrationPulse, onRemove: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val isLong = pulse == VibrationPulse.LONG
    Surface(
        onClick = onRemove,
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.18f),
        contentColor = accent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(if (isLong) 28.dp else 8.dp)
                    .clip(if (isLong) RoundedCornerShape(4.dp) else CircleShape)
                    .background(accent)
            )
            Text(
                stringResource(
                    if (isLong) R.string.edit_regular_vibration_long
                    else R.string.edit_regular_vibration_short
                ),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.remove),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * Per-reminder heads-up buffer for a calendar-imported reminder: how long before
 * departure the first nudge fires, so the reminder starts at
 * appointment − travel − buffer. Snaps to whole 5-minute steps.
 */
@Composable
private fun TripBufferField(bufferMin: Int, onChange: (Int) -> Unit) {
    MinutesSlider(
        icon = Icons.Filled.Timelapse,
        label = stringResource(R.string.edit_trip_buffer_label),
        valueText = stringResource(R.string.edit_trip_buffer_value, bufferMin),
        helpText = stringResource(R.string.edit_trip_buffer_help),
        value = bufferMin,
        min = EditReminderViewModel.MIN_TRIP_BUFFER_MIN,
        max = EditReminderViewModel.MAX_TRIP_BUFFER_MIN,
        onChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}

/**
 * A labelled minutes picker rendered as a Material slider that snaps to whole
 * [step]-minute increments. Shared by the calendar trip buffer and the
 * room-based fallback so both read and behave identically.
 */
@Composable
private fun MinutesSlider(
    icon: ImageVector,
    label: String,
    valueText: String,
    helpText: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 5,
) {
    val steps = ((max - min) / step) - 1
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(min, max).toFloat(),
            onValueChange = { onChange((it / step).toInt() * step) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = steps,
        )
        Text(
            helpText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun QuickAddCard(state: EditState, onChange: (String) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val selectedGroupName = state.groups.firstOrNull { it.id == state.groupId }?.name
    val selectedGroupColor = state.groups.firstOrNull { it.id == state.groupId }?.color?.let { Color(it) }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.edit_quick_add_hint),
                style = MaterialTheme.typography.labelMedium,
                color = onSurfaceVar
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = primary.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(16.dp)
                ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = state.nlInput,
                    onValueChange = onChange,
                    textStyle = TextStyle(
                        color = onSurface,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(primary),
                    visualTransformation = QuickAddTransformation(
                        primaryHighlight = primary.copy(alpha = 0.25f),
                        groupName = selectedGroupName,
                        groupHighlight = selectedGroupColor?.copy(alpha = 0.25f)
                    ),
                    decorationBox = { inner ->
                        if (state.nlInput.isEmpty()) {
                            Text(
                                stringResource(R.string.edit_quick_add_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = onSurfaceVar
                            )
                        }
                        inner()
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.nlThinking) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.edit_ai_thinking),
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurfaceVar
                        )
                    }
                } else if (state.nlError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.edit_ai_error_prefix, state.nlError!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (state.nlInput.isNotBlank()) {
                    val summary = parsedSummary(state)
                    if (summary != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF80CBC4),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.edit_quick_add_parsed, summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVar
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Build the "parsed" one-liner from current state if anything has been resolved. */
@Composable
private fun parsedSummary(state: EditState): String? {
    if (state.groups.isEmpty()) return null
    val groupName = state.groups.firstOrNull { it.id == state.groupId }?.name ?: return null
    val timeJoin = stringResource(R.string.parsed_summary_time_join)
    val schedulePart: String = when (state.scheduleType) {
        ScheduleKind.DAILY -> {
            val times = state.dailyTimesMinutes.sorted().joinToString(timeJoin) {
                "%02d:%02d".format(it / 60, it % 60)
            }
            stringResource(R.string.parsed_summary_daily, times)
        }
        ScheduleKind.WEEKLY -> {
            val locale = Locale.getDefault()
            val days = state.weeklyDays.sorted().joinToString(", ") {
                it.getDisplayName(JTextStyle.SHORT, locale)
            }
            val times = state.weeklyTimesMinutes.sorted().joinToString(timeJoin) {
                "%02d:%02d".format(it / 60, it % 60)
            }
            stringResource(R.string.parsed_summary_weekly, days, times)
        }
        ScheduleKind.MONTHLY -> stringResource(
            R.string.parsed_summary_monthly,
            state.monthlyDay,
            "%02d:%02d".format(state.monthlyTimeMinutes / 60, state.monthlyTimeMinutes % 60)
        )
        ScheduleKind.INTERVAL -> {
            val base = stringResource(R.string.parsed_summary_interval, state.intervalHours)
            val startMs = state.intervalStartMs
            if (startMs != null) {
                val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), ZoneId.systemDefault())
                stringResource(
                    R.string.parsed_summary_interval_with_start,
                    state.intervalHours,
                    formatDate(dt.toLocalDate()),
                    "%02d:%02d".format(dt.hour, dt.minute)
                )
            } else base
        }
        ScheduleKind.ONESHOT -> {
            val dt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(state.oneShotMs),
                ZoneId.systemDefault()
            )
            stringResource(
                R.string.parsed_summary_once,
                formatDate(dt.toLocalDate()),
                "%02d:%02d".format(dt.hour, dt.minute)
            )
        }
        ScheduleKind.ON_UNLOCK -> stringResource(R.string.parsed_summary_on_unlock)
        ScheduleKind.ROOM -> {
            val roomName = state.rooms.firstOrNull { it.id == state.roomId }?.name
                ?: stringResource(R.string.parsed_summary_room_unset)
            stringResource(
                R.string.parsed_summary_room,
                roomName,
                "%02d:%02d".format(state.roomAfterMinutes / 60, state.roomAfterMinutes % 60)
            )
        }
    }
    return stringResource(R.string.parsed_summary_wrapped, schedulePart, groupName)
}

private val TIME_PATTERNS = listOf(
    Regex("""\b\d{1,2}:\d{2}\b""", RegexOption.IGNORE_CASE),
    Regex("""\b\d{1,2}\s*(?:am|pm)\b""", RegexOption.IGNORE_CASE),
    Regex("""\bevery\s+\d+\s*(?:m|min|mins|h|hr|hrs|hour|hours|minute|minutes)\b""", RegexOption.IGNORE_CASE),
    Regex("""\bevery\s+(?:day|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:daily|weekly|monthly|tomorrow|today)\b""", RegexOption.IGNORE_CASE),
)

private class QuickAddTransformation(
    private val primaryHighlight: Color,
    private val groupName: String?,
    private val groupHighlight: Color?,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val ranges = mutableListOf<Triple<Int, Int, Color>>()
        TIME_PATTERNS.forEach { rx ->
            rx.findAll(raw).forEach { m ->
                ranges.add(Triple(m.range.first, m.range.last + 1, primaryHighlight))
            }
        }
        if (groupName != null && groupHighlight != null) {
            val rx = Regex("\\b" + Regex.escape(groupName) + "\\b", RegexOption.IGNORE_CASE)
            rx.findAll(raw).forEach { m ->
                ranges.add(Triple(m.range.first, m.range.last + 1, groupHighlight))
            }
        }
        val annotated = buildAnnotatedString {
            append(raw)
            ranges.forEach { (s, e, c) ->
                addStyle(SpanStyle(background = c), s, e)
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

@Composable
private fun GroupSelector(
    groups: List<app.nock.android.domain.model.Group>,
    selectedId: Long,
    onSelect: (Long) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.edit_group_label).uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            groups.forEach { g ->
                val selected = g.id == selectedId
                val color = Color(g.color)
                Surface(
                    onClick = { onSelect(g.id) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) color.copy(alpha = 0.22f) else Color.Transparent,
                    border = if (selected) null else androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline
                    ),
                    contentColor = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = app.nock.android.ui.components.groupIconFor(g.icon),
                            contentDescription = null,
                            tint = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            g.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleKindSelector(current: ScheduleKind, onChange: (ScheduleKind) -> Unit) {
    val items = listOf(
        ScheduleKind.ONESHOT to R.string.schedule_kind_once,
        ScheduleKind.DAILY to R.string.schedule_kind_daily,
        ScheduleKind.WEEKLY to R.string.schedule_kind_weekly,
        ScheduleKind.MONTHLY to R.string.schedule_kind_monthly,
        ScheduleKind.INTERVAL to R.string.schedule_kind_interval,
        ScheduleKind.ON_UNLOCK to R.string.schedule_kind_on_unlock,
        ScheduleKind.ROOM to R.string.schedule_kind_room,
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.edit_schedule_label).uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEach { (k, labelRes) ->
                FilterChip(
                    selected = current == k,
                    onClick = { onChange(k) },
                    label = { Text(stringResource(labelRes)) }
                )
            }
        }
    }
}

/** Localized medium date for picker buttons and summaries (was a raw ISO string). */
private fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

@Composable
private fun OneShotEditor(ms: Long, onChange: (Long) -> Unit) {
    val dt = remember(ms) { LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.edit_date_time_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showDate = true }) { Text(formatDate(dt.toLocalDate())) }
            OutlinedButton(onClick = { showTime = true }) { Text("%02d:%02d".format(dt.hour, dt.minute)) }
        }
    }
    if (showDate) {
        NockDatePickerDialog(
            initialDate = dt.toLocalDate(),
            onDismiss = { showDate = false },
            onConfirm = { date ->
                val newDt = LocalDateTime.of(date, dt.toLocalTime())
                onChange(newDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }
        )
    }
    if (showTime) {
        NockTimePickerDialog(
            initialMinutesOfDay = dt.hour * 60 + dt.minute,
            onDismiss = { showTime = false },
            onConfirm = { minutes ->
                val newDt = dt.withHour(minutes / 60).withMinute(minutes % 60).withSecond(0).withNano(0)
                onChange(newDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }
        )
    }
}

@Composable
private fun TimesListEditor(label: String, times: List<Int>, onChange: (List<Int>) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    var editIdx by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            times.forEachIndexed { idx, m ->
                FilterChip(
                    selected = true,
                    onClick = { editIdx = idx },
                    label = { Text("%02d:%02d".format(m / 60, m % 60)) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.remove),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable {
                                    onChange(times.toMutableList().also { it.removeAt(idx) })
                                }
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = primary.copy(alpha = 0.22f),
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                    )
                )
            }
            AssistChip(
                onClick = { adding = true },
                label = { Text(stringResource(R.string.edit_add_time)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) }
            )
        }
    }
    editIdx?.let { idx ->
        NockTimePickerDialog(
            initialMinutesOfDay = times.getOrElse(idx) { 9 * 60 },
            onDismiss = { editIdx = null },
            onConfirm = { m ->
                onChange(times.toMutableList().also { it[idx] = m }.sorted())
            }
        )
    }
    if (adding) {
        NockTimePickerDialog(
            initialMinutesOfDay = 9 * 60,
            onDismiss = { adding = false },
            onConfirm = { m -> onChange((times + m).sorted()) }
        )
    }
}

@Composable
private fun WeekdaySelector(selected: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    val locale = Locale.getDefault()
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.edit_days_label).uppercase(locale),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DayOfWeek.values().forEach { d ->
                FilterChip(
                    selected = d in selected,
                    onClick = {
                        val new = selected.toMutableSet().also { if (d in it) it.remove(d) else it.add(d) }
                        onChange(new)
                    },
                    label = { Text(d.getDisplayName(JTextStyle.SHORT, locale)) }
                )
            }
        }
    }
}

@Composable
private fun MonthlyEditor(day: Int, timeMin: Int, onDay: (Int) -> Unit, onTime: (Int) -> Unit) {
    var showTime by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.edit_monthly_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = day.toString(),
                onValueChange = { it.toIntOrNull()?.let { v -> if (v in 1..31) onDay(v) } },
                label = { Text(stringResource(R.string.edit_day_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp)
            )
            OutlinedButton(onClick = { showTime = true }) {
                Text(stringResource(R.string.edit_at_time, "%02d:%02d".format(timeMin / 60, timeMin % 60)))
            }
        }
    }
    if (showTime) {
        NockTimePickerDialog(
            initialMinutesOfDay = timeMin,
            onDismiss = { showTime = false },
            onConfirm = onTime
        )
    }
}

@Composable
private fun IntervalEditor(
    hours: Int,
    startMs: Long?,
    onHoursChange: (Int) -> Unit,
    onStartChange: (Long?) -> Unit
) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.edit_interval_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = hours.toString(),
            onValueChange = { it.toIntOrNull()?.let { v -> if (v in 1..168) onHoursChange(v) } },
            label = { Text(stringResource(R.string.edit_hours_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(160.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable {
                if (startMs != null) onStartChange(null)
                else onStartChange(System.currentTimeMillis() + 3_600_000L)
            }
        ) {
            Checkbox(
                checked = startMs != null,
                onCheckedChange = { enabled ->
                    if (enabled) onStartChange(System.currentTimeMillis() + 3_600_000L)
                    else onStartChange(null)
                }
            )
            Text(stringResource(R.string.edit_interval_start_label), style = MaterialTheme.typography.bodyMedium)
        }
        if (startMs != null) {
            val dt = remember(startMs) {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), ZoneId.systemDefault())
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDate = true }) { Text(formatDate(dt.toLocalDate())) }
                OutlinedButton(onClick = { showTime = true }) { Text("%02d:%02d".format(dt.hour, dt.minute)) }
            }
            if (showDate) {
                NockDatePickerDialog(
                    initialDate = dt.toLocalDate(),
                    onDismiss = { showDate = false },
                    onConfirm = { date ->
                        val newDt = LocalDateTime.of(date, dt.toLocalTime())
                        onStartChange(newDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    }
                )
            }
            if (showTime) {
                NockTimePickerDialog(
                    initialMinutesOfDay = dt.hour * 60 + dt.minute,
                    onDismiss = { showTime = false },
                    onConfirm = { minutes ->
                        val newDt = dt.withHour(minutes / 60).withMinute(minutes % 60).withSecond(0).withNano(0)
                        onStartChange(newDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    }
                )
            }
        }
    }
}

@Composable
private fun OnUnlockEditor() {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.edit_on_unlock_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.edit_on_unlock_description),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun RoomScheduleEditor(
    rooms: List<app.nock.android.data.entity.WifiRoomEntity>,
    selectedRoomId: Long?,
    afterMinutes: Int,
    fallbackMin: Int,
    graceMin: Int,
    onRoom: (Long) -> Unit,
    onAfter: (Int) -> Unit,
    onFallback: (Int) -> Unit,
    onGrace: (Int) -> Unit,
    onManageRooms: () -> Unit,
) {
    var showAfterTime by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text(
                stringResource(R.string.edit_room_label).uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            if (rooms.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.edit_room_none),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rooms.forEach { room ->
                    FilterChip(
                        selected = room.id == selectedRoomId,
                        onClick = { onRoom(room.id) },
                        label = { Text(room.name) }
                    )
                }
                AssistChip(
                    onClick = onManageRooms,
                    label = { Text(stringResource(R.string.edit_room_manage)) },
                    leadingIcon = {
                        Icon(Icons.Filled.MeetingRoom, contentDescription = null)
                    }
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.edit_room_after_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { showAfterTime = true }) {
                Text("%02d:%02d".format(afterMinutes / 60, afterMinutes % 60))
            }
        }
        if (showAfterTime) {
            NockTimePickerDialog(
                initialMinutesOfDay = afterMinutes,
                onDismiss = { showAfterTime = false },
                onConfirm = onAfter
            )
        }

        MinutesSlider(
            icon = Icons.Filled.Timelapse,
            label = stringResource(R.string.edit_room_fallback_label),
            valueText = stringResource(R.string.edit_trip_buffer_value, fallbackMin),
            helpText = stringResource(R.string.edit_room_fallback_help, fallbackMin),
            value = fallbackMin,
            min = EditReminderViewModel.MIN_ROOM_FALLBACK_MIN,
            max = EditReminderViewModel.MAX_ROOM_FALLBACK_MIN,
            onChange = onFallback,
            modifier = Modifier.fillMaxWidth(),
        )

        MinutesSlider(
            icon = Icons.Filled.Timelapse,
            label = stringResource(R.string.edit_room_grace_label),
            valueText = stringResource(R.string.edit_trip_buffer_value, graceMin),
            helpText = stringResource(R.string.edit_room_grace_help),
            value = graceMin,
            min = EditReminderViewModel.MIN_ROOM_GRACE_MIN,
            max = EditReminderViewModel.MAX_ROOM_GRACE_MIN,
            onChange = onGrace,
            modifier = Modifier.fillMaxWidth(),
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.edit_room_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun EscalationSummary(state: EditState) {
    val g = state.groups.firstOrNull { it.id == state.groupId } ?: return
    val chain = g.overrideChain ?: app.nock.android.domain.model.DefaultChain.CHAIN
    val accent = Color(g.color)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.edit_escalation_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            val template = stringResource(R.string.edit_using_group_chain)
            val parts = template.split("%1\$s", limit = 2)
            Text(
                buildAnnotatedString {
                    append(parts[0])
                    pushStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface))
                    append(g.name)
                    pop()
                    if (parts.size > 1) append(parts[1])
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                chain.stages.forEachIndexed { idx, stage ->
                    Surface(
                        color = accent.copy(alpha = 0.18f),
                        shape = CircleShape,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = stageIcon(stage.type),
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                stringResource(stageTypeLabel(stage.type)),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (idx < chain.stages.lastIndex) {
                        Text(
                            "→",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }
    }
}
