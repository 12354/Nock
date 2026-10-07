package app.nock.android.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import app.nock.android.R
import app.nock.android.bluetooth.BluetoothPauseMonitor
import app.nock.android.data.NockRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.CalendarTripDao
import app.nock.android.data.json.ChainJson
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.domain.trip.TripAlarmInfo
import app.nock.android.ui.components.TripAlarmDetails
import app.nock.android.ui.LocaleHelper
import app.nock.android.ui.components.StageProgress
import app.nock.android.ui.components.groupIconFor
import app.nock.android.ui.theme.NockTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {

    @Inject lateinit var repo: NockRepository
    @Inject lateinit var engine: EscalationEngine
    @Inject lateinit var activeDao: ActiveEscalationDao
    @Inject lateinit var tripDao: CalendarTripDao
    @Inject lateinit var bluetoothPause: BluetoothPauseMonitor

    private val nameState = MutableStateFlow("")
    private val groupState = MutableStateFlow<Group?>(null)
    private val escalationIdState = MutableStateFlow(-1L)
    private val chainState = MutableStateFlow<EscalationChain?>(null)
    private val startedAtState = MutableStateFlow(0L)
    private val tripState = MutableStateFlow<TripAlarmInfo?>(null)
    // The reminders a collected alarm stands for (empty for an ordinary alarm).
    private val itemsState = MutableStateFlow<List<EscalationEngine.CollectedItem>>(emptyList())
    private var bindingJob: Job? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAlarmWindowFlags()
        nameState.value = getString(R.string.alarm_title)
        bindFromIntent(intent)

        // A paused Bluetooth device (e.g. the car) connected while this alarm was
        // on screen: the engine has silenced and held it, so get out of the way.
        lifecycleScope.launch {
            bluetoothPause.pauseStarted.collect { finish() }
        }

        setContent {
            NockTheme {
                val name by nameState.collectAsState()
                val group by groupState.collectAsState()
                val chain by chainState.collectAsState()
                val startedAt by startedAtState.collectAsState()
                val trip by tripState.collectAsState()
                val items by itemsState.collectAsState()
                AlarmTakeoverScreen(
                    name = name,
                    group = group,
                    chain = chain,
                    startedAtMs = startedAt,
                    trip = trip,
                    items = items,
                    onItemDone = { id -> lifecycleScope.launch { continueWith(engine.doneInCollection(id)) } },
                    onItemSnooze = { id -> lifecycleScope.launch { continueWith(engine.snoozeInCollection(id)) } },
                    onDone = {
                        val id = escalationIdState.value
                        lifecycleScope.launch {
                            if (id >= 0L) engine.done(id)
                            finish()
                        }
                    },
                    onSnooze = {
                        val id = escalationIdState.value
                        lifecycleScope.launch {
                            if (id >= 0L) engine.snooze(id)
                            finish()
                        }
                    }
                )
            }
        }
    }

    /** After ticking off one reminder of a collected alarm: show what's left, or leave. */
    private fun continueWith(nextLeadId: Long?) {
        if (nextLeadId == null) {
            finish()
            return
        }
        bindFromIntent(Intent().putExtra(IntentExtras.EXTRA_ESCALATION_ID, nextLeadId))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask launchMode reuses the activity for subsequent alarms; without
        // re-reading the intent the screen would still show the previous alarm.
        applyAlarmWindowFlags()
        bindFromIntent(intent)
    }

    private fun applyAlarmWindowFlags() {
        // minSdk is 29 so the O_MR1+ activity-level APIs are always available.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Belt-and-suspenders: some OEM builds (Samsung/Xiaomi) only honor the
        // legacy window flags when the activity launches from the keyguard, so
        // set both the modern APIs above and the deprecated flags below.
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun bindFromIntent(intent: Intent?) {
        bindingJob?.cancel()
        val escalationId = intent?.getLongExtra(IntentExtras.EXTRA_ESCALATION_ID, -1L) ?: -1L
        val intentReminderId = intent?.getLongExtra(IntentExtras.EXTRA_REMINDER_ID, -1L) ?: -1L
        escalationIdState.value = escalationId
        groupState.value = null
        chainState.value = null
        tripState.value = null
        itemsState.value = emptyList()
        startedAtState.value = 0L
        bindingJob = lifecycleScope.launch {
            val esc = if (escalationId >= 0L) activeDao.getById(escalationId) else null
            if (escalationId >= 0L && esc == null) {
                finish()
                return@launch
            }
            // The chain snapshot drives the stage rail, the "repeats every N min"
            // subtitle, and the snooze duration label — the real chain, not the
            // hardcoded 4-dot default this screen used to show.
            if (esc != null) {
                chainState.value = runCatching { ChainJson.decode(esc.chainSnapshotJson) }.getOrNull()
                startedAtState.value = esc.startedAtMs
                itemsState.value = engine.collectedItems(esc.id)
            }
            // The receiver launches us without a reminderId (it only knows the
            // escalation), so fall back to the escalation row's reminderId to
            // resolve the alarm's name and group tint.
            val reminderId = if (intentReminderId >= 0L) intentReminderId else esc?.reminderId ?: -1L
            val r = if (reminderId >= 0L) repo.getReminder(reminderId) else null
            if (r != null) {
                nameState.value = r.name
                groupState.value = repo.getGroup(r.groupId)
                // Follow cached routing updates while this alarm is on screen.
                combine(tripDao.observeByReminderId(r.id), repo.observeReminders()) { trip, reminders ->
                    val leaveBy = reminders.firstOrNull { it.id == r.id }?.nextFireAt
                    if (trip != null && trip.location.isNotBlank() && leaveBy != null) {
                        TripAlarmInfo(leaveBy, trip.lastTravelMs, trip.bufferMs)
                    } else null
                }.collect { tripState.value = it }
            }
        }
    }
}

@Composable
internal fun AlarmTakeoverScreen(
    name: String,
    group: Group?,
    chain: EscalationChain?,
    startedAtMs: Long,
    trip: TripAlarmInfo?,
    items: List<EscalationEngine.CollectedItem> = emptyList(),
    onItemDone: (Long) -> Unit = {},
    onItemSnooze: (Long) -> Unit = {},
    onDone: () -> Unit,
    onSnooze: () -> Unit
) {
    // A collected alarm — several reminders held during a Bluetooth pause and
    // released together. They're usually separate to-dos, so each gets its own
    // Done and Snooze instead of one Done for the lot.
    val collected = items.size >= 2
    // The reminders may come from different groups, so a collected alarm takes the
    // app accent rather than the first reminder's group tint; each row shows its own.
    val accent = group?.takeIf { !collected }?.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // The stage actually ringing right now, from elapsed time — same rule the
    // engine uses for snooze (see EscalationEngine.snoozeLocked).
    val stageIndex = remember(chain, startedAtMs) {
        chain?.stageDueAt(startedAtMs, System.currentTimeMillis())?.coerceIn(0, chain.lastIndex)
    }
    val repeatMin = chain?.let { (it.repeatIntervalMs / 60_000L).toInt() }

    Surface(modifier = Modifier.fillMaxSize(), color = surface) {
        // Vertical gradient that fades the group tint into the surface — gives the takeover
        // its distinctive group-colored "halo" without overwhelming the alarm-clock layout.
        val gradient = Brush.verticalGradient(
            0f to accent.copy(alpha = 0.22f),
            0.6f to surface,
            1f to surface,
        )
        Box(modifier = Modifier.fillMaxSize().background(gradient)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Keep Done and Snooze reachable even with long trip names or large text.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (group != null && !collected) {
                            GroupPill(group = group, accent = accent)
                        } else {
                            Spacer(Modifier.size(1.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        if (chain != null && stageIndex != null) {
                            Text(
                                text = stringResource(
                                    R.string.alarm_stage_of, stageIndex + 1, chain.stages.size
                                ).uppercase(),
                                color = onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(Modifier.height(36.dp))
                    // Big clock — alarm-clock style. Re-read periodically so the minute
                    // actually advances while the takeover is on screen.
                    var clock by remember { mutableStateOf(currentClock()) }
                    val date = remember { currentDate() }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(10_000L)
                            clock = currentClock()
                        }
                    }
                    Text(
                        text = clock,
                        fontSize = 84.sp,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Light,
                        color = onSurface,
                        letterSpacing = (-2).sp
                    )
                    Text(
                        text = date,
                        color = onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(Modifier.height(48.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Alarm, contentDescription = null, tint = accent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.alarm_loud_alarm).uppercase(),
                            color = accent,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    if (collected) {
                        Text(
                            text = pluralStringResource(R.plurals.alarm_collected_title, items.size, items.size),
                            fontSize = 28.sp,
                            color = onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        items.forEach { item ->
                            CollectedItemRow(
                                item = item,
                                fallbackAccent = accent,
                                onDone = { onItemDone(item.escalationId) },
                                onSnooze = { onItemSnooze(item.escalationId) },
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    } else Text(
                        text = name,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Normal,
                        color = onSurface,
                        textAlign = TextAlign.Center,
                        lineHeight = 42.sp
                    )
                    if (repeatMin != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.alarm_subtitle_repeats, repeatMin),
                            color = onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (trip != null && !collected) {
                        Spacer(Modifier.height(20.dp))
                        TripAlarmDetails(trip)
                    }

                    Spacer(Modifier.height(24.dp))

                    if (chain != null && stageIndex != null) {
                        StageProgress(chain = chain, currentIndex = stageIndex, accent = accent)
                    }
                }

                Spacer(Modifier.height(32.dp))
                // Extra-large pill Done button — the design's primary affordance.
                // A collected alarm has a Done per reminder instead.
                if (!collected) Button(
                    onClick = onDone,
                    modifier = Modifier
                        .height(80.dp)
                        .widthIn(min = 200.dp),
                    shape = RoundedCornerShape(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 32.dp)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.done), fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onSnooze) {
                    Icon(Icons.Outlined.Snooze, contentDescription = null, tint = onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // Snoozing the loud alarm re-rings one repeat interval out
                        // (EscalationEngine.snoozeLocked), so say that number.
                        text = when {
                            collected && repeatMin != null ->
                                stringResource(R.string.alarm_snooze_all_minutes, repeatMin)
                            collected -> stringResource(R.string.alarm_snooze_all)
                            repeatMin != null -> stringResource(R.string.alarm_snooze_minutes, repeatMin)
                            else -> stringResource(R.string.snooze)
                        },
                        color = onSurface,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

/** One reminder of a collected alarm, with its own Done and Snooze. */
@Composable
private fun CollectedItemRow(
    item: EscalationEngine.CollectedItem,
    fallbackAccent: Color,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
) {
    val accent = item.group?.color?.let { Color(it) } ?: fallbackAccent
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.group != null) {
                    Icon(
                        imageVector = groupIconFor(item.group.icon),
                        contentDescription = item.group.name,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSnooze) {
                    Icon(Icons.Outlined.Snooze, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.snooze))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onDone, shape = RoundedCornerShape(100.dp)) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.done))
                }
            }
        }
    }
}

@Composable
private fun GroupPill(group: Group, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.18f),
        contentColor = accent,
        shape = RoundedCornerShape(100.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = groupIconFor(group.icon),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                group.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun currentClock(): String {
    val locale = Locale.getDefault()
    return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm", locale))
}

private fun currentDate(): String {
    val locale = Locale.getDefault()
    return LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))
}
