package app.nock.android.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
                onProgress(1f)
                apkFile
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
