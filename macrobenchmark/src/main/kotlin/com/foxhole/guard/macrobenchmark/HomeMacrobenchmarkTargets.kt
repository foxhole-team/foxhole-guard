package com.foxhole.guard.macrobenchmark

data class SettingsDetailTarget(
    val tag: String,
    val detailTag: String,
    val labels: List<String>,
    val tapYRatio: Float? = null,
    val optional: Boolean = false,
    val traceSections: List<String> = emptyList(),
)
