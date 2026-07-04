package dev.warsha.remoteble.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service keeping the agent's process alive (and foreground-priority) while the
 * app is backgrounded — Android otherwise suspends/kills background sockets and BLE work.
 * Owns no BLE/server logic itself: that's [AgentRunner], owned per-`AgentViewModel` and handed
 * to this service via [start]. Rather than relying on the host Activity/Compose UI to keep this
 * service in lockstep with [AgentRunner.running] (fragile — the composition can be torn down,
 * e.g. on task removal, without running its "stop" branch), this service *observes*
 * [AgentRunner.running] itself once started and calls [stopSelf] the moment it flips to `false`,
 * so it can never outlive the agent it represents. [onTaskRemoved] provides a second, independent
 * backstop for the case where the whole task (and its runner) is swiped away.
 *
 * Foregrounding is wrapped in a try/catch: starting a `CONNECTED_DEVICE` foreground service
 * requires a qualifying Bluetooth permission on API 34+, and while [MainActivity] gates Start on
 * that permission, a mid-session revocation could still reach here — in which case we bail cleanly
 * (having called `startForeground`, so the `startForegroundService` contract is honoured) instead
 * of crashing.
 */
class AgentService : Service() {

    private var scope: CoroutineScope? = null

    // Captured from the companion handoff in onStartCommand; [myGeneration] lets a superseded
    // instance's onDestroy avoid nulling a newer instance's handoff (see the companion).
    private var runner: AgentRunner? = null
    private var myGeneration = 0L
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Claim the handoff for this instance up front: a later start() bumps `generation`, so a
        // superseded instance's onDestroy won't null the ref a newer instance is relying on.
        runner = runnerRef
        myGeneration = generation

        val foregrounded = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            true
        } catch (e: Exception) {
            // Couldn't enter the foreground — e.g. the connectedDevice type's BLUETOOTH_CONNECT
            // grant was revoked mid-session (SecurityException on API 34+). startForeground() *was*
            // called, satisfying the startForegroundService() contract, and the stopSelf() below
            // ends the service before the "did not start in time" deadline — so this is a clean
            // bail-out, not a crash. (MainActivity already gates Start on the permission, so this
            // path is defensive.)
            false
        }
        if (!foregrounded) {
            stopSelf()
            return START_NOT_STICKY
        }

        observeRunner()
        // This service cannot meaningfully resume itself after a process kill — the
        // AgentRunner/AgentViewModel it depends on is gone with the process — so there is no
        // "sticky" state worth restoring.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val runner = this.runner
        if (runner != null) {
            scope?.launch { runCatchingNonCancellation { runner.stop() } }
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        runner = null
        // Only relinquish the shared handoff if a newer start() hasn't already claimed it (both
        // instances point at the app's single AgentRunner, so identity alone can't tell them
        // apart — the generation can). Otherwise a superseded instance's teardown would starve the
        // live instance's observer of its runner, leaving a stale notification behind.
        if (generation == myGeneration) runnerRef = null
        super.onDestroy()
    }

    private fun observeRunner() {
        // onStartCommand can fire more than once on a live instance (e.g. the Activity re-issues
        // start() after a rotation), so guard against stacking duplicate collectors.
        if (observing) return
        val runner = runner ?: return
        observing = true
        scope?.launch {
            runner.running.collect { running -> if (!running) stopSelf() }
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RemoteBLE Agent", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RemoteBLE Agent running")
            .setContentText("Serving this device's Bluetooth radio to remote clients")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "remote_ble_agent"
        private const val NOTIFICATION_ID = 1

        // Set just before starting the service so the freshly-created instance can observe it; a
        // Service has no constructor args, so a static handoff is the only option. `generation`
        // is bumped on every start() so a superseded instance's onDestroy can tell it no longer
        // owns the ref and must not null it (see onDestroy). All touched on the main thread only.
        private var runnerRef: AgentRunner? = null
        private var generation = 0L

        fun start(context: Context, runner: AgentRunner) {
            runnerRef = runner
            generation++
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
