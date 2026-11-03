package com.foxhole.guard.router

internal class RouterUiNoticeGate {
    private val causes = mutableMapOf<String, String>()
    fun claim(slot: String, key: String?): Boolean {
        if (key == null) { causes.remove(slot); return false }
        if (causes[slot] == key) return false
        causes[slot] = key
        return true
    }
}
