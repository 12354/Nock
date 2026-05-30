package app.nock.android

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.nock.android.alarm.UnlockReceiver
import app.nock.android.data.SeedData
import app.nock.android.data.SeedGroupLocaleSync
import app.nock.android.data.dao.GroupDao
import app.nock.android.di.ApplicationScope
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.notif.NockNotificationChannels
import app.nock.android.ui.LocaleHelper
import app.nock.android.voice.PendingVoiceProcessor
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NockApplication : Application() {

    @Inject lateinit var channels: NockNotificationChannels
    @Inject lateinit var seedData: SeedData
    @Inject lateinit var groupDao: GroupDao
    @Inject lateinit var seedGroupLocaleSync: SeedGroupLocaleSync
    @Inject lateinit var pendingVoiceProcessor: PendingVoiceProcessor
    @Inject lateinit var engine: EscalationEngine
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        val tag = LocaleHelper.getLanguageTag(this)
        val locales = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
        channels.ensureCreated()
        // ACTION_USER_PRESENT cannot be declared in the manifest on Android
        // 8+ (Oreo), so register at runtime. The receiver lives for the life
        // of the process; AlarmManager wake-ups and the foreground alarm
        // service keep the process alive long enough to catch the next
        // unlock for typical "remember soon" use cases.
        registerReceiver(UnlockReceiver(), IntentFilter(Intent.ACTION_USER_PRESENT))
        appScope.launch {
            if (groupDao.getAll().isEmpty()) {
                groupDao.upsertAll(seedData.toEntities())
            } else {
                seedGroupLocaleSync.sync()
            }
            // Re-arm every active escalation against its stored next-fire time
            // on process start, the same way boot/time-change does. This
            // self-heals escalations whose pending alarm went missing — e.g. a
            // Done/Snooze whose process was killed mid-handling after the old
            // alarm was cancelled but before the next one was armed, which would
            // otherwise leave a reminder stuck at a past fire time that never
            // rings again. An overdue stored time fires (and catches up) at once.
            engine.rescheduleAll()
        }
        pendingVoiceProcessor.kickAll()
    }
}
