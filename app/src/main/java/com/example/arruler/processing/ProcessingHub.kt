package com.example.arruler.processing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Result of the last PC ping, for the Settings screen and for routing. */
sealed interface PcStatus {
    data object Unknown : PcStatus
    data object Checking : PcStatus
    data class Ok(val info: ServerInfo, val roundTripMs: Long, val atMs: Long) : PcStatus
    data class Failed(val message: String, val atMs: Long) : PcStatus
}

/**
 * The app's one place for the processing settings: the user preference, the pairing, the last ping, the
 * device profile, and the factory for [ProcessingService]. Thin Android glue over the pure pieces
 * ([Router], [PairingInfo], [UrlPolicy]); not unit-tested.
 */
class ProcessingHub(
    context: Context,
    private val scope: CoroutineScope,
    private val store: PairingStore = AndroidPairingStore(context),
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("processing", Context.MODE_PRIVATE)

    private val _pref = MutableStateFlow(
        runCatching { UserPref.valueOf(prefs.getString("pref", null) ?: "AUTO") }.getOrDefault(UserPref.AUTO)
    )
    val pref: StateFlow<UserPref> = _pref.asStateFlow()

    /** Paired and the last ping succeeded (a ping in flight keeps the previous answer). */
    @Volatile private var reachable = false

    private val _pairing = MutableStateFlow<PairingInfo?>(null)
    val pairing: StateFlow<PairingInfo?> = _pairing.asStateFlow()

    private val _status = MutableStateFlow<PcStatus>(PcStatus.Unknown)
    val status: StateFlow<PcStatus> = _status.asStateFlow()

    private val _profile = MutableStateFlow(DeviceProfile.read(app))
    val profile: StateFlow<DeviceProfile> = _profile.asStateFlow()

    private val _speedTesting = MutableStateFlow(false)
    val speedTesting: StateFlow<Boolean> = _speedTesting.asStateFlow()

    init {
        // result ZIPs of finished jobs older than a day are scratch
        val cutoff = System.currentTimeMillis() - 24L * 3600_000
        workDir().listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
        _pairing.value =runCatching { store.load() }.getOrNull()
        if (_pairing.value != null) testConnection()
    }

    fun setPref(p: UserPref) {
        _pref.value = p
        prefs.edit().putString("pref", p.name).apply()
    }

    /** Parses the QR / pasted JSON, stores it (token encrypted) and pings. Failure carries a user-readable message. */
    fun pair(text: String): Result<PairingInfo> {
        val info = PairingInfo.parse(text).getOrElse { return Result.failure(it) }
        try {
            store.save(info)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        _pairing.value = info
        testConnection()
        return Result.success(info)
    }

    fun unpair() {
        store.clear()
        _pairing.value = null
        reachable = false
        _status.value = PcStatus.Unknown
    }

    /** Pings the paired PC and publishes the outcome in [status]. */
    fun testConnection() {
        val info = _pairing.value ?: return
        _status.value = PcStatus.Checking
        scope.launch(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            _status.value = try {
                val s = HttpPcLink(info).ping()
                reachable = true
                PcStatus.Ok(s, SystemClock.elapsedRealtime() - t0, System.currentTimeMillis())
            } catch (e: PcLinkException) {
                reachable = false
                PcStatus.Failed(
                    when {
                        e.network -> "Cannot reach the PC (${e.message})"
                        e.httpStatus == 401 -> "The PC rejected the token; pair again"
                        else -> e.message ?: "ping failed"
                    }, System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                reachable = false
                PcStatus.Failed(e.message ?: "ping failed", System.currentTimeMillis())
            }
        }
    }

    /** Re-measures the 2 s benchmark and re-derives the tier (Settings: Run speed test). */
    fun runSpeedTest() {
        if (_speedTesting.value) return
        _speedTesting.value = true
        scope.launch {
            try {
                _profile.value = withContext(Dispatchers.Default) {
                    DeviceProfile.forgetBenchmark(app)
                    DeviceProfile.read(app, useBenchmark = true)
                }
            } finally {
                _speedTesting.value = false
            }
        }
    }

    val pcAvailable: Boolean get() = _pairing.value != null && reachable

    fun onWifi(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** The picker rows for the current tier, PC state and connection. */
    fun qualities(estimate: JobEstimate): List<QualityOption> =
        Router.availableQualities(DeviceProfile.read(app).tier, pcAvailable, onWifi(), estimate)

    /** Where a job would run right now ([prefOverride] replaces the Settings preference for this one job). */
    fun route(type: JobType, quality: ObjectQuality?, estimate: JobEstimate, prefOverride: UserPref? = null): RouteDecision {
        val s = signals(prefOverride)
        return Router.decide(type, quality, estimate, s.tier, s.pcAvailable, s.userPref, s.onWifi, s.batteryLow, s.thermal)
    }

    private fun signals(prefOverride: UserPref?): RouteSignals {
        val p = DeviceProfile.read(app)
        return RouteSignals(p.tier, pcAvailable, prefOverride ?: _pref.value, onWifi(), p.batteryLow, p.thermalStatus)
    }

    /** A service bound to the current pairing (or phone-only without one). */
    fun service(prefOverride: UserPref? = null): ProcessingService {
        val link = _pairing.value?.let { HttpPcLink(it) }
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        return ProcessingService(
            signals = { signals(prefOverride) },
            pc = link,
            packager = DefaultJobPackager(version, { DeviceProfile.read(app).summary() }),
        )
    }

    /** Scratch directory for job and result ZIPs. */
    fun workDir(): File = File(app.cacheDir, "jobs").apply { mkdirs() }
}
