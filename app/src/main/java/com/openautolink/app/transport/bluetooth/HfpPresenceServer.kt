package com.openautolink.app.transport.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Publishes an RFCOMM service record on the standard Hands-Free Profile UUID.
 *
 * Why: some phones gate the Wireless Android Auto pairing flow on seeing an
 * HFP-advertising device in their BT scan results. The shipped AAOS BT stack
 * may or may not publish HFP HEADSET (server role) — when it doesn't, the
 * phone refuses to start AA wireless setup even though everything else is
 * fine. headunit-revived hit the same wall and worked around it by accepting
 * an HFP RFCOMM connection from a normal app and closing it immediately;
 * the phone treats this as "HFP present" for discovery purposes.
 *
 * What this is NOT: an HFP Audio Gateway. We do not speak AT commands, we do
 * not accept SCO, and we do not carry call audio. From a non-platform-signed
 * AAOS app the BT SCO socket is gated by BLUETOOTH_PRIVILEGED / selinux
 * `bluetooth_socket` context — call audio over BT is unreachable. This class
 * is strictly a presence advertisement.
 *
 * Side effect we'd like to confirm: it's not impossible that with both the
 * SDR `bluetooth_service` block populated AND an actual HFP RFCOMM listener
 * online, phone-side AA negotiates differently. Cheap to ship, easy to
 * disable, falsifiable by logs.
 */
class HfpPresenceServer(
    private val context: Context,
    parentScope: CoroutineScope,
) {
    private val TAG = "HfpPresence"
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob() + Dispatchers.IO)
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            OalLog.w(TAG, "BLUETOOTH_CONNECT not granted — skipping HFP presence advertisement")
            return
        }
        running = true
        acceptJob = scope.launch { acceptLoop() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun acceptLoop() {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            OalLog.w(TAG, "No BT adapter or adapter disabled — HFP presence not started")
            running = false
            return
        }
        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
            OalLog.i(TAG, "HFP presence RFCOMM listener up (uuid=$HFP_UUID)")
        } catch (e: Exception) {
            OalLog.w(TAG, "listenUsingRfcommWithServiceRecord failed: ${e.message}")
            running = false
            return
        }

        var consecutiveFailures = 0
        while (running && scope.isActive) {
            var threw = false
            val client: BluetoothSocket? = try {
                serverSocket?.accept()
            } catch (e: Exception) {
                // Log only the first failure; the loop abandons the socket after a
                // few tries rather than spamming 'accept() failed' once/sec.
                if (running && consecutiveFailures == 0) OalLog.w(TAG, "HFP accept() failed: ${e.message}")
                threw = true
                null
            }
            if (client != null) {
                consecutiveFailures = 0
                val remote = try { client.remoteDevice?.address ?: "?" } catch (_: Throwable) { "?" }
                OalLog.i(TAG, "HFP presence connection from $remote — closing (presence-only)")
                try { client.close() } catch (_: Throwable) {}
                continue
            }
            if (serverSocket == null) {
                if (running) OalLog.w(TAG, "HFP server socket closed — exiting accept loop")
                break
            }
            if (threw) {
                consecutiveFailures++
                // Some AAOS BT stacks won't keep an app RFCOMM server socket alive,
                // so accept() fails immediately and forever. Abandon after a few
                // tries instead of retrying a dead socket once/sec and flooding the
                // log — this is a best-effort presence hint, not required for AA.
                if (consecutiveFailures >= 5) {
                    OalLog.w(TAG, "HFP accept() failed ${consecutiveFailures}x — abandoning presence advertisement (best-effort only, harmless)")
                    break
                }
                try { kotlinx.coroutines.delay(1000) } catch (_: Throwable) { break }
            }
        }
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        running = false
    }

    fun stop() {
        if (!running) return
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
        OalLog.i(TAG, "HFP presence listener stopped")
    }

    companion object {
        // Hands-Free Profile (Hands-Free unit role) UUID from the Bluetooth SIG.
        private val HFP_UUID: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
    }
}
