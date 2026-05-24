@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nock.android.ui.edit

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.nock.android.R
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditReminderRoute(
    reminderId: Long,
    onDone: () -> Unit,
    vm: EditReminderViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(reminderId) { vm.load(reminderId) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (reminderId == 0L) R.string.edit_title_new else R.string.edit_title_edit)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = {
                        scope.launch { vm.save(); onDone() }
                    }) { Text(stringResource(R.string.save)) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.nlInput,
                onValueChange = vm::updateNl,
                label = { Text(stringResource(R.string.edit_quick_add_label)) },
                placeholder = { Text(stringResource(R.string.edit_quick_add_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                supportingText = {
                    if (state.nlPreview.isNotBlank()) Text(stringResource(R.string.edit_parsed_prefix, state.nlPreview))
                }
            )

            OutlinedTextField(
                value = state.name,
                onValueChange = vm::updateName,
                label = { Text(stringResource(R.string.edit_name_label)) },
                modifier = Modifier.fillMaxWidth()
            )

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
                ScheduleKind.INTERVAL -> IntervalEditor(state.intervalHours, vm::updateIntervalHours)
                ScheduleKind.ON_UNLOCK -> OnUnlockEditor()
            }
        }
    }
}

@Composable
private fun GroupSelector(groups: List<app.nock.android.domain.model.Group>, selectedId: Long, onSelect: (Long) -> Unit) {
    Column {
        Text(stringResource(R.string.edit_group_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow {
            groups.forEach { g ->
                val selected = g.id == selectedId
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(g.id) },
                    label = { Text(g.name) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(g.color))
                        )
                    },
                    modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)
                )
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
    )
    Column {
        Text(stringResource(R.string.edit_schedule_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow {
            items.forEach { (k, labelRes) ->
                FilterChip(
                    selected = current == k,
                    onClick = { onChange(k) },
                    label = { Text(stringResource(labelRes)) },
                    modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun OneShotEditor(ms: Long, onChange: (Long) -> Unit) {
    val ctx = LocalContext.current
    val dt = remember(ms) { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()) }
    Column {
        Text(stringResource(R.string.edit_date_time_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                DatePickerDialog(ctx, { _, y, m, d ->
                    val newDt = dt.withYear(y).withMonth(m + 1).withDayOfMonth(d)
                    onChange(newDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                }, dt.year, dt.monthValue - 1, dt.dayOfMonth).show()
            }) { Text("%04d-%02d-%02d".format(dt.year, dt.monthValue, dt.dayOfMonth)) }

            OutlinedButton(onClick = {
                TimePickerDialog(ctx, { _, h, mn ->
                    val newDt = dt.withHour(h).withMinute(mn).withSecond(0).withNano(0)
                    onChange(newDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                }, dt.hour, dt.minute, true).show()
            }) { Text("%02d:%02d".format(dt.hour, dt.minute)) }
        }
    }
}

@Composable
private fun TimesListEditor(label: String, times: List<Int>, onChange: (List<Int>) -> Unit) {
    val ctx = LocalContext.current
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow {
            times.forEachIndexed { idx, m ->
                AssistChip(
                    onClick = {
                        TimePickerDialog(ctx, { _, h, mn ->
                            onChange(times.toMutableList().also { it[idx] = h * 60 + mn }.sorted())
                        }, m / 60, m % 60, true).show()
                    },
                    label = { Text("%02d:%02d".format(m / 60, m % 60)) },
                    trailingIcon = {
                        IconButton(onClick = {
                            onChange(times.toMutableList().also { it.removeAt(idx) })
                        }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove), modifier = Modifier.size(16.dp))
                        }
                    },
                    modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)
                )
            }
            AssistChip(
                onClick = {
                    TimePickerDialog(ctx, { _, h, mn ->
                        onChange((times + (h * 60 + mn)).sorted())
                    }, 9, 0, true).show()
                },
                label = { Text(stringResource(R.string.edit_add_time)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) }
            )
        }
    }
}

@Composable
private fun WeekdaySelector(selected: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    val locale = Locale.getDefault()
    Column {
        Text(stringResource(R.string.edit_days_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow {
            DayOfWeek.values().forEach { d ->
                FilterChip(
                    selected = d in selected,
                    onClick = {
                        val new = selected.toMutableSet().also { if (d in it) it.remove(d) else it.add(d) }
                        onChange(new)
                    },
                    label = { Text(d.getDisplayName(TextStyle.SHORT, locale)) },
                    modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun MonthlyEditor(day: Int, timeMin: Int, onDay: (Int) -> Unit, onTime: (Int) -> Unit) {
    val ctx = LocalContext.current
    Column {
        Text(stringResource(R.string.edit_monthly_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = day.toString(),
                onValueChange = { it.toIntOrNull()?.let { v -> if (v in 1..31) onDay(v) } },
                label = { Text(stringResource(R.string.edit_day_label)) },
                modifier = Modifier.width(120.dp)
            )
            OutlinedButton(onClick = {
                TimePickerDialog(ctx, { _, h, mn -> onTime(h * 60 + mn) }, timeMin / 60, timeMin % 60, true).show()
            }) { Text(stringResource(R.string.edit_at_time, "%02d:%02d".format(timeMin / 60, timeMin % 60))) }
        }
    }
}

@Composable
private fun IntervalEditor(hours: Int, onChange: (Int) -> Unit) {
    Column {
        Text(stringResource(R.string.edit_interval_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = hours.toString(),
            onValueChange = { it.toIntOrNull()?.let { v -> if (v in 1..168) onChange(v) } },
            label = { Text(stringResource(R.string.edit_hours_label)) },
            modifier = Modifier.width(160.dp)
        )
    }
}

@Composable
private fun OnUnlockEditor() {
    Column {
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

