package com.cvlvky.cloudroll.transfer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cvlvky.cloudroll.R
import com.cvlvky.cloudroll.backup.BackupEngine
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.util.Notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Keeps CloudRoll running while it backs up or saves files, with a progress notification,
 * so leaving the app does not stop a long upload or download.
 */
class TransferService : Service() {

    companion object {
        private const val EXTRA_BACKUP = "backup"
        private const val ACTION_STOP = "com.cvlvky.cloudroll.STOP_TRANSFERS"
        private const val ONGOING_ID = 7001
        private const val DONE_ID = 7002

        fun start(ctx: Context, backup: Boolean) {
            val intent = Intent(ctx, TransferService::class.java).putExtra(EXTRA_BACKUP, backup)
            try {
                ContextCompat.startForegroundService(ctx, intent)
            } catch (e: Exception) {
                // Android refuses foreground work from the background; the next app open retries.
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var backupJob: Job? = null
    private var saveJob: Job? = null
    private var notifyJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, ONGOING_ID, build("Getting ready…", "", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (intent?.action == ACTION_STOP) {
            BackupEngine.stop()
            SaveEngine.cancel()
            backupJob?.cancel()
            checkDone()
            return START_NOT_STICKY
        }
        Tg.init(applicationContext)
        if (intent?.getBooleanExtra(EXTRA_BACKUP, false) == true && backupJob?.isActive != true) {
            backupJob = scope.launch { BackupEngine.run(applicationContext, background = false) }
                .also { it.invokeOnCompletion { checkDone() } }
        }
        if (saveJob?.isActive != true && SaveEngine.hasWork()) {
            saveJob = scope.launch { SaveEngine.drain(applicationContext) }
                .also { it.invokeOnCompletion { checkDone() } }
        }
        if (notifyJob == null) {
            notifyJob = scope.launch {
                combine(BackupEngine.state, SaveEngine.state) { b, s -> b to s }.conflate().collect { (b, s) ->
                    val nm = getSystemService(NotificationManager::class.java)
                    when {
                        b.running && b.total > 0 -> nm?.notify(
                            ONGOING_ID,
                            build("Backing up ${b.done + 1} of ${b.total}", "${b.currentName} · ${b.currentPct}%", b.total, b.done),
                        )
                        b.running -> nm?.notify(ONGOING_ID, build("Backup", b.message, 0, 0))
                        s.running && s.total > 0 -> nm?.notify(
                            ONGOING_ID,
                            build("Saving ${minOf(s.done + 1, s.total)} of ${s.total} to your phone", "${s.currentName} · ${s.currentPct}%", s.total, s.done),
                        )
                    }
                    delay(800)
                }
            }
        }
        checkDone()
        return START_NOT_STICKY
    }

    private fun build(title: String, text: String, max: Int, progress: Int): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notify.TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_cloud)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(Notify.openApp(this))
            .setProgress(max, progress, max == 0)
            .addAction(0, "Stop", stop)
            .build()
    }

    @Synchronized
    private fun checkDone() {
        val busy = backupJob?.isActive == true || saveJob?.isActive == true || SaveEngine.hasWork()
        if (busy) return
        val b = BackupEngine.state.value
        val s = SaveEngine.state.value
        val summary = listOf(b.message, s.message).filter { it.isNotBlank() && it != "Starting" && it != "Getting ready…" }
        if (summary.isNotEmpty()) {
            val done = NotificationCompat.Builder(this, Notify.RESULTS)
                .setSmallIcon(R.drawable.ic_stat_cloud)
                .setContentTitle("CloudRoll")
                .setContentText(summary.joinToString(" "))
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary.joinToString("\n")))
                .setContentIntent(Notify.openApp(this))
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(DONE_ID, done)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
