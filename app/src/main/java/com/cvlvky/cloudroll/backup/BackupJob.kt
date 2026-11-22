package com.cvlvky.cloudroll.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.tg.Tg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Auto backup in the background: every few hours, and soon after a new photo is taken. */
class BackupJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId == BackupScheduler.CONTENT_ID) BackupScheduler.scheduleContentTrigger(applicationContext)
        if (!Prefs.backupEnabled.value) return false
        running = scope.launch {
            try {
                Tg.init(applicationContext)
                if (Tg.awaitReady(60_000)) {
                    // Jobs get about ten minutes; stop between files well before that.
                    BackupEngine.run(applicationContext, background = true, deadline = System.currentTimeMillis() + 8 * 60_000L)
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        BackupEngine.stop()
        running?.cancel()
        return true
    }
}

object BackupScheduler {
    const val PERIODIC_ID = 4101
    const val CONTENT_ID = 4102

    private fun scheduler(ctx: Context) = ctx.getSystemService(JobScheduler::class.java)

    private fun builder(ctx: Context, id: Int): JobInfo.Builder =
        JobInfo.Builder(id, ComponentName(ctx, BackupJob::class.java))
            .setRequiredNetworkType(if (Prefs.backupWifiOnly.value) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(Prefs.backupChargingOnly.value)

    /** Turns the background jobs on or off to match the Backup settings. */
    fun update(ctx: Context) {
        val js = scheduler(ctx) ?: return
        if (!Prefs.backupEnabled.value || Prefs.apiKeys() == null) {
            js.cancel(PERIODIC_ID)
            js.cancel(CONTENT_ID)
            return
        }
        try {
            js.schedule(builder(ctx, PERIODIC_ID).setPeriodic(3 * 60 * 60 * 1000L).setPersisted(true).build())
        } catch (e: Exception) {
            // A phone that refuses the job keeps the manual backup.
        }
        scheduleContentTrigger(ctx)
    }

    /** Wakes the backup a minute after the camera saves something new. */
    fun scheduleContentTrigger(ctx: Context) {
        if (!Prefs.backupEnabled.value) return
        val js = scheduler(ctx) ?: return
        try {
            val job = builder(ctx, CONTENT_ID)
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(20_000)
                .setTriggerContentMaxDelay(120_000)
                .build()
            js.schedule(job)
        } catch (e: Exception) {
            // Content triggers are optional; the periodic job still runs.
        }
    }
}
