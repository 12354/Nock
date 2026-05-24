package app.nock.android.alarm

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import androidx.lifecycle.lifecycleScope
import app.nock.android.R
import app.nock.android.data.NockRepository
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.ui.LocaleHelper
import app.nock.android.ui.theme.NockTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {

    @Inject lateinit var repo: NockRepository
    @Inject lateinit var engine: EscalationEngine

    private val nameState = MutableStateFlow("")
    private val groupNameState = MutableStateFlow("")
    private val escalationIdState = MutableStateFlow(-1L)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAlarmWindowFlags()
        nameState.value = getString(R.string.alarm_title)
        bindFromIntent(intent)

        setContent {
            NockTheme {
                val name by nameState.collectAsState()
                val groupName by groupNameState.collectAsState()
                AlarmScreen(
                    name = name,
                    groupName = groupName,
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

    private fun applyAlarmWindowFlags() {
        // minSdk is 29 so the O_MR1+ activity-level APIs are always available.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        km?.requestDismissKeyguard(this, null)
    }

    private fun bindFromIntent(intent: Intent?) {
        val escalationId = intent?.getLongExtra(IntentExtras.EXTRA_ESCALATION_ID, -1L) ?: -1L
        val reminderId = intent?.getLongExtra(IntentExtras.EXTRA_REMINDER_ID, -1L) ?: -1L
        escalationIdState.value = escalationId
        groupNameState.value = ""
        lifecycleScope.launch {
            val r = if (reminderId >= 0L) repo.getReminder(reminderId) else null
            if (r != null) {
                nameState.value = r.name
                groupNameState.value = repo.getGroup(r.groupId)?.name.orEmpty()
            }
        }
    }
}

@Composable
private fun AlarmScreen(
    name: String,
    groupName: String,
    onDone: () -> Unit,
    onSnooze: () -> Unit
) {
    val ctx = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(48.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Alarm,
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = name,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                if (groupName.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = groupName,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                ) {
                    Text(stringResource(R.string.done), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(stringResource(R.string.snooze_10_min), fontSize = 18.sp)
                }
            }
        }
    }
}
