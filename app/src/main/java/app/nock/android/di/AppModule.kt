package app.nock.android.di

import android.content.Context
import androidx.room.Room
import app.nock.android.data.MIGRATION_1_2
import app.nock.android.data.MIGRATION_2_3
import app.nock.android.data.MIGRATION_3_4
import app.nock.android.data.MIGRATION_4_5
import app.nock.android.data.MIGRATION_5_6
import app.nock.android.data.NockDatabase
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.CalendarTripDao
import app.nock.android.data.dao.GroupDao
import app.nock.android.data.dao.PendingTelegramDeletionDao
import app.nock.android.data.dao.PendingVoiceReminderDao
import app.nock.android.data.dao.ReminderDao
import app.nock.android.data.dao.SettingsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): NockDatabase =
        Room.databaseBuilder(ctx, NockDatabase::class.java, "nock.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideGroupDao(db: NockDatabase): GroupDao = db.groupDao()
    @Provides fun provideReminderDao(db: NockDatabase): ReminderDao = db.reminderDao()
    @Provides fun provideActiveDao(db: NockDatabase): ActiveEscalationDao = db.activeEscalationDao()
    @Provides fun provideSettingsDao(db: NockDatabase): SettingsDao = db.settingsDao()
    @Provides fun providePendingVoiceReminderDao(db: NockDatabase): PendingVoiceReminderDao =
        db.pendingVoiceReminderDao()
    @Provides fun providePendingTelegramDeletionDao(db: NockDatabase): PendingTelegramDeletionDao =
        db.pendingTelegramDeletionDao()
    @Provides fun provideCalendarTripDao(db: NockDatabase): CalendarTripDao = db.calendarTripDao()

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton @ApplicationScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
