package com.foxhole.guard

private object ExternalFdroidTestFixture {}

internal fun externalFdroidBinariesFixture(): String {
    val source = requireNotNull(ExternalFdroidTestFixture::class.java.getResourceAsStream(
        "/fdroid/synthetic-external-binaries.yml",
    ))
    val text = source.bufferedReader().use { it.readText() }
    check(text.startsWith("# Synthetic external Binaries contract fixture;"))
    return text
}
