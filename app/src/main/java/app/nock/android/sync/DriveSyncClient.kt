package app.nock.android.sync

import android.accounts.Account
import android.content.Context
import app.nock.android.data.SettingsRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveSyncClient @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val snapshots: SnapshotService,
    private val settings: SettingsRepository,
) {
    private val driveScopes = listOf(DriveScopes.DRIVE_APPDATA)
    private val snapshotName = "nock-snapshot.json"

    // Serializes all Drive operations. Without it, two concurrent pulls (a
    // double-tapped "Pull now", or a pull racing a foreground sync) both pass the
    // savedAt freshness check and both run the full clear+repopulate import; two
    // first-ever pushes both take the create branch and duplicate the snapshot
    // file in appDataFolder. One in-flight op at a time removes both windows.
    private val syncMutex = Mutex()

    fun isConfigured(): Boolean = GoogleSignIn.getLastSignedInAccount(ctx) != null

    fun signedInEmail(): String? = GoogleSignIn.getLastSignedInAccount(ctx)?.email

    private fun driveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(ctx) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(ctx, driveScopes)
        credential.selectedAccount = Account(account.email!!, "com.google")
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Nock").build()
    }

    suspend fun pushSnapshot(): SyncOutcome = syncMutex.withLock {
        withContext(Dispatchers.IO) {
        val service = driveService() ?: return@withContext SyncOutcome.NotSignedIn
        try {
            val exported = snapshots.export()
            val existing = findSnapshotFileId(service)
            val metadata = com.google.api.services.drive.model.File().apply {
                name = snapshotName
                if (existing == null) parents = listOf("appDataFolder")
            }
            val content = ByteArrayContent("application/json", exported.json.toByteArray(Charsets.UTF_8))
            if (existing == null) {
                service.files().create(metadata, content).setFields("id").execute()
            } else {
                service.files().update(existing, metadata, content).execute()
            }
            // Record the just-pushed snapshot as already-applied. Without this the
            // next pullIfNewer() on THIS device sees its own fresh savedAtMs as
            // "newer" than the stale local cursor and re-imports it — a needless
            // wipe-and-replace that also drops the device-local Trips group until
            // the next calendar sync rebuilds it.
            settings.set(SettingsRepository.KEY_DRIVE_LAST_REMOTE_MS, exported.savedAtMs.toString())
            settings.set(SettingsRepository.KEY_DRIVE_LAST_SYNC_MS, System.currentTimeMillis().toString())
            SyncOutcome.Ok
        } catch (t: Throwable) {
            SyncOutcome.Error(t.message ?: t::class.simpleName ?: "error")
        }
        }
    }

    suspend fun pullIfNewer(): SyncOutcome = syncMutex.withLock {
        withContext(Dispatchers.IO) {
        val service = driveService() ?: return@withContext SyncOutcome.NotSignedIn
        try {
            val fileId = findSnapshotFileId(service) ?: return@withContext SyncOutcome.Ok
            val out = ByteArrayOutputStream()
            service.files().get(fileId).executeMediaAndDownloadTo(out)
            val json = out.toString(Charsets.UTF_8)
            // Merged new data and "already in sync" are both healthy outcomes; a
            // remote that downloaded but couldn't be decoded (empty/truncated file
            // from an interrupted push, or an unsupported version) is a real error
            // the user should see — not a silent no-op that looks in-sync.
            when (snapshots.mergeIfNewer(json)) {
                MergeResult.Merged, MergeResult.NotNewer -> SyncOutcome.Ok
                MergeResult.Corrupt -> SyncOutcome.Error("remote snapshot is empty or unreadable")
            }
        } catch (t: Throwable) {
            SyncOutcome.Error(t.message ?: t::class.simpleName ?: "error")
        }
        }
    }

    private fun findSnapshotFileId(service: Drive): String? {
        val list = service.files().list()
            .setSpaces("appDataFolder")
            .setFields("files(id,name,modifiedTime)")
            .setQ("name='$snapshotName'")
            .execute()
        return list.files?.firstOrNull()?.id
    }
}

sealed class SyncOutcome {
    object Ok : SyncOutcome()
    object NotSignedIn : SyncOutcome()
    data class Error(val message: String) : SyncOutcome()
}
