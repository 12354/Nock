package app.nock.android

import android.app.Application
import app.nock.android.data.SeedData
import app.nock.android.data.dao.GroupDao
import app.nock.android.di.ApplicationScope
import app.nock.android.notif.NockNotificationChannels
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NockApplication : Application() {

    @Inject lateinit var channels: NockNotificationChannels
    @Inject lateinit var seedData: SeedData
    @Inject lateinit var groupDao: GroupDao
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        channels.ensureCreated()
        appScope.launch {
            if (groupDao.getAll().isEmpty()) {
                groupDao.upsertAll(seedData.toEntities())
            }
        }
    }
}
