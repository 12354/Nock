package app.nock.android.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.nock.android.R
import app.nock.android.update.UpdateManager
import app.nock.android.update.UpdateResult
import app.nock.android.update.UpdateState
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.ui.components.GroupAvatar
import app.nock.android.ui.components.NockLogo
import app.nock.android.ui.components.UndoSnackbar
import app.nock.android.ui.components.stageIcon
import app.nock.android.ui.voice.VoiceAlarmFab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class TodaySection(val titleRes: Int, val items: List<TodayItem>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onAddReminder: () -> Unit,
    onEditReminder: (Long) -> Unit,
    scrollToTopSignal: Int = 0,
    vm: TodayViewModel = hiltViewModel()
) {
    val items by vm.items.collectAsState()
    val pendingDoneIds by vm.pendingDoneIds.collectAsState()
    val ctx = LocalContext.current
    val visibleItems = remember(items, pendingDoneIds) {
        items.filterNot { it.reminder.id in pendingDoneIds }
    }
    val sections = remember(visibleItems) { groupByBucket(visibleItems) }
    val activeItem = remember(items) { items.firstOrNull { it.isActive } }
    val isActiveItemPending = activeItem != null && activeItem.reminder.id in pendingDoneIds

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Reset the list to the top whenever the app is brought to the foreground
    // (the signal increments on every resume) and whenever this screen is freshly
    // entered, so "Heute" always opens at the top instead of a stale scroll spot.
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTopSignal) {
        listState.scrollToItem(0)
    }
    val undoMessage = stringResource(R.string.today_done_undo_message)
    val undoAction = stringResource(R.string.today_done_undo_action)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { NockLogo() }
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                UndoSnackbar(data = data, durationMs = TodayViewModel.UNDO_WINDOW_MS)
            }
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VoiceAlarmFab()
                ExtendedFloatingActionButton(
                    onClick = onAddReminder,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.today_empty_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.today_empty_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)
        ) {
            item("update-banner") {
                UpdateNowBar()
            }
            if (activeItem != null) {
                item("active-header") {
                    AnimatedVisibility(
                        visible = !isActiveItemPending,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        SectionHeader(stringResource(R.string.today_section_active_now))
                    }
                }
                item("active-card-${activeItem.reminder.id}") {
                    AnimatedVisibility(
                        visible = !isActiveItemPending,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        ActiveEscalationCard(
                            item = activeItem,
                            onDone = {
                                val reminderId = activeItem.reminder.id
                                vm.markDone(reminderId)
                                scope.launch {
                                    launch {
                                        // Dismiss the snackbar as soon as the
                                        // VM-side commit window closes, so the
                                        // countdown bar lines up with reality
                                        // instead of running past it.
                                        vm.pendingDoneIds.first { reminderId !in it }
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                    }
                                    val result = snackbarHostState.showSnackbar(
                                        message = undoMessage,
                                        actionLabel = undoAction,
                                        duration = SnackbarDuration.Indefinite
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        vm.undoDone(reminderId)
                                    }
                                }
                            },
                            onSnooze = { vm.snooze(activeItem.reminder.id) },
                            onClick = { onEditReminder(activeItem.reminder.id) }
                        )
                    }
                }
            }
            sections.forEach { section ->
                item("hdr-${section.titleRes}") {
                    SectionHeader(stringResource(section.titleRes))
                }
                section.items.forEach { item ->
                    item("row-${item.reminder.id}") {
                        ReminderRow(
                            item = item,
                            timeLabel = formatRowTime(ctx, item.reminder.nextFireAt),
                            relativeLabel = relativeLabel(ctx, item.reminder.nextFireAt),
                            onClick = { onEditReminder(item.reminder.id) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Slim, dismissable "Update ready" banner pinned to the top of the Today list.
 * Mirrors the M3 dark mock: an amber accent dot, a one-line version label, a
 * dismiss (×) and an Update action that flips to an "Updating…" spinner while the
 * download/install runs. It only renders when [UpdateManager] reports a genuine
 * update is available — otherwise the slot is empty and the list looks untouched.
 */
@Composable
private fun UpdateNowBar() {
    // Design accent: amber pill on the dark surface, with a near-black label on
    // the filled Update button so it reads on the bright fill.
    val accent = Color(0xFFFFB070)
    val onAccent = Color(0xFF3A2410)

    val context = LocalContext.current
    val updateManager = remember { UpdateManager(context) }
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var dismissed by remember { mutableStateOf(false) }

    suspend fun doCheck() {
        updateState = UpdateState.Checking
        updateState = when (val result = updateManager.checkForUpdate()) {
            is UpdateResult.Success ->
                if (result.info.hasUpdate) UpdateState.Available(result.info)
                else UpdateState.UpToDate
            is UpdateResult.Failure -> UpdateState.Error(result.error)
        }
    }

    LaunchedEffect(Unit) { doCheck() }

    // Re-check every time the app is brought to the foreground, not just on the
    // first composition, so a release published while the app sat in the
    // background is picked up on the next open without a cold start. This also
    // covers the system installer (a separate activity): when the user backs out
    // of it our activity resumes still parked on "Updating…", and the re-check
    // returns the banner to Available (cancelled) or removes it (installed). Skip
    // only while a download is mid-flight, so we don't clobber its progress.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && updateState !is UpdateState.Downloading) {
                scope.launch { doCheck() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state = updateState
    val isUpdating = state is UpdateState.Downloading || state is UpdateState.Installing
    val info = (state as? UpdateState.Available)?.info
    val downloadProgress = (state as? UpdateState.Downloading)?.progress

    // Unobtrusive by design: show only while an update is in play, and let the
    // user dismiss it for the session.
    if (dismissed || (info == null && !isUpdating)) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(17.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (isUpdating) R.string.today_update_updating else R.string.today_update_ready
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                if (downloadProgress != null) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.18f),
                    )
                } else {
                    Text(
                        text = if (isUpdating) {
                            stringResource(R.string.settings_update_opening_installer)
                        } else {
                            stringResource(R.string.today_update_version, info!!.remoteVersionCode)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isUpdating) {
                if (downloadProgress == null) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                }
            } else {
                IconButton(onClick = { dismissed = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.today_update_dismiss),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                val downloadFailedMsg = stringResource(R.string.settings_update_download_failed)
                Button(
                    onClick = {
                        scope.launch {
                            updateState = UpdateState.Downloading(0f)
                            val apk = updateManager.downloadUpdate { progress ->
                                updateState = UpdateState.Downloading(progress)
                            }
                            if (apk != null) {
                                updateState = UpdateState.Installing
                                updateManager.installApk(apk)
                            } else {
                                updateState = UpdateState.Error(downloadFailedMsg)
                            }
                        }
                    },
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = onAccent
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(
                        stringResource(R.string.today_update_action),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun ReminderRow(
    item: TodayItem,
    timeLabel: String,
    relativeLabel: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GroupAvatar(item.group, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.reminder.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(item.group.name)
                    if (relativeLabel != null) {
                        append(" · ")
                        append(relativeLabel)
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = timeLabel,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ActiveEscalationCard(
    item: TodayItem,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
    onClick: () -> Unit,
) {
    val active = item.active ?: return
    val color = Color(item.group.color)
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupAvatar(item.group, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${item.group.name.uppercase()} · ${stringResource(R.string.today_firing_now).uppercase()}",
                        color = color,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        letterSpacing = 0.4.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.reminder.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            StageProgress(chain = active.chain, currentIndex = active.nextStageIndex, accent = color)
            Spacer(Modifier.height(14.dp))
            val ctx = LocalContext.current
            // nextStageIndex is the stage that fires next at nextFireAtMs, so it
            // *is* the next escalation — don't advance past it. The last stage is
            // the repeating alarm, shown as "repeats at …" instead.
            val nextStage = if (active.nextStageIndex < active.chain.lastIndex)
                active.chain.stage(active.nextStageIndex)
            else null
            val timeStr = formatClock(active.nextFireAtMs)
            val subtitle = if (nextStage != null) {
                val name = stageDisplayName(ctx, nextStage.type)
                stringResource(R.string.today_active_next_stage, name, timeStr)
            } else {
                stringResource(R.string.today_active_repeats_at, timeStr)
            }
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.done))
                }
                FilledTonalButton(onClick = onSnooze) {
                    Icon(Icons.Filled.SkipNext, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.snooze))
                }
            }
        }
    }
}

@Composable
private fun StageProgress(
    chain: EscalationChain,
    currentIndex: Int,
    accent: Color,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val outline = MaterialTheme.colorScheme.outlineVariant
            val onSurfVar = MaterialTheme.colorScheme.onSurfaceVariant
            val ctx = LocalContext.current
            chain.stages.forEachIndexed { i, stage ->
                val done = i < currentIndex
                val live = i == currentIndex
                val tint = when {
                    done -> onSurfVar
                    live -> accent
                    else -> outline
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (live) Modifier else Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (live) 28.dp else 22.dp)
                            .clip(CircleShape)
                            .then(
                                if (live) Modifier.background(accent)
                                else Modifier.border(1.5.dp, tint, CircleShape)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (done) Icons.Filled.Check else stageIcon(stage.type),
                            contentDescription = null,
                            tint = if (live) Color.Black else tint,
                            modifier = Modifier.size(if (live) 16.dp else 12.dp)
                        )
                    }
                    if (live) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stageDisplayName(ctx, stage.type),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (i < chain.stages.lastIndex) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .padding(horizontal = 6.dp)
                            .background(
                                if (done) onSurfVar else outline,
                                RoundedCornerShape(1.dp)
                            )
                    )
                }
            }
        }
    }
}

private fun stageDisplayName(ctx: android.content.Context, type: app.nock.android.domain.model.StageType): String {
    val res = when (type) {
        app.nock.android.domain.model.StageType.SILENT -> R.string.stage_type_silent
        app.nock.android.domain.model.StageType.VIBRATE -> R.string.stage_type_vibrate
        app.nock.android.domain.model.StageType.TELEGRAM -> R.string.stage_type_telegram
        app.nock.android.domain.model.StageType.ALARM_VIBRATE -> R.string.stage_type_alarm_vibrate
        app.nock.android.domain.model.StageType.ALARM -> R.string.stage_type_alarm
    }
    return ctx.getString(res)
}

private fun groupByBucket(items: List<TodayItem>): List<TodaySection> {
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val nextHourCutoff = now + 60 * 60_000L
    // The Today view is a near-term agenda, so cap it at the next 7 days. Anything
    // firing further out is hidden here (it still lives in the reminder list).
    val horizonCutoff = now + 7 * 24 * 60 * 60_000L

    val nextHour = mutableListOf<TodayItem>()
    val later = mutableListOf<TodayItem>()
    val tomorrow = mutableListOf<TodayItem>()
    val laterDays = mutableListOf<TodayItem>()

    items
        .filter { !it.isActive && it.reminder.nextFireAt != null && it.reminder.nextFireAt <= horizonCutoff }
        .sortedBy { it.reminder.nextFireAt }
        .forEach { item ->
            val ms = item.reminder.nextFireAt ?: return@forEach
            val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toLocalDate()
            when {
                date == today && ms <= nextHourCutoff -> nextHour += item
                date == today -> later += item
                date == today.plusDays(1) -> tomorrow += item
                else -> laterDays += item
            }
        }

    return buildList {
        if (nextHour.isNotEmpty()) add(TodaySection(R.string.today_section_next_hour, nextHour))
        if (later.isNotEmpty()) add(TodaySection(R.string.today_section_later_today, later))
        if (tomorrow.isNotEmpty()) add(TodaySection(R.string.today_section_tomorrow, tomorrow))
        if (laterDays.isNotEmpty()) add(TodaySection(R.string.today_section_later, laterDays))
    }
}

private fun formatRowTime(ctx: android.content.Context, ms: Long?): String {
    if (ms == null) return ctx.getString(R.string.today_no_next_fire)
    val locale = Locale.getDefault()
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = dt.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    return when (dt.toLocalDate()) {
        today -> time
        today.plusDays(1) -> "$time · ${dt.format(DateTimeFormatter.ofPattern("EEE", locale))}"
        else -> "$time · ${dt.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))}"
    }
}

private fun formatClock(ms: Long): String {
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    return dt.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
}

private fun relativeLabel(ctx: android.content.Context, ms: Long?): String? {
    if (ms == null) return null
    val diff = ms - System.currentTimeMillis()
    if (diff <= 0) return null
    val minutes = (diff / 60_000L).toInt()
    return when {
        minutes < 60 -> ctx.getString(R.string.time_in_minutes, minutes)
        minutes < 24 * 60 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) ctx.getString(R.string.time_in_hours, h)
            else ctx.getString(R.string.time_in_hours_minutes, h, m)
        }
        else -> null
    }
}
