package com.foxhole.guard.router

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class RouterPortableProfileTest {
    private val uri = "vless://00000000-0000-0000-0000-000000000001@1.1.1.1:443?security=tls&sni=example.com#Home"
    private fun profile(content: String = uri, hash: String = digest(content)) = buildJsonObject {
        put("engine", "core"); put("config", "private native config")
        put("portableProfile", buildJsonObject { put("content", content); put("sha256", hash) })
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun aCoreProfileCopiesItsExactAdmittedOriginalInsteadOfTheNativeNormalization() = runTest {
        assertEquals(uri, routerWholeShareContent(profile()))
    }

    @Test fun subscriptionSourceTakesPriorityAndRetainsItsWholeOriginalUrl() = runTest {
        val source = "ssconf://subscription.example.com/key%2Ftoken#Home"
        val value = buildJsonObject {
            put("engine", "core"); put("portableProfile", JsonNull)
            put("subscription", buildJsonObject { put("sourceUrl", source) })
        }
        assertEquals(source, routerWholeShareContent(value))
    }

    @Test fun absentAlteredOrUnsupportedOriginalHasAnExplicitCopyFailure() = runTest {
        for (value in listOf(profile(hash = "a".repeat(64)),
            buildJsonObject { put("engine", "core"); put("portableProfile", JsonNull) },
            profile(content = """{"unknown_policy":"must not be omitted"}"""))) {
            try { routerWholeShareContent(value); error("unsupported original copied") }
            catch (failure: RouterSetupFailure) { assertEquals(RouterWizardError.PROFILE_EXPORT, failure.kind) }
        }
    }

    @Test fun copyingAnInsecureLinkDoesNotRemoveTheConsentFlagOrImportIt() = runTest {
        val insecure = uri.replace("security=tls", "security=tls&allowInsecure=1")
        assertEquals(insecure, routerWholeShareContent(profile(insecure)))
    }
}
