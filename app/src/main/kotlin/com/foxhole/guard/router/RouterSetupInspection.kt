package com.foxhole.guard.router

internal interface RouterSetupInspection : AutoCloseable {
    val address: String
    val system: RouterSystemInspection
    val catalogSha256: String
    val manifestSha256: String
    fun requireCurrent()
}
