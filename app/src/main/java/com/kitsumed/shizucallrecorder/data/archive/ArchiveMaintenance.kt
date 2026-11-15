package com.kitsumed.shizucallrecorder.data.archive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.kitsumed.shizucallrecorder.MainActivity
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.shizuku.ShizukuConnectionManager
import com.kitsumed.shizucallrecorder.system.permissions.PermissionChecks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object ArchiveMaintenance {
    private const val CLEANUP_JOB_ID = 15002
    private const val REMINDER_ID = 15003
    private const val CHANNEL_ID = "recording_readiness"

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (RecordingArchive.retentionDays(context) == 0) {
            scheduler.cancel(CLEANUP_JOB_ID)
            return
        }
        if (scheduler.getPendingJob(CLEANUP_JOB_ID) != null) return
        val job = JobInfo.Builder(CLEANUP_JOB_ID, ComponentName(context, ArchiveCleanupJob::class.java))
            .setPeriodic(TimeUnit.DAYS.toMillis(1))
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    fun remindIfNeeded(context: Context) {
        val prefs = AppPreferences(context)
        if (!prefs.isDisclaimerAccepted() || prefs.getRecordingFolderUri() == null ||
            (!prefs.isAutoRecordIncomingEnabled() && !prefs.isAutoRecordOutgoingEnabled()) ||
            !RecordingArchive.remindOnBoot(context) || !PermissionChecks.hasNotificationPermission(context) ||
            ShizukuConnectionManager.isAvailable() ||
            (prefs.isShizukuAutoManageEnabled() && prefs.getShizukuAuthKey().isNotBlank())
        ) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.archive_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT
        ))
        val open = PendingIntent.getActivity(
            context, REMINDER_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(REMINDER_ID, Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.archive_shizuku_off))
            .setContentText(context.getString(R.string.archive_reminder_body))
            .setSmallIcon(R.drawable.recorder_test_icon)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true).build()
        )
    }

    fun dismissReminder(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(REMINDER_ID)
    }
}

class ArchiveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ArchiveMaintenance.schedule(app)
                delay(2_500)
                ArchiveMaintenance.remindIfNeeded(app)
            } finally {
                result.finish()
            }
        }
    }
}

class ArchiveCleanupJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cleanup: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        cleanup = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    runCatching { RecordingArchive.prune(applicationContext) { isActive } }
                }
            } finally {
                if (isActive) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cleanup?.cancel()
        cleanup = null
        return true
    }

    override fun onDestroy() {
        cleanup?.cancel()
        super.onDestroy()
    }
}
