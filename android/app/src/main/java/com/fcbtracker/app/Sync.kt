package com.fcbtracker.app

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fcbtracker.core.Repository
import com.fcbtracker.core.Snapshot
import com.fcbtracker.widget.FcbWidget
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

object Data {
    @Volatile private var repo: Repository? = null
    fun repo(context: Context): Repository =
        repo ?: synchronized(this) { repo ?: Repository(File(context.applicationContext.filesDir, "fcb")).also { repo = it } }
}

/**
 * When the phone refreshes, with no server involved:
 * - every 30 minutes (Android may stretch this while the phone is idle);
 * - about every minute while a Barcelona match is in its live window (75 minutes before kick-off
 *   until it ends), and once more when it finishes.
 */
object Sync {
    private const val PERIODIC = "fcb-refresh"
    private const val LIVE = "fcb-live"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun start(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES).setConstraints(online).build(),
        )
        plan(context, Data.repo(context).load())
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(LIVE)
    }

    /** Schedule the next live check: in a minute during a match, or at the next live window's start. */
    fun plan(context: Context, s: Snapshot?, now: Instant = Instant.now()) {
        val repo = Data.repo(context)
        val delay = when {
            repo.inLiveWindow(s, now).isNotEmpty() -> Duration.ofSeconds(60)
            else -> s?.next?.let { Duration.between(now, it.kickoff - Repository.LIVE_BEFORE) }
                ?.takeIf { !it.isNegative && it < Duration.ofDays(2) }
        } ?: return
        WorkManager.getInstance(context).enqueueUniqueWork(
            LIVE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LiveWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).setConstraints(online).build(),
        )
    }

    suspend fun refreshWidgets(context: Context) = FcbWidget().updateAll(context)
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = runCatching { Data.repo(applicationContext).refresh() }.getOrNull()
        Sync.refreshWidgets(applicationContext)
        Sync.plan(applicationContext, s ?: Data.repo(applicationContext).load())
        return if (s != null) Result.success() else Result.retry()
    }
}

class LiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = Data.repo(applicationContext)
        val before = repo.load()
        var s = runCatching { repo.refreshLive() }.getOrNull() ?: before
        // Kick-off passed with the schedule still saying "pre", or the match just ended: full refresh.
        if (before == null || repo.inLiveWindow(s).isEmpty() || s?.matches?.any { it.status.completed && before.matches.find { b -> b.id == it.id }?.status?.completed == false } == true) {
            s = runCatching { repo.refresh() }.getOrNull() ?: s
        }
        Sync.refreshWidgets(applicationContext)
        Sync.plan(applicationContext, s)
        return Result.success()
    }
}
