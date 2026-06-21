package app.nock.android.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.nock.android.R
import app.nock.android.data.NockRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.json.ChainJson
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.ui.LocaleHelper
import app.nock.android.ui.components.StageProgress
import app.nock.android.ui.components.groupIconFor
import app.nock.android.ui.theme.NockTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
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

    private val nameState = MutableStateFlow("")
    private val groupState = MutableStateFlow<Group?>(null)
    private val escalationIdState = MutableStateFlow(-1L)
    private val chainState = MutableStateFlow<EscalationChain?>(null)
    private val startedAtState = MutableStateFlow(0L)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAlarmWindowFlags()
        nameState.value = getString(R.string.alarm_title)
        bindFromIntent(intent)
        finishWhenAlarmStops()

        setContent {
            NockTheme {
                val name by nameState.collectAsState()
                val group by groupState.collectAsState()
                val chain by chainState.collectAsState()
                val startedAt by startedAtState.collectAsState()
                AlarmTakeoverScreen(
                    name = name,
                    group = group,
                    chain = chain,
                    startedAtMs = startedAt,
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask launchMode reuses the activity for subsequent alarms; without
        // re-reading the intent the screen would still show the previous alarm.
        applyAlarmWindowFlags()
        bindFromIntent(intent)
    }

    /**
     * The takeover must vanish the moment its alarm stops ringing. The only way
     * to leave this screen used to be its own Done/Snooze buttons (which call
     * finish() themselves) or the escalation row disappearing. But an alarm is
     * routinely stopped from *elsewhere* while the takeover is on screen — Snooze
     * or Done tapped on the notification, the Today screen's Done, a reminder
     * move, a group pause, a room detection, a sync. None of those finish this
     * activity, so it lingered (silent, since AlarmService had stopped) in its
     * own task and resurfaced on the next app foreground — a "ghost" alarm. A
     * snoozed escalation's row still exists, so the row-missing guard in
     * bindFromIntent never caught it.
     *
     * AlarmService is the single source of truth for "an alarm is ringing right
     * now"; collecting its state and finishing on null closes every one of those
     * paths at once. A different non-null id means another alarm took over — the
     * service re-launches us for it via onNewIntent (singleTask), so only null
     * (nothing ringing) finishes us.
     */
    private fun finishWhenAlarmStops() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AlarmService.ringingEscalation.collect { ringingId ->
                    if (ringingId == null) finish()
                }
            }
        }
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
        val escalationId = intent?.getLongExtra(IntentExtras.EXTRA_ESCALATION_ID, -1L) ?: -1L
        val intentReminderId = intent?.getLongExtra(IntentExtras.EXTRA_REMINDER_ID, -1L) ?: -1L
        escalationIdState.value = escalationId
        groupState.value = null
        chainState.value = null
        lifecycleScope.launch {
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
            }
            // The receiver launches us without a reminderId (it only knows the
            // escalation), so fall back to the escalation row's reminderId to
            // resolve the alarm's name and group tint.
            val reminderId = if (intentReminderId >= 0L) intentReminderId else esc?.reminderId ?: -1L
            val r = if (reminderId >= 0L) repo.getReminder(reminderId) else null
            if (r != null) {
                nameState.value = r.name
                groupState.value = repo.getGroup(r.groupId)
            }
        }
    }
}

@Composable
private fun AlarmTakeoverScreen(
    name: String,
    group: Group?,
    chain: EscalationChain?,
    startedAtMs: Long,
    onDone: () -> Unit,
    onSnooze: () -> Unit
) {
    val accent = group?.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (group != null) {
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
                Text(
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

                Spacer(Modifier.weight(1f))

                if (chain != null && stageIndex != null) {
                    StageProgress(chain = chain, currentIndex = stageIndex, accent = accent)
                }

                Spacer(Modifier.height(32.dp))
                // Extra-large pill Done button — the design's primary affordance.
                Button(
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
                        text = if (repeatMin != null)
                            stringResource(R.string.alarm_snooze_minutes, repeatMin)
                        else stringResource(R.string.snooze),
                        color = onSurface,
                        fontSize = 16.sp
                    )
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
