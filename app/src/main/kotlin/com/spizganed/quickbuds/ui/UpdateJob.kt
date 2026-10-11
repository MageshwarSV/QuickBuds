package com.spizganed.quickbuds.ui

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.spizganed.quickbuds.bluetooth.PacketLogger

/**
 * The background update check, run by Android about once an hour with a network: also with the app closed, the
 * background service stopped, after a reboot (persisted) and after an update. Was: a timer in BudsService, which
 * did not run once Android stopped the service.
 */
class UpdateJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        UpdateChecker.backgroundCheck(applicationContext) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 7101
        private const val PERIOD_MS = 60 * 60_000L

        /** Schedules the hourly check, or cancels it when "Check for updates" is off. A scheduled job is kept. */
        fun sync(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (!UpdateChecker.autoEnabled(context)) return js.cancel(JOB_ID)
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(job) }.onFailure { PacketLogger.error("update job", it) }
        }
    }
}
