package com.foxhole.beta.core.diagnostics

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeDiagnosticsDependencies

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
