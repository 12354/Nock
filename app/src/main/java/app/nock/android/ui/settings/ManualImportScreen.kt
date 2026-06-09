@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nock.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import app.nock.android.R
import app.nock.android.trip.CalendarEvent
import app.nock.android.trip.TripPreview
import app.nock.android.ui.components.stageIcon
import app.nock.android.ui.components.stageTypeLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Manual single-appointment importer: a three-step flow (pick calendar → pick
 * appointment → preview & import) reached from the Calendar alarms settings. Lets
 * the user deliberately re-create a reminder — including one they dismissed, which
 * the automatic sync never resurrects.
 */
@Composable
fun ManualImportScreen(
    onBack: () -> Unit,
    closeAfterImport: Boolean = false,
    onImported: () -> Unit = {},
    vm: ManualImportViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    // Back walks the step machine; only exits the importer at the first step.
    BackHandler { if (!vm.back()) onBack() }

    // When launched from the new-reminder view, a successful import is the end of
    // the flow: dismiss the importer (and the reminder view beneath it) rather than
    // offering to import another.
    LaunchedEffect(state.imported) {
        if (closeAfterImport && state.imported) onImported()
    }

    val title = when (state.stage) {
        ManualImportStage.PICK_CALENDAR -> stringResource(R.string.trips_manual_step_calendar)
        ManualImportStage.PICK_EVENT -> stringResource(R.string.trips_manual_step_event)
        ManualImportStage.PREVIEW -> stringResource(R.string.trips_manual_step_preview)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { if (!vm.back()) onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        when (state.stage) {
            ManualImportStage.PICK_CALENDAR -> CalendarPicker(state, vm, padding)
            ManualImportStage.PICK_EVENT -> EventPicker(state, vm, padding)
            ManualImportStage.PREVIEW -> PreviewStep(state, vm, padding, closeAfterImport)
        }
    }
}

@Composable
private fun CalendarPicker(state: ManualImportUiState, vm: ManualImportViewModel, padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.trips_manual_pick_calendar),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.trips_manual_pick_calendar_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (state.calendars.isEmpty()) {
            Text(
                stringResource(R.string.trips_manual_no_calendars),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                state.calendars.forEachIndexed { idx, cal ->
                    if (idx > 0) HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(cal.displayName) },
                        supportingContent = cal.accountName.takeIf { it.isNotBlank() }
                            ?.let { name -> { Text(name) } },
                        leadingContent = {
                            Icon(
                                Icons.Outlined.CalendarMonth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { vm.selectCalendar(cal) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EventPicker(state: ManualImportUiState, vm: ManualImportViewModel, padding: PaddingValues) {
    // Match the query against title, location and a few date renderings so the
    // user can search "by name or date" interchangeably.
    val filtered = remember(state.events, state.query) {
        val q = state.query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) state.events
        else state.events.filter { e ->
            (e.title + " " + e.location + " " + searchableDate(e.beginMs))
                .lowercase(Locale.getDefault())
                .contains(q)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.trips_manual_pick_event),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.trips_manual_pick_event_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            label = { Text(stringResource(R.string.trips_manual_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.eventsLoading -> CenteredLoading(stringResource(R.string.trips_manual_loading_events))
            state.events.isEmpty() -> Text(
                stringResource(R.string.trips_manual_no_events),
                style = MaterialTheme.typography.bodyMedium,
            )
            filtered.isEmpty() -> Text(
                stringResource(R.string.trips_manual_no_matches),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filtered, key = { "${it.eventId}:${it.beginMs}" }) { event ->
                    EventRow(event, onClick = { vm.selectEvent(event) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            formatDateTime(event.beginMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            event.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            event.location.ifBlank { stringResource(R.string.trips_manual_event_no_location) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PreviewStep(
    state: ManualImportUiState,
    vm: ManualImportViewModel,
    padding: PaddingValues,
    closeAfterImport: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val preview = state.preview
        when {
            state.previewLoading || preview == null ->
                CenteredLoading(stringResource(R.string.trips_manual_computing))
            else -> {
                PreviewCard(preview, state.selectedCalendar?.displayName)

                if (state.imported && closeAfterImport) {
                    // Launched from the new-reminder view: the importer is closing
                    // itself, so don't offer "import another".
                } else if (state.imported) {
                    Text(
                        stringResource(R.string.trips_manual_imported),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.importAnother() }) {
                            Text(stringResource(R.string.trips_manual_import_another))
                        }
                    }
                } else {
                    Button(
                        onClick = { vm.importSelected() },
                        enabled = !state.importing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.importing) stringResource(R.string.trips_manual_importing)
                            else stringResource(R.string.trips_manual_import_button)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(preview: TripPreview, calendarName: String?) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.trips_manual_preview_reminder),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // The reminder name as it will appear in the lists.
            Text(preview.reminderName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)

            HorizontalDivider()

            PreviewRow(stringResource(R.string.trips_manual_field_appointment), preview.event.title)
            calendarName?.let { PreviewRow(stringResource(R.string.trips_manual_field_calendar), it) }
            PreviewRow(stringResource(R.string.trips_manual_field_starts), formatDateTime(preview.event.beginMs))

            if (preview.hasLocation) {
                PreviewRow(stringResource(R.string.trips_manual_field_location), preview.event.location)
                preview.homeAddress?.let {
                    PreviewRow(stringResource(R.string.trips_manual_field_from), it)
                }
                PreviewRow(
                    stringResource(R.string.trips_manual_field_travel),
                    durationLabel(preview.travelMs),
                )
                PreviewRow(
                    stringResource(R.string.trips_manual_field_leave_by),
                    formatDateTime(preview.leaveByMs),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.LocationOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.trips_manual_no_location_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                PreviewRow(
                    stringResource(R.string.trips_manual_field_alerts_at),
                    formatDateTime(preview.leaveByMs),
                )
            }

            HorizontalDivider()

            Text(
                stringResource(R.string.trips_manual_field_steps),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            preview.steps.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        stageIcon(step.type),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(stageTypeLabel(step.type)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatTime(step.atMs), style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Notes: surfaced only when relevant so a clean import shows nothing extra.
            val notes = buildList {
                if (preview.hasLocation && !preview.travelLive) add(stringResource(R.string.trips_manual_travel_fallback))
                if (preview.hasLocation && preview.homeAddress == null) add(stringResource(R.string.trips_manual_home_missing))
                if (preview.hasLocation && preview.homeAddress != null && !preview.destResolved)
                    add(stringResource(R.string.trips_manual_dest_unresolved))
                if (preview.alreadyImported) add(stringResource(R.string.trips_manual_already_imported))
                if (preview.previouslyDismissed) add(stringResource(R.string.trips_manual_previously_dismissed))
            }
            if (notes.isNotEmpty()) {
                HorizontalDivider()
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        notes.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CenteredLoading(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun durationLabel(ms: Long): String {
    val totalMin = (ms / 60_000L).toInt().coerceAtLeast(0)
    return if (totalMin >= 60)
        stringResource(R.string.trips_manual_duration_h_min, totalMin / 60, totalMin % 60)
    else stringResource(R.string.trips_manual_duration_min, totalMin)
}

private val DATE_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm", Locale.getDefault())
private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

private fun formatDateTime(ms: Long): String =
    DATE_TIME_FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun formatTime(ms: Long): String =
    TIME_FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** A space-joined bag of date renderings so "search by date" matches dd, month names and ISO. */
private fun searchableDate(ms: Long): String {
    val zdt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return DATE_TIME_FMT.format(zdt) + " " +
        DateTimeFormatter.ISO_LOCAL_DATE.format(zdt)
}
