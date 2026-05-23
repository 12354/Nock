package app.nock.android.alarm

import android.app.KeyguardManager
import android.os.Build
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            km.requestDismissKeyguard(this, null)
        }

        val escalationId = intent.getLongExtra(IntentExtras.EXTRA_ESCALATION_ID, -1L)
        val reminderId = intent.getLongExtra(IntentExtras.EXTRA_REMINDER_ID, -1L)

        val nameState = MutableStateFlow(getString(R.string.alarm_title))
        val groupNameState = MutableStateFlow("")
        lifecycleScope.launch {
            val r = repo.getReminder(reminderId)
            if (r != null) {
                nameState.value = r.name
                groupNameState.value = repo.getGroup(r.groupId)?.name.orEmpty()
            }
        }

        setContent {
            NockTheme {
                val name by nameState.collectAsState()
                val groupName by groupNameState.collectAsState()
                AlarmScreen(
                    name = name,
                    groupName = groupName,
                    onDone = {
                        lifecycleScope.launch {
                            engine.done(escalationId)
                            finish()
                        }
                    },
                    onSnooze = {
                        lifecycleScope.launch {
                            engine.snooze(escalationId)
                            finish()
                        }
                    }
                )
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
