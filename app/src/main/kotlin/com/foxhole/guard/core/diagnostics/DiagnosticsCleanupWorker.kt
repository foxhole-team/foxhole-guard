package com.foxhole.guard.core.diagnostics

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeDiagnosticsDependencies

class DiagnosticsCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies: FoxholeDiagnosticsDependencies = (applicationContext as FoxholeApplication).appGraph
        dependencies.diagnosticsLogger.cleanupExpiredExports()
        return Result.success()
    }
}
