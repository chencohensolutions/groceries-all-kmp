package com.hencohen.grocery.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hencohen.grocery.domain.ChainSyncResult
import java.util.concurrent.TimeUnit

/** Thin Android wrapper: scheduling lives here, the sync itself is shared Kotlin. */
class PriceSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val core = (applicationContext as GroceryApp).core
        val results = core.syncPrices(force = inputData.getBoolean(KEY_FORCE, false))
        val output = workDataOf(KEY_SUMMARY to results.joinToString("\n") { it.describe() })

        val allFailed = results.isNotEmpty() && results.all { it is ChainSyncResult.Failed }
        return when {
            !allFailed -> Result.success(output)
            runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> Result.failure(output)
        }
    }

    companion object {
        const val PERIODIC_WORK = "price-sync-weekly"
        const val MANUAL_WORK = "price-sync-now"
        const val KEY_FORCE = "force"
        const val KEY_SUMMARY = "summary"
        private const val MAX_RETRIES = 3

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<PriceSyncWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<PriceSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_FORCE to force))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.REPLACE, request)
        }

        private fun ChainSyncResult.describe(): String = when (this) {
            is ChainSyncResult.Updated -> "${chain.displayName}: עודכנו $itemCount מוצרים"
            is ChainSyncResult.UpToDate -> "${chain.displayName}: המחירים עדכניים"
            is ChainSyncResult.Failed -> "${chain.displayName}: שגיאה – ${error.message ?: error::class.simpleName}"
        }
    }
}
