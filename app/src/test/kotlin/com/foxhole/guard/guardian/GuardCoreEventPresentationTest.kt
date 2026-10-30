package com.foxhole.guard.guardian

import com.foxhole.guard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardCoreEventPresentationTest {
    @Test
    fun everyE5BlockReasonHasAClosedExplanationAndAction() {
        val reasons =
            listOf("kill_switch", "policy", "unattributed", "flow_limit", "dns_blocklist", "dns_overlay_gate", "udp_unsupported", "continuity_held", "tunnel_address_mismatch", "tunnel_family_unmapped", "tunnel_fake_ip_unroutable", "tunnel_socket_unavailable", "flow_backlog_exceeded", "lane_unavailable", "tunnel_routing_loop", "tunnel_peer_unresponsive", "dns_encrypted_bypass")
        listOf("en", "ru").forEach { language ->
            val text = journalResourceText(language)
            reasons.forEach { reason ->
                val message = render(GuardEventType.CORE_FLOW_BLOCKED, "reason=$reason transport=udp count=2", language)
                assertFalse(reason, message.contains(text(R.string.journal_unknown_reason, emptyList())))
                assertFalse(reason, message.contains(text(R.string.journal_unknown_action, emptyList())))
                assertTrue(reason, message.contains(text(R.string.journal_transport, listOf("UDP"))))
                assertTrue(reason, message.contains(text(R.string.journal_count, listOf("2"))))
            }
        }
    }

    @Test
    fun everyE5AvailabilityReasonHasItsOwnCauseAndNextStep() {
        listOf("timeout", "permissions", "network", "config", "unsupported", "disabled", "internal").forEach { reason ->
            listOf("en", "ru").forEach { language ->
                val text = journalResourceText(language)
                val message = render(
                    GuardEventType.CORE_OUTBOUND_UNAVAILABLE,
                    "id=primary kind=wireguard reason=$reason attempts=3",
                    language
                )
                assertFalse(message.contains(text(R.string.journal_unknown_reason, emptyList())))
                assertTrue(message.contains(text(R.string.journal_kind, listOf("wireguard"))))
                assertTrue(message.contains(text(R.string.journal_attempts, listOf("3"))))
                assertFalse(message.contains(text(R.string.journal_transport, listOf("UDP"))))
            }
        }
    }

    @Test
    fun holdExpiryPeerAndBacklogHaveDifferentSourceBackedOutcomes() {
        val hold = render(GuardEventType.CORE_FLOW_BLOCKED, "reason=continuity_held")
        val expiry = render(GuardEventType.CORE_CONFIRMATION_EXPIRED, "interruption=network_switch token=top_secret")
        val peer = render(GuardEventType.CORE_FLOW_BLOCKED, "reason=tunnel_peer_unresponsive")
        val backlog = render(GuardEventType.CORE_FLOW_BLOCKED, "reason=flow_backlog_exceeded")
        assertTrue(hold.contains("remains held until confirmation"))
        assertFalse(hold.contains("This flow was refused"))
        assertTrue(expiry.contains("The tunnel was not stopped."))
        assertTrue(expiry.contains("Confirmation is still valid."))
        assertTrue(expiry.contains("affected lane remains held"))
        assertFalse(expiry.contains("top_secret"))
        assertTrue(peer.contains("relay keeps retrying"))
        assertFalse(peer.contains("This flow was refused"))
        assertTrue(backlog.contains("No outbound write progress within its time budget"))
        assertTrue(backlog.contains("stalled flow was ended"))
    }

    @Test
    fun dnsCategoryDoesNotInventTransportPortOrProtocol() {
        listOf("malicious", "telemetry", "trackers", "ads").forEach { category ->
            val message = render(
                GuardEventType.CORE_DNS_BLOCKED,
                "reason=dns_blocklist category=$category count=4 transport=tcp port=53"
            )
            assertTrue(message.contains("DNS category:"))
            assertFalse(message.contains("Not reported"))
            assertFalse(message.contains("Transport:"))
            assertFalse(message.contains("Port:"))
        }
        assertTrue(
            render(GuardEventType.CORE_DNS_BLOCKED, "reason=dns_overlay_gate category=none").contains("Not reported")
        )
    }

    @Test
    fun confirmationInterruptionCatalogWorksInBothActualResources() {
        listOf("proxy_session", "selector_member", "network_switch", "vpn_failure").forEach { interruption ->
            listOf("en", "ru").forEach { language ->
                val message = render(
                    GuardEventType.CORE_CONFIRMATION_REQUIRED,
                    "interruption=$interruption token=899",
                    language
                )
                assertFalse(
                    message.contains(journalResourceText(language)(R.string.journal_unknown_reason, emptyList()))
                )
                assertFalse(message.contains("899"))
            }
        }
        assertTrue(
            render(
                GuardEventType.CORE_CONFIRMATION_EXPIRED,
                "interruption=vpn_failure",
                "ru"
            ).contains(journalResourceText("ru")(R.string.journal_confirmation_still_valid, emptyList()))
        )
    }

    @Test
    fun revisionsRestorationRevocationAndQueueGapsRetainTheirFacts() {
        assertTrue(
            render(
                GuardEventType.CORE_CONFIG_APPLIED,
                "previous=18446744073709551614 revision=18446744073709551615"
            ).contains("18446744073709551615")
        )
        val restored = render(GuardEventType.CORE_OUTBOUND_RESTORED, "id=secondary kind=tor attempts=2")
        assertTrue(restored.contains("without restarting the engine"))
        val revoked = render(GuardEventType.CORE_FLOWS_REVOKED, "target=package scope=com.example.owner count=0")
        assertTrue(revoked.contains("App package"))
        assertTrue(revoked.contains("Live flows ended: 0"))
        val gap = render(GuardEventType.CORE_EVENT_GAP, "audit_dropped=9 traffic_dropped=13")
        assertTrue(gap.contains("Audit events dropped before delivery: 9"))
        assertTrue(gap.contains("Traffic events dropped before delivery: 13"))
        assertTrue(gap.contains("do not prove durable storage"))
    }

    @Test
    fun unknownCodeIsDiagnosticOnlyAndExceptionPayloadsAreNotShown() {
        val message = render(
            GuardEventType.CORE_FLOW_BLOCKED,
            "reason=future_code message=java.lang.Exception password=secret token=899"
        )
        assertTrue(message.startsWith("Core flow event"))
        assertFalse(message.startsWith("connection blocked"))
        assertTrue(message.contains("recognized reason"))
        assertTrue(message.contains("cannot be determined"))
        assertTrue(message.contains("Diagnostic code: future_code"))
        assertFalse(message.contains("Exception"))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("899"))
        assertFalse(message.contains("This flow was refused"))
        val unsafe = render(GuardEventType.CORE_FLOW_BLOCKED, "reason=https://user:password@example.com/?token=secret")
        assertFalse(unsafe.contains("password"))
        assertFalse(unsafe.contains("example.com"))
        assertFalse(unsafe.contains("secret"))
    }

    @Test
    fun parserRejectsDuplicatesOversizedValuesAndOversizedInput() {
        assertEquals(emptyMap<String, String>(), parseCoreJournalFields("reason=policy reason=kill_switch"))
        assertFalse(parseCoreJournalFields("reason=" + "x".repeat(129)).containsKey("reason"))
        assertFalse(parseCoreJournalFields("reason=" + "x".repeat(129) + " reason=policy").containsKey("reason"))
        assertEquals(emptyMap<String, String>(), parseCoreJournalFields("reason=policy " + "x".repeat(2048)))
        assertEquals(
            mapOf("reason" to "policy", "count" to "2"),
            parseCoreJournalFields("token=123 password=secret reason=policy count=2")
        )
        assertFalse(
            render(GuardEventType.CORE_FLOW_BLOCKED, "reason=policy count=-1").contains("Events in this group:")
        )
        assertFalse(
            render(
                GuardEventType.CORE_FLOW_BLOCKED,
                "reason=policy count=18446744073709551616"
            ).contains("Events in this group:")
        )
    }

    @Test
    fun exportedCoreSensitiveFieldsAreMaskedBeforeTranslation() {
        listOf("en", "ru").forEach { language ->
            val event = GuardEvent(
                GuardEventType.CORE_OUTBOUND_UNAVAILABLE,
                packageName = "com.example.owner",
                detail = "id=PRIVATE_ACCOUNT kind=wireguard reason=config attempts=1 token=token123"
            )
            val message = requireNotNull(event.coreUserFacingMessage(journalResourceText(language), forExport = true))
            assertFalse(message.contains("PRIVATE_ACCOUNT"))
            assertFalse(message.contains("com.example.owner"))
            assertFalse(message.contains("token123"))
            assertTrue(message.contains(journalResourceText(language)(R.string.journal_redacted, emptyList())))
            val revoked = requireNotNull(
                GuardEvent(
                    GuardEventType.CORE_FLOWS_REVOKED,
                    detail = "target=uid scope=10023 count=3"
                ).coreUserFacingMessage(journalResourceText(language), forExport = true)
            )
            assertFalse(revoked.contains("10023"))
        }
    }

    @Test
    fun nonCoreEntriesKeepTheirSeparatePresenter() {
        assertEquals(null, GuardEvent(GuardEventType.PACKAGE_ADDED).coreUserFacingMessage(journalResourceText("en")))
        GuardEventType.entries.forEach { assertNotNull(it.userLabelRes()) }
    }

    @Test
    fun packageQueueGapStatesIncompleteHistoryAndRequiredRepairWithoutNamesOrInfectionVerdict() {
        val event = GuardEvent(
            GuardEventType.PACKAGE_EVENT_GAP,
            service = "package-worker",
            detail = "dropped=3;fullRepairRequired=true"
        )
        val en = requireNotNull(event.packageGapUserFacingMessage(journalResourceText("en")))
        val ru = requireNotNull(event.packageGapUserFacingMessage(journalResourceText("ru")))
        assertTrue(en.contains("Package observations dropped: 3"))
        assertTrue(en.contains("history is incomplete"))
        assertTrue(en.contains("full app inventory repair is required"))
        assertTrue(en.contains("not a malware verdict"))
        assertTrue(ru.contains(journalResourceText("ru")(R.string.journal_package_gap_repair, emptyList())))
        assertFalse(en.contains("Core event queue"))
        val absent = requireNotNull(
            event.copy(detail = "dropped=bad").packageGapUserFacingMessage(journalResourceText("en"))
        )
        assertFalse(absent.contains("Package observations dropped:"))
        assertFalse(absent.contains("repair is required"))
    }

    @Test
    fun everyNewResourceHasAnActualRussianTranslation() {
        R.string::class.java.fields.filter { it.name.startsWith("journal_") }.forEach { field ->
            val resource = field.getInt(null)
            val en = journalResourceText("en")(resource, listOf("value"))
            val ru = journalResourceText("ru")(resource, listOf("value"))
            assertFalse(field.name, en == ru)
            if (field.name != "journal_target_uid") assertTrue(field.name, ru.any { it.code in 0x0400..0x04FF })
        }
    }

    @Test
    fun unavailableTunnelSocketWaitsAndRetriesWithoutBecomingAPolicyRefusal() {
        listOf("en", "ru").forEach { language ->
            val text = journalResourceText(language)
            val message = render(
                GuardEventType.CORE_FLOW_BLOCKED,
                "reason=tunnel_socket_unavailable transport=udp count=2",
                language
            )
            assertTrue(message.startsWith(text(R.string.journal_socket_wait_title, emptyList())))
            assertTrue(
                message.contains(text(R.string.journal_outcome, listOf(text(R.string.journal_retrying, emptyList()))))
            )
            assertFalse(message.contains(text(R.string.journal_refused, emptyList())))
            listOf("kill_switch", "policy", "udp_unsupported").forEach { reason ->
                val refused = render(GuardEventType.CORE_FLOW_BLOCKED, "reason=$reason", language)
                assertTrue(
                    refused.contains(
                        text(R.string.journal_outcome, listOf(text(R.string.journal_refused, emptyList())))
                    )
                )
                assertFalse(refused.startsWith(text(R.string.journal_socket_wait_title, emptyList())))
            }
        }
    }

    private fun render(type: GuardEventType, detail: String, language: String = "en"): String =
        requireNotNull(GuardEvent(type, detail = detail).coreUserFacingMessage(journalResourceText(language)))
}
