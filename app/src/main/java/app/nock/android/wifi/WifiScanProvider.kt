package app.nock.android.wifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** One WiFi scan: BSSID → RSSI (dBm), plus how stale the results are. */
data class RoomScan(val levels: Map<String, Int>, val ageMs: Long)

/**
 * Thin battery-conscious wrapper around [WifiManager] for room fingerprinting.
 *
 * [cachedScan] only reads the results the system already has — it never
 * triggers radio work, so background checks are effectively free. [freshScan]
 * requests one real scan; Android throttles those hard (foreground apps: 4 per
 * 2 minutes, all background apps combined: 1 per 30 minutes), so a denied
 * request silently degrades to the cached results. Both return null without
 * location permission (the OS gates scan results behind it).
 */
@Singleton
class WifiScanProvider @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    private val wifi: WifiManager? = ctx.applicationContext.getSystemService()

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Needed for any detection while the app is not on screen — both the
     * periodic checks and the unlock check run in the background, where scan
     * results are empty without "allow all the time" location access.
     */
    fun hasBackgroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION") // isScanAlwaysAvailable: still the only "WiFi scanning" toggle probe
    fun isWifiScanningAvailable(): Boolean {
        val w = wifi ?: return false
        return w.isWifiEnabled || runCatching { w.isScanAlwaysAvailable }.getOrDefault(false)
    }

    fun cachedScan(): RoomScan? {
        val w = wifi ?: return null
        if (!hasLocationPermission()) return null
        val results = try {
            w.scanResults
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (results.isEmpty()) return null
        // ScanResult.timestamp is µs since boot; the newest entry dates the scan.
        val newestUs = results.maxOf { it.timestamp }
        val age = (SystemClock.elapsedRealtime() - newestUs / 1_000L).coerceAtLeast(0L)
        val levels = HashMap<String, Int>(results.size)
        for (r in results) {
            val bssid = r.BSSID ?: continue
            val prev = levels[bssid]
            if (prev == null || r.level > prev) levels[bssid] = r.level
        }
        return RoomScan(levels, age)
    }

    /**
     * Ask for a real scan and wait for the results broadcast, up to
     * [timeoutMs]. Falls back to [cachedScan] when the request is throttled or
     * the broadcast never arrives.
     */
    @Suppress("DEPRECATION") // startScan: deprecated but the only fingerprinting trigger
    suspend fun freshScan(timeoutMs: Long = 10_000L): RoomScan? {
        val w = wifi ?: return null
        if (!hasLocationPermission()) return null
        val started = try {
            w.startScan()
        } catch (_: SecurityException) {
            false
        }
        if (!started) return cachedScan()
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        runCatching { ctx.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                ContextCompat.registerReceiver(
                    ctx,
                    receiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
            }
        }
        // Either the broadcast arrived (results are now fresh) or we timed out
        // and the cache is the best we have — same read either way.
        return cachedScan()
    }
}
