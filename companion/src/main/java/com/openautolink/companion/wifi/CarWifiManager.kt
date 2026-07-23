package com.openautolink.companion.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.openautolink.companion.diagnostics.CompanionLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages WiFi connectivity to the car's hotspot using a two-layer approach:
 *
 * 1. **WifiNetworkSuggestion**: Persistent background preference — Android
 *    auto-connects on future scans without any UI.
 *
 * 2. **WifiNetworkSpecifier** + **requestNetwork**: Forces an *immediate*
 *    connection attempt so the car can TCP-connect within seconds of BT
 *    pairing, without waiting for the OS to action the suggestion.
 *    The callback is **unregistered immediately after onAvailable** so the
 *    "Searching for device / Stay connected?" system dialog dismisses as soon
 *    as the network is found. If onUnavailable fires (SSID not in range yet),
 *    we retry after a short delay.
 *
 * Retry strategy: each requestNetwork() scans ~30s. On failure, wait 5s
 * and retry, up to [MAX_ATTEMPTS] times.
 */
class CarWifiManager(private val context: Context) {

    sealed class State {
        data object Idle : State()
        data class Scanning(val attempt: Int, val maxAttempts: Int) : State()
        data class Connected(val ssid: String) : State()
        data class Failed(val reason: String) : State()
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var entries: List<CarWifiEntry> = emptyList()
    private var currentCallback: ConnectivityManager.NetworkCallback? = null
    private var attempt = 0
    private var running = false
    private var retryRunnable: Runnable? = null
    private var rearmRunnable: Runnable? = null
    private var activeSuggestions: List<WifiNetworkSuggestion> = emptyList()
    // 5 GHz band pinning: the specifier otherwise let the phone pick and it kept
    // landing on 2.4 GHz (laggy, and it collides with the BT the AA handshake
    // uses). Pin 5 GHz when the AP is seen there; disable for the session if a
    // pinned request fails, so a 2.4-GHz-only AP still connects.
    private var bandPinDisabled = false
    private var lastAttemptPinned5 = false

    fun start(carWifiEntries: List<CarWifiEntry>) {
        if (carWifiEntries.isEmpty()) {
            CompanionLog.w(TAG, "No car WiFi entries configured, skipping")
            return
        }
        entries = carWifiEntries
        running = true
        attempt = 0
        bandPinDisabled = false
        CompanionLog.i(TAG, "Starting car WiFi manager for ${entries.size} SSID(s)")
        registerSuggestions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tryConnect()
    }

    fun stop() {
        running = false
        cancelRetry()
        cancelRearm()
        releaseCallback()
        removeSuggestions()
        _state.value = State.Idle
        CompanionLog.i(TAG, "Stopped")
    }

    private fun registerSuggestions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val suggestions = entries.map { entry ->
                val builder = WifiNetworkSuggestion.Builder()
                    .setSsid(entry.ssid)
                    .setWpa2Passphrase(entry.password)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setIsInitialAutojoinEnabled(true)
                }
                builder.build()
            }
            if (activeSuggestions.isNotEmpty()) {
                wifiManager.removeNetworkSuggestions(activeSuggestions)
            }
            val status = wifiManager.addNetworkSuggestions(suggestions)
            activeSuggestions = if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                CompanionLog.i(TAG, "Registered ${suggestions.size} WiFi suggestion(s) for auto-connect")
                suggestions
            } else {
                CompanionLog.w(TAG, "WifiNetworkSuggestion registration failed (status=$status) — specifier-only mode")
                emptyList()
            }
        } catch (e: Exception) {
            CompanionLog.w(TAG, "WifiNetworkSuggestion error: ${e.message}")
        }
    }

    private fun removeSuggestions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (activeSuggestions.isEmpty()) return
        try { wifiManager.removeNetworkSuggestions(activeSuggestions) } catch (_: Exception) {}
        CompanionLog.d(TAG, "Removed ${activeSuggestions.size} WiFi suggestion(s)")
        activeSuggestions = emptyList()
    }

    @SuppressLint("NewApi")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun tryConnect() {
        if (!running) return
        if (attempt >= MAX_ATTEMPTS) {
            val msg = "Gave up after $MAX_ATTEMPTS attempts"
            CompanionLog.w(TAG, msg)
            _state.value = State.Failed(msg)
            // Don't stop dead — the car AP may simply be out of range for now
            // (hill, parking structure, AP reboot, RF null). Schedule a slow
            // background re-arm that resets the counter and resumes the fast
            // burst, so any outage is bounded by REARM_DELAY_MS instead of
            // lasting the whole session (issue #31). The re-arm is a no-op once
            // we're connected (onAvailable cancels it).
            scheduleRearm()
            return
        }

        attempt++
        _state.value = State.Scanning(attempt, MAX_ATTEMPTS)

        val entry = entries.first()
        CompanionLog.i(TAG, "Attempt $attempt/$MAX_ATTEMPTS: requesting \"${entry.ssid}\"")

        releaseCallback()

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(entry.ssid)
            .setWpa2Passphrase(entry.password)
        // Pin 5 GHz when the car AP advertises this SSID there (per the last
        // scan), to avoid the laggy 2.4 GHz association. Falls back to any band
        // if 5 GHz isn't seen or a prior pinned attempt failed, so a 2.4-GHz-only
        // AP still connects.
        val pin5 = !bandPinDisabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ssidSeenOn5GHz(entry.ssid)
        if (pin5) {
            specifierBuilder.setBand(android.net.wifi.ScanResult.WIFI_BAND_5_GHZ)
            CompanionLog.i(TAG, "Pinning \"${entry.ssid}\" to 5GHz")
        }
        lastAttemptPinned5 = pin5
        val specifier = specifierBuilder.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!running) return
                CompanionLog.i(TAG, "Connected to \"${entry.ssid}\" on attempt $attempt")
                _state.value = State.Connected(entry.ssid)
                attempt = 0
                cancelRearm()
                // Log the association quality (band / link rate / rssi). A poor
                // initial association — 2.4 GHz or a low MCS rate — is the leading
                // suspect for the "low bitrate & laggy until I manually rejoin"
                // symptom (a fresh requestNetwork, i.e. the Reconnect button, lands
                // a better one). Sample now (band is fixed at association) and again
                // after the rate settles, so a shared log shows both.
                logLinkQuality(network, "on connect")
                handler.postDelayed({ if (running) logLinkQuality(network, "+10s") }, 10_000L)
                // Keep the callback registered — unregistering here would tear down
                // the secondary WiFi network, removing the phone's IP on the car's
                // subnet and making the car unable to reach our server ports.
                // The callback is released in stop().
            }

            override fun onUnavailable() {
                if (!running) return
                if (lastAttemptPinned5 && !bandPinDisabled) {
                    // 5 GHz-pinned request failed — fall back to any band for the
                    // rest of this session so we don't get stuck off-band.
                    bandPinDisabled = true
                    CompanionLog.w(TAG, "5GHz-pinned request failed — falling back to any band")
                }
                CompanionLog.w(TAG, "Attempt $attempt failed (SSID not in range yet)")
                scheduleRetry()
            }

            override fun onLost(network: Network) {
                if (!running) return
                CompanionLog.w(TAG, "Car WiFi \"${entry.ssid}\" lost")
                // A *lost* link is a fresh recovery, not a continuation of the
                // initial scan budget — reset so we get a full burst again
                // rather than counting the loss against a possibly-depleted
                // attempt counter (issue #31).
                attempt = 0
                _state.value = State.Scanning(attempt, MAX_ATTEMPTS)
                scheduleRetry(LOST_RETRY_DELAY_MS)
            }
        }

        currentCallback = callback
        connectivityManager.requestNetwork(request, callback)
    }

    private fun scheduleRetry(delayMs: Long = RETRY_DELAY_MS) {
        cancelRetry()
        val r = Runnable { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tryConnect() }
        retryRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun cancelRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
    }

    /**
     * After the fast burst gives up, periodically reset the counter and resume
     * scanning so a longer car-AP outage recovers on its own. Bounds the dead
     * window to [REARM_DELAY_MS] instead of lasting the whole session (#31).
     */
    private fun scheduleRearm() {
        cancelRearm()
        val r = Runnable {
            if (!running) return@Runnable
            // Only resume if we're not already connected. onAvailable cancels
            // the re-arm, but guard against a race where it fired just before.
            if (_state.value is State.Connected) return@Runnable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                CompanionLog.i(TAG, "Re-arming car WiFi scan after give-up")
                attempt = 0
                tryConnect()
            }
        }
        rearmRunnable = r
        handler.postDelayed(r, REARM_DELAY_MS)
    }

    private fun cancelRearm() {
        rearmRunnable?.let { handler.removeCallbacks(it) }
        rearmRunnable = null
    }

    /**
     * Log the car-link band / rate / signal so a shared companion log reveals
     * whether a bad initial connect is a 2.4 GHz vs 5 GHz band problem, a low
     * link rate (RF/association), or fine (pointing the lag elsewhere). Reads
     * the WifiInfo for THIS requested network via NetworkCapabilities, falling
     * back to the primary connection info. Best-effort — never throws.
     */
    private fun logLinkQuality(network: Network, whenLabel: String) {
        try {
            val caps = connectivityManager.getNetworkCapabilities(network)
            val info = (caps?.transportInfo as? android.net.wifi.WifiInfo)
                ?: @Suppress("DEPRECATION") wifiManager.connectionInfo
            if (info == null) {
                CompanionLog.w(TAG, "Link ($whenLabel): no WifiInfo available")
                return
            }
            val freq = info.frequency
            val band = when {
                freq >= 5925 -> "6GHz"
                freq >= 4900 -> "5GHz"
                freq in 2300..2600 -> "2.4GHz"
                else -> "?"
            }
            CompanionLog.i(
                TAG,
                "Link ($whenLabel): $band freq=${freq}MHz " +
                    "linkSpeed=${info.linkSpeed}Mbps rssi=${info.rssi}dBm",
            )
        } catch (e: Exception) {
            CompanionLog.w(TAG, "Link probe ($whenLabel) failed: ${e.message}")
        }
    }

    /**
     * True if the last WiFi scan saw [ssid] on a 5 GHz channel. Lets us pin the
     * band only when the car AP actually offers 5 GHz (avoids stalling a
     * 2.4-GHz-only AP). Reads cached scanResults (needs location permission,
     * which the app holds); best-effort — returns false on any failure.
     */
    @SuppressLint("MissingPermission")
    private fun ssidSeenOn5GHz(ssid: String): Boolean {
        return try {
            val matches = (wifiManager.scanResults ?: emptyList()).filter { it.SSID == ssid }
            val has5 = matches.any { it.frequency >= 4900 }
            if (matches.isNotEmpty()) {
                CompanionLog.i(
                    TAG,
                    "Scan: \"$ssid\" seen on freqs=${matches.map { it.frequency }.sorted()} (5GHz=$has5)",
                )
            }
            has5
        } catch (e: Exception) {
            CompanionLog.w(TAG, "5GHz scan check failed: ${e.message}")
            false
        }
    }

    private fun releaseCallback() {
        currentCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        currentCallback = null
    }

    companion object {
        private const val TAG = "OAL_CarWifi"
        private const val MAX_ATTEMPTS = 12
        private const val RETRY_DELAY_MS = 5_000L
        private const val LOST_RETRY_DELAY_MS = 2_000L
        // After the fast 12x burst gives up, re-arm a fresh burst this often so
        // a longer car-AP outage still recovers without a user restart (#31).
        private const val REARM_DELAY_MS = 30_000L
    }
}

/**
 * A car WiFi network entry (SSID + password).
 */
data class CarWifiEntry(val ssid: String, val password: String) {
    /** Serialize to prefs format: "ssid\tpassword" */
    fun toPrefsString(): String = "$ssid\t$password"

    companion object {
        /** Parse from prefs format: "ssid\tpassword" */
        fun fromPrefsString(s: String): CarWifiEntry? {
            val parts = s.split('\t', limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) return null
            return CarWifiEntry(parts[0], parts[1])
        }

        /** Load all entries from SharedPreferences */
        fun loadAll(prefs: android.content.SharedPreferences): List<CarWifiEntry> {
            val raw = prefs.getStringSet(
                com.openautolink.companion.CompanionPrefs.CAR_WIFI_ENTRIES,
                emptySet()
            ) ?: emptySet()
            return raw.mapNotNull { fromPrefsString(it) }
        }

        /** Save all entries to SharedPreferences */
        fun saveAll(prefs: android.content.SharedPreferences, entries: List<CarWifiEntry>) {
            prefs.edit()
                .putStringSet(
                    com.openautolink.companion.CompanionPrefs.CAR_WIFI_ENTRIES,
                    entries.map { it.toPrefsString() }.toSet()
                )
                .apply()
        }
    }
}
