package app.nock.android.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val remoteVersionCode: Int,
    val currentVersionCode: Int
) {
    val hasUpdate: Boolean get() = remoteVersionCode > currentVersionCode
}

sealed class UpdateResult {
    data class Success(val info: UpdateInfo) : UpdateResult()
    data class Failure(val error: String) : UpdateResult()
}

class UpdateManager(private val context: Context) {

    companion object {
        private const val VERSION_URL =
            "https://github.com/12354/Nock/releases/latest/download/version.txt"
        private const val APK_URL =
            "https://github.com/12354/Nock/releases/latest/download/nock-latest.apk"
        private const val MAX_REDIRECTS = 5
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            info.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }
    }

    private fun openConnectionFollowingRedirects(urlString: String): HttpURLConnection {
        var url = URL(urlString)
        var redirects = 0
        while (true) {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false

            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                    ?: throw Exception("Redirect with no Location header (HTTP $code)")
                connection.disconnect()
                redirects++
                if (redirects > MAX_REDIRECTS) {
                    throw Exception("Too many redirects ($redirects)")
                }
                url = URL(url, location)
                continue
            }
            return connection
        }
    }

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val connection = openConnectionFollowingRedirects(VERSION_URL)
            try {
                val code = connection.responseCode
                if (code != 200) {
                    val body = try {
                        connection.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
                    } catch (_: Exception) { "" }
                    return@withContext UpdateResult.Failure(
                        "HTTP $code from version check. $body".trim()
                    )
                }
                val responseText = connection.inputStream.bufferedReader().readText().trim()
                val remoteVersion = responseText.toIntOrNull()
                    ?: return@withContext UpdateResult.Failure(
                        "Invalid version response: \"$responseText\""
                    )
                UpdateResult.Success(
                    UpdateInfo(
                        remoteVersionCode = remoteVersion,
                        currentVersionCode = getCurrentVersionCode()
                    )
                )
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            UpdateResult.Failure("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun downloadUpdate(onProgress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates")
            updateDir.mkdirs()
            val apkFile = File(updateDir, "nock-update.apk")
            if (apkFile.exists()) apkFile.delete()

            val connection = openConnectionFollowingRedirects(APK_URL)
            try {
                if (connection.responseCode != 200) {
                    return@withContext null
                }
                val totalBytes = connection.contentLength.toLong()
                var downloadedBytes = 0L

                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                onProgress(downloadedBytes.toFloat() / totalBytes)
                            }
                        }
                    }
                }

                // Never hand a partial or non-APK file to the installer: that is
                // itself a cause of the installer opening and instantly closing
                // ("problem parsing the package"). Verify the download is
                // complete and really is an APK (ZIP local-file-header magic)
                // before returning it.
                if (totalBytes > 0 && downloadedBytes != totalBytes) {
                    apkFile.delete()
                    return@withContext null
                }
                if (!isApkFile(apkFile)) {
                    apkFile.delete()
                    return@withContext null
                }
                onProgress(1f)
                apkFile
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * An APK is a ZIP archive, so a valid download must start with the ZIP
     * local-file-header magic ("PK"). This cheaply rejects HTML
     * error pages or truncated/empty files that would otherwise make the system
     * installer open and immediately dismiss.
     */
    private fun isApkFile(file: File): Boolean {
        if (file.length() < 4) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                if (input.read(header) != 4) return false
                header[0] == 0x50.toByte() && // 'P'
                    header[1] == 0x4B.toByte() && // 'K'
                    header[2] == 0x03.toByte() &&
                    header[3] == 0x04.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun installApk(apkFile: File) {
        // On Android 12+ install through PackageInstaller with
        // USER_ACTION_NOT_REQUIRED: a same-signer self-update applies silently —
        // no install confirmation and no per-install Play Protect scan prompt —
        // while Play Protect itself stays enabled. If that path can't run yet
        // (older OS, or it throws), fall back to the classic installer intent,
        // which still prompts and scans but always works.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && installSilently(apkFile)) return
        installViaIntent(apkFile)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun installSilently(apkFile: File): Boolean {
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("nock-update", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val statusIntent = Intent(context, UpdateInstallReceiver::class.java)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                session.commit(pending.intentSender)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun installViaIntent(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        // Launched from the application context, so guard against a launch
        // denial rather than letting it crash the caller's coroutine.
        runCatching { context.startActivity(intent) }
    }
}
