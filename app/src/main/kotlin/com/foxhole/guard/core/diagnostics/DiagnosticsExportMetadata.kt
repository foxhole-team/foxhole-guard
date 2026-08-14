package com.foxhole.guard.core.diagnostics

import com.foxhole.core.model.RetentionPolicy

internal data class DiagnosticsExportMetadata(
    val generatedAt: Long,
    val appVersion: String,
    val versionCode: Int,
    val coreVersion: String,
    val androidRelease: String,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val themeMode: String,
    val locale: String,
    val trafficMode: String,
    val tunStack: String,
    val diagnosticsRetention: RetentionPolicy,
    val networkActivityLoggingEnabled: Boolean,
)
