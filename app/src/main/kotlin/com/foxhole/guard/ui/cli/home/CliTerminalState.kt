package com.foxhole.guard.ui.cli.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.RuntimeTeardownPhase
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.guard.ui.TorIdentityProbePhase
import com.foxhole.guard.ui.TorIdentityProbeState
import com.foxhole.guard.ui.cli.CliCommands
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.confirmedTorIdentityOrNull
import java.util.Locale

internal fun cliTerminalCommandText(text: String): String =
    buildString(text.length) {
        text.forEach { character -> append(character.lowercaseChar()) }
    }

internal enum class CliLineTone {
    PLAIN,
    DIM,
    ACCENT,
    OK,
    PENDING,
    WARN,
    ERR,
    INFO,
    VPN,
    TOR,
    I2P,
    FIREWALL,
    DNS_FILTER,
}

internal enum class CliLineIcon {
    IP,
    LOCATION,
}

@Immutable
internal data class CliTerminalLine(
    val timestampMs: Long,
    val text: String,
    val tone: CliLineTone = CliLineTone.PLAIN,
    val prompt: Boolean = false,
    val flagCountry: String? = null,
    val value: String? = null,
    val valueTone: CliLineTone = tone,
    val packages: List<String> = emptyList(),
    val valueLeading: Boolean = false,
    val inlineValue: Boolean = false,
    val typed: Boolean = true,
    val footnote: Boolean = false,
    val icon: CliLineIcon? = null,
    val id: Long = 0L,
)

@Immutable
internal data class CliTerminalProgress(
    val id: Long,
    val timestampMs: Long,
    val text: String,
    val tone: CliLineTone,
    val title: String? = null,
    val titleTone: CliLineTone = tone,
    val animated: Boolean = true,
)

@Immutable
internal data class CliTerminalRow(
    val key: String,
    val value: String? = null,
    val tone: CliLineTone = CliLineTone.INFO,
    val keyTone: CliLineTone = CliLineTone.DIM,
    val flagCountry: String? = null,
    val packages: List<String> = emptyList(),
    val valueLeading: Boolean = false,
    val inlineValue: Boolean = false,
    val typed: Boolean = false,
)

private enum class CliRouteIntent { NONE, VPN, TOR, VPN_TOR }

private fun routeIntentAfterCommand(text: String, current: CliRouteIntent): CliRouteIntent {
    val token = text.trim().lowercase(Locale.US)
    if (token == CliCommands.STOP || token == CliCommands.CANCEL) return CliRouteIntent.NONE
    val target = token
        .takeIf { it.startsWith(START_PREFIX) }
        ?.removePrefix(START_PREFIX)
        ?.trim()
        ?.substringBefore(' ')
        ?: return current
    return when (target) {
        TOKEN_VPN_TOR -> CliRouteIntent.VPN_TOR
        TOKEN_TOR -> CliRouteIntent.TOR
        TOKEN_VPN -> CliRouteIntent.VPN
        else -> current
    }
}

private enum class CliBootStage { NONE, LOADING, READY }

@Immutable
internal data class CliTerminalStrings(
    val bootLoading: String,
    val bootReady: String,
    val connecting: String,
    val connected: String,
    val tunnelUp: String,
    val tunnelUpNamed: String,
    val vpnEstablished: String,
    val vpnExitFailed: String,
    val i2pEstablished: String,
    val reconnecting: String,
    val error: String,
    val unknown: String,
    val closed: String,
    val torBesideVpn: String,
    val torStarting: String,
    val torConnecting: String,
    val torCircuits: String,
    val torConnected: String,
    val torExitLookup: String,
    val torExitFailed: String,
    val torStopped: String,
    val i2pStarting: String,
    val i2pDiscovering: String,
    val i2pTunnels: String,
    val i2pTunnelsCount: String,
    val i2pConnected: String,
    val i2pStopped: String,
    val disconnectingVpn: String,
    val disconnectingTor: String,
    val disconnectingI2p: String,
    val disconnectingAndroidTunnel: String,
    val exitKeyIp: String,
    val exitKeyGeo: String,
    val exitKeyIsp: String,
    val stepTunnel: String = connecting,
    val torConnectingTitle: String = torConnecting,
    val torEstablished: String = torConnected,
    val reasonLabels: Map<AutoConnectReasonCode, String> = emptyMap(),
)

private enum class CliProgressLeg { VPN, TOR, I2P, DISCONNECT, GEO }

private const val TERMINAL_MAX_LINES = 120
private const val WELCOME_LINE_PREFIX = "FoxHole Guard \u00b7 "
private const val LEGACY_WELCOME_LINE_PREFIX = "\u273b $WELCOME_LINE_PREFIX"
private const val BOOT_NOTICE_LINE_ID = Long.MIN_VALUE
private const val COLD_START_HOLD_LIMIT = TERMINAL_MAX_LINES
private const val MS_PER_HOUR = 3_600_000L

internal class CliFirewallReminder(
    private val emit: (String) -> Unit,
) {
    private var lastKey: String? = null

    fun update(packages: List<String>, message: String) {
        val key = packages.map(String::trim).filter(String::isNotBlank).distinct().sorted().joinToString("|")
        if (key.isEmpty()) {
            lastKey = null
            return
        }
        if (key == lastKey) return
        lastKey = key
        emit(message)
    }
}

@Suppress("TooManyFunctions", "LargeClass")
internal class CliTerminalState(
    private val strings: CliTerminalStrings,
    private val retentionHours: () -> Int = { DEFAULT_RETENTION_HOURS },
    private val onCommitted: (CliTerminalLine) -> Unit = {},
    private val onCleared: () -> Unit = {},
    startsHeld: Boolean = true,
) {
    val lines = mutableStateListOf<CliTerminalLine>()

    var progress: CliTerminalProgress? by mutableStateOf(null)
        private set

    var bootProgress: CliTerminalProgress? by mutableStateOf(null)
        private set

    private var lastConnectionKey: String? = null
    private var lastTorPhase: TorNetworkPhase? = null
    private var lastTorProgress: Int = -1
    private var lastI2pPhase: I2pNetworkPhase? = null
    private var lastI2pSnapshot: I2pPhaseSnapshot? = null
    private var lastVpnIdentity: RouteIdentityKey? = null
    private var lastTorIdentity: RouteIdentityKey? = null
    private var welcomed = false
    private var welcomeVersionName: String? = null
    private var bootStage = CliBootStage.NONE
    private var currentStatusText = strings.bootReady
    private var currentStatusTone = CliLineTone.OK
    private var statusNoticeArmed = true

    private var coldStartHeld = startsHeld
    private var restoredForColdStart: List<CliTerminalLine>? = null
    private var historyCleared = false

    private var vpnLegUp = false
    private var routeIntent = CliRouteIntent.NONE
    private var vpnTorExpected = false

    internal var vpnIdentityNotBeforeMs: Long? = null

    private var torLegOpened = false
    private var progressLeg: CliProgressLeg? = null
    private var nextProgressId = 0L
    private var vpnAwaitingIdentity = false
    private var torResultCommitted = false
    private var lastTorProbeState = TorIdentityProbeState()
    private var failedTorProbeGeneration: Long? = null
    private var i2pResultCommitted = false
    private var pendingVpnIdentity: IpInfo? = null
    private var pendingTorIdentity: IpInfo? = null
    private var observedProfileId: Long? = null
    private var settledProfileId: Long? = null
    private val torNarration = CliTorNarration(strings) { text, tone ->
        updateProgress(
            leg = CliProgressLeg.TOR,
            text = text,
            tone = tone,
            title = strings.torConnectingTitle,
            titleTone = CliLineTone.TOR,
        )
    }
    internal val firewallReminder = CliFirewallReminder { message ->
        note(message, CliLineTone.INFO)
    }

    val torNarrationRevision: Int
        get() = torNarration.revision

    val torNarrationPending: Boolean
        get() = torNarration.hasQueuedStages || (torNarration.isLookupVisible && pendingTorIdentity != null)

    fun welcome(versionName: String) {
        welcomeVersionName = versionName
        if (welcomed) return
        welcomed = true
        append(welcomeLine(versionName), CliLineTone.ACCENT)
    }

    fun onBootStage(ready: Boolean) {
        if (ready) {
            if (bootStage == CliBootStage.READY) return
            bootStage = CliBootStage.READY
            bootProgress = null
            restoredForColdStart?.let(::onJournalRestored)
            showCurrentStatusNotice()
        } else if (bootStage == CliBootStage.NONE) {
            bootStage = CliBootStage.LOADING
            bootProgress =
                CliTerminalProgress(
                    id = BOOT_NOTICE_LINE_ID,
                    timestampMs = System.currentTimeMillis(),
                    text = strings.bootLoading,
                    tone = CliLineTone.DIM,
                )
        }
    }

    fun updateCurrentStatus(text: String, tone: CliLineTone) {
        currentStatusText = text
        currentStatusTone = tone
        val noticeIndex = lines.indexOfFirst { line -> line.id == BOOT_NOTICE_LINE_ID }
        if (bootStage == CliBootStage.READY && noticeIndex >= 0 && noticeIndex == lines.lastIndex) {
            lines[noticeIndex] = lines[noticeIndex].copy(text = text, tone = tone)
        }
    }

    fun onJournalRestored(restored: List<CliTerminalLine>) {
        val accepted = if (historyCleared) {
            emptyList()
        } else {
            restored.filterNot { line ->
                line.text == strings.bootReady ||
                    line.text.startsWith(WELCOME_LINE_PREFIX) ||
                    line.text.startsWith(LEGACY_WELCOME_LINE_PREFIX)
            }
        }
        if (!coldStartHeld) {
            restoreLines(accepted)
            return
        }
        restoredForColdStart = accepted
        if (bootStage != CliBootStage.READY && held.size <= COLD_START_HOLD_LIMIT) {
            return
        }
        coldStartHeld = false
        restoredForColdStart = null
        val session = held.toList()
        held.clear()
        lines.clear()
        restoreLines(accepted)
        session.forEach(::commit)
        showCurrentStatusNotice()
    }

    fun clearHistory() {
        historyCleared = true
        statusNoticeArmed = true
        restoredForColdStart = emptyList()
        blockQueue.clear()
        pendingOutput = null
        held.clear()
        promptText = null
        promptTypedCount = 0
        progress = null
        progressLeg = null
        lines.clear()
        onCleared()
        welcomeVersionName?.let { versionName ->
            append(welcomeLine(versionName), CliLineTone.ACCENT)
        }
        if (bootStage == CliBootStage.READY) {
            showCurrentStatusNotice()
        }
    }

    private fun showCurrentStatusNotice() {
        lines.removeAll { line -> line.id == BOOT_NOTICE_LINE_ID }
        if (!statusNoticeArmed) return
        lines.add(
            CliTerminalLine(
                id = BOOT_NOTICE_LINE_ID,
                timestampMs = System.currentTimeMillis(),
                text = currentStatusText,
                tone = currentStatusTone,
                typed = false,
            ),
        )
    }

    fun onConnection(snapshot: ConnectionSnapshot) {
        val key = when (snapshot.state) {
            ConnectionState.CONNECTED -> "${snapshot.state}|${snapshot.profileName}|${snapshot.protocolHint}"
            else ->
                "${snapshot.state}|${snapshot.profileName}|${snapshot.torActive}|" +
                    "${snapshot.teardownPhase}|${snapshot.message}"
        }
        observedProfileId = snapshot.profileId
        if (shouldIgnoreSettledInPlaceReload(snapshot)) {
            lastConnectionKey = key
            return
        }
        vpnLegUp = snapshot.state == ConnectionState.CONNECTED &&
            snapshot.profileId != TOR_ONLY_PROFILE_ID &&
            snapshot.profileId != LOCAL_GUARD_PROFILE_ID
        if (key == lastConnectionKey) return
        val first = lastConnectionKey == null
        lastConnectionKey = key
        if (first && snapshot.state == ConnectionState.IDLE) return
        appendConnectionTransition(snapshot)
    }

    private fun shouldIgnoreSettledInPlaceReload(snapshot: ConnectionSnapshot): Boolean =
        snapshot.state == ConnectionState.RECONNECTING &&
            snapshot.inPlaceRuntimeReload &&
            snapshot.profileId != null &&
            snapshot.profileId == settledProfileId &&
            lastVpnIdentity != null

    @Suppress("CyclomaticComplexMethod")
    private fun appendConnectionTransition(snapshot: ConnectionSnapshot) {
        if (snapshot.state != ConnectionState.DISCONNECTING) {
            clearProgress(CliProgressLeg.DISCONNECT)
        }
        if (snapshot.state == ConnectionState.ERROR || snapshot.state == ConnectionState.IDLE) {
            cancelTorProgress()
        }
        if (snapshot.profileId == TOR_ONLY_PROFILE_ID && snapshot.state != ConnectionState.DISCONNECTING) return
        if (snapshot.profileId == LOCAL_GUARD_PROFILE_ID && snapshot.state != ConnectionState.DISCONNECTING) return
        when (snapshot.state) {
            ConnectionState.CONNECTING -> {
                settledProfileId = null
                lastVpnIdentity = null
                pendingVpnIdentity = null
                vpnIdentityNotBeforeMs = snapshot.lastChangeAt
                vpnAwaitingIdentity = true
                vpnTorExpected = snapshot.torActive || routeIntent == CliRouteIntent.VPN_TOR
                if (routeIntent == CliRouteIntent.NONE && !isRecentModeCommand(lastModeCommandAtMs)) {
                    val command = if (snapshot.torActive) {
                        CliCommands.startVpnTor(snapshot.profileName)
                    } else {
                        CliCommands.startVpn(snapshot.profileName)
                    }
                    append(command, CliLineTone.PLAIN, prompt = true)
                }
                updateVpnConnectProgress(strings.stepTunnel)
            }
            ConnectionState.CONNECTED -> {
                if (vpnIdentityNotBeforeMs == null) {
                    vpnIdentityNotBeforeMs = terminalConnectedIdentityNotBefore(snapshot.lastChangeAt)
                }
                vpnTorExpected = vpnTorExpected || snapshot.torActive || routeIntent == CliRouteIntent.VPN_TOR
                val pendingIdentity = pendingVpnIdentity
                vpnAwaitingIdentity = lastVpnIdentity == null && pendingIdentity == null
                if (pendingIdentity != null) {
                    commitVpnIdentity(pendingIdentity)
                } else if (vpnAwaitingIdentity) {
                    updateVpnConnectProgress(strings.connected, CliLineTone.OK)
                    if (vpnTorExpected) openTorLeg(catchUp = true, primeStarting = true)
                } else {
                    clearProgress(CliProgressLeg.VPN)
                    syncTorProgress()
                    syncI2pProgress()
                }
            }
            ConnectionState.RECONNECTING -> {
                settledProfileId = null
                lastVpnIdentity = null
                pendingVpnIdentity = null
                vpnIdentityNotBeforeMs = snapshot.lastChangeAt
                vpnAwaitingIdentity = true
                val reason = snapshot.localizedReason(strings.reasonLabels)
                val text = if (reason != null) "${strings.reconnecting} · $reason" else strings.reconnecting
                updateVpnConnectProgress(text)
            }
            ConnectionState.DISCONNECTING -> {
                settledProfileId = null
                vpnAwaitingIdentity = false
                pendingVpnIdentity = null
                vpnIdentityNotBeforeMs = null
                pendingTorIdentity = null
                updateProgress(
                    CliProgressLeg.DISCONNECT,
                    disconnectingText(strings, snapshot.teardownPhase),
                    CliLineTone.PENDING,
                )
            }
            ConnectionState.ERROR -> {
                settledProfileId = null
                lastVpnIdentity = null
                clearProgress(CliProgressLeg.VPN)
                vpnAwaitingIdentity = false
                pendingVpnIdentity = null
                vpnIdentityNotBeforeMs = null
                vpnTorExpected = false
                routeIntent = CliRouteIntent.NONE
                appendError(snapshot)
                syncI2pProgress()
            }
            ConnectionState.IDLE -> {
                settledProfileId = null
                lastVpnIdentity = null
                clearProgress(CliProgressLeg.VPN)
                vpnAwaitingIdentity = false
                pendingVpnIdentity = null
                vpnIdentityNotBeforeMs = null
                vpnTorExpected = false
                routeIntent = CliRouteIntent.NONE
                if (hasNarrated) append(strings.closed, CliLineTone.DIM)
                syncI2pProgress()
            }
        }
    }

    private fun appendError(snapshot: ConnectionSnapshot) {
        val reason = snapshot.localizedReason(strings.reasonLabels)
        append(String.format(Locale.US, strings.error, reason ?: strings.unknown), CliLineTone.ERR)
        if (reason == null) {
            snapshot.message?.takeIf { it.isNotBlank() }?.let(::footnote)
        }
    }

    fun onTorPhase(phase: TorPhaseSnapshot) {
        val progress = phase.progress ?: -1
        if (phase.phase == lastTorPhase && progress == lastTorProgress) return
        val phaseChanged = phase.phase != lastTorPhase
        val first = lastTorPhase == null
        lastTorPhase = phase.phase
        lastTorProgress = progress
        if (!phaseChanged) return
        if (first && phase.phase == TorNetworkPhase.OFFLINE) {
            cancelTorProgress()
            return
        }
        val primeStarting = shouldPrimeTorStarting(vpnAwaitingIdentity, vpnTorExpected)
        when (phase.phase) {
            TorNetworkPhase.CONNECTING ->
                openTorLeg(primeStarting = primeStarting)
            TorNetworkPhase.BUILDING_CIRCUITS ->
                openTorLeg(catchUp = true, primeStarting = primeStarting)
            TorNetworkPhase.CONNECTED ->
                openTorLeg(catchUp = true, primeStarting = primeStarting)
            TorNetworkPhase.OFFLINE -> {
                val disconnecting = progressLeg == CliProgressLeg.DISCONNECT
                val completedLeg = torLegOpened
                cancelTorProgress()
                if (completedLeg) {
                    routeIntent = CliRouteIntent.NONE
                    vpnTorExpected = false
                }
                if (hasNarrated && !disconnecting) append(strings.torStopped, CliLineTone.DIM)
                syncI2pProgress()
            }
        }
    }

    private fun openTorLeg(
        catchUp: Boolean = false,
        primeStarting: Boolean = false,
    ) {
        if (!torLegOpened) {
            torLegOpened = true
            torResultCommitted = false
            lastTorIdentity = null
            torNarration.reset(
                completedThrough = CliTorNarrationStage.STARTING.takeUnless { primeStarting },
            )
            if (!catchUp && routeIntent == CliRouteIntent.NONE && !isRecentModeCommand(lastModeCommandAtMs)) {
                append(CliCommands.START_TOR, CliLineTone.PLAIN, prompt = true)
            }
        }
        if (primeStarting && (vpnLegUp || lastTorPhase in setOf(null, TorNetworkPhase.OFFLINE))) {
            requestTorNarration(CliTorNarrationStage.STARTING, allowWhileVpnIdentityPending = true)
        } else {
            syncTorProgress()
        }
    }

    private fun syncTorProgress() {
        if (!torLegOpened || vpnAwaitingIdentity || torResultCommitted) return
        when (lastTorPhase) {
            TorNetworkPhase.CONNECTING -> requestTorNarration(CliTorNarrationStage.CONNECTING)
            TorNetworkPhase.BUILDING_CIRCUITS -> requestTorNarration(CliTorNarrationStage.CIRCUITS)
            TorNetworkPhase.CONNECTED -> requestTorNarration(CliTorNarrationStage.CONNECTED)
            TorNetworkPhase.OFFLINE, null -> Unit
        }
    }

    fun onTorIdentityProbe(state: TorIdentityProbeState) {
        if (state == lastTorProbeState) return
        if (state.generation != lastTorProbeState.generation) {
            val retryAfterFailure = failedTorProbeGeneration != null
            failedTorProbeGeneration = null
            torResultCommitted = false
            lastTorIdentity = null
            pendingTorIdentity = null
            if (retryAfterFailure) {
                torNarration.reset(completedThrough = CliTorNarrationStage.CONNECTED)
            }
        }
        lastTorProbeState = state
        if (!torLegOpened || lastTorPhase != TorNetworkPhase.CONNECTED || vpnAwaitingIdentity) return
        when (state.phase) {
            TorIdentityProbePhase.IDLE -> Unit
            TorIdentityProbePhase.LOOKING_UP -> requestTorNarration(CliTorNarrationStage.LOOKUP)
            TorIdentityProbePhase.CONFIRMED -> Unit
            TorIdentityProbePhase.FAILED -> {
                finishTorIdentityFailure(state.generation)
            }
            TorIdentityProbePhase.CANCELLED -> cancelTorProgress()
        }
    }

    fun onI2pPhase(snapshot: I2pPhaseSnapshot) {
        if (snapshot == lastI2pSnapshot) return
        val first = lastI2pPhase == null
        lastI2pPhase = snapshot.phase
        lastI2pSnapshot = snapshot
        if (first && snapshot.phase == I2pNetworkPhase.OFFLINE) return
        when (snapshot.phase) {
            I2pNetworkPhase.STARTING -> {
                i2pResultCommitted = false
                syncI2pProgress(snapshot)
            }
            I2pNetworkPhase.DISCOVERING_PEERS,
            I2pNetworkPhase.BUILDING_TUNNELS,
            I2pNetworkPhase.CONNECTED,
            -> syncI2pProgress(snapshot)
            I2pNetworkPhase.OFFLINE -> {
                val disconnecting = progressLeg == CliProgressLeg.DISCONNECT
                i2pResultCommitted = false
                clearProgress(CliProgressLeg.I2P)
                if (hasNarrated && !disconnecting) append(strings.i2pStopped, CliLineTone.DIM)
            }
        }
    }

    private fun syncI2pProgress(snapshot: I2pPhaseSnapshot? = lastI2pSnapshot) {
        val current = snapshot ?: return
        val higherLegPending =
            progressLeg == CliProgressLeg.VPN ||
                vpnAwaitingIdentity ||
                progressLeg == CliProgressLeg.TOR ||
                (torLegOpened && !torResultCommitted)
        if (higherLegPending || i2pResultCommitted) return
        when (current.phase) {
            I2pNetworkPhase.STARTING ->
                updateProgress(CliProgressLeg.I2P, strings.i2pStarting, CliLineTone.PENDING)
            I2pNetworkPhase.DISCOVERING_PEERS ->
                updateProgress(CliProgressLeg.I2P, strings.i2pDiscovering, CliLineTone.PENDING)
            I2pNetworkPhase.BUILDING_TUNNELS ->
                updateProgress(
                    CliProgressLeg.I2P,
                    if (current.tunnelsBuilt > 0) {
                        String.format(Locale.US, strings.i2pTunnelsCount, current.tunnelsBuilt)
                    } else {
                        strings.i2pTunnels
                    },
                    CliLineTone.PENDING,
                    animated = false,
                )
            I2pNetworkPhase.CONNECTED -> {
                updateProgress(CliProgressLeg.I2P, strings.i2pConnected, CliLineTone.OK)
                append(
                    i2pEstablishedLine(strings, vpnLegUp, lastTorIdentity != null),
                    CliLineTone.I2P,
                )
                i2pResultCommitted = true
                clearProgress(CliProgressLeg.I2P)
            }
            I2pNetworkPhase.OFFLINE -> Unit
        }
    }

    fun onRouteIpInfo(
        vpnInfo: IpInfo?,
        torInfo: IpInfo?,
    ) {
        vpnInfo
            ?.takeIf { info ->
                val ip = info.ip.trim()
                ip != lastVpnIdentity?.ip && ip != pendingVpnIdentity?.ip?.trim()
            }
            ?.let { info ->
                pendingVpnIdentity = info
                if (vpnLegUp) {
                    commitVpnIdentity(info)
                }
            }
        torInfo
            ?.confirmedTorIdentityOrNull()
            ?.takeIf {
                torLegOpened &&
                    lastTorPhase == TorNetworkPhase.CONNECTED &&
                    !torResultCommitted &&
                    failedTorProbeGeneration != lastTorProbeState.generation &&
                    it.ip.trim() != lastTorIdentity?.ip
            }
            ?.let { info ->
                pendingTorIdentity = info
                if (!vpnAwaitingIdentity) requestTorNarration(CliTorNarrationStage.LOOKUP)
            }
    }

    private fun commitVpnIdentity(info: IpInfo) {
        lastVpnIdentity = info.routeIdentityKey()
        vpnAwaitingIdentity = false
        if (vpnTorExpected || torLegOpened) {
            commitPendingVpnIdentity()
            if (!torLegOpened) openTorLeg(catchUp = true, primeStarting = true)
            resumeTorAfterVpnIdentityOutcome()
        } else {
            finishConnectionSuccess()
        }
    }

    fun advanceTorNarration() {
        if (!torLegOpened || vpnAwaitingIdentity) return
        if (torNarration.advance()) return
        if (torNarration.isLookupVisible) {
            pendingTorIdentity?.let(::commitTorIdentity)
        }
    }

    private fun requestTorNarration(
        target: CliTorNarrationStage,
        allowWhileVpnIdentityPending: Boolean = false,
    ) {
        if (!torLegOpened) return
        torNarration.request(target, allowDisplay = allowWhileVpnIdentityPending || !vpnAwaitingIdentity)
        if (target == CliTorNarrationStage.LOOKUP && pendingTorIdentity != null) torNarration.signalIdentityReady()
    }

    private fun commitTorIdentity(info: IpInfo) {
        pendingTorIdentity = null
        lastTorIdentity = info.routeIdentityKey()
        torResultCommitted = true
        torNarration.reset(completedThrough = CliTorNarrationStage.LOOKUP)
        clearProgress(CliProgressLeg.TOR)
        commitPendingVpnIdentity()
        appendEstablishedBlock(
            title = strings.torEstablished,
            tone = CliLineTone.TOR,
            info = info,
        )
        finishConnectionSuccess(identitiesCommitted = true)
        syncI2pProgress()
    }

    private fun resumeTorAfterVpnIdentityOutcome() {
        when (lastTorProbeState.phase) {
            TorIdentityProbePhase.FAILED -> finishTorIdentityFailure(lastTorProbeState.generation)
            TorIdentityProbePhase.CANCELLED -> cancelTorProgress()
            TorIdentityProbePhase.IDLE,
            TorIdentityProbePhase.LOOKING_UP,
            TorIdentityProbePhase.CONFIRMED,
            -> {
                syncTorProgress()
                if (
                    lastTorPhase == TorNetworkPhase.CONNECTED &&
                    (lastTorProbeState.phase != TorIdentityProbePhase.IDLE || pendingTorIdentity != null)
                ) {
                    requestTorNarration(CliTorNarrationStage.LOOKUP)
                }
            }
        }
    }

    private fun finishTorIdentityFailure(generation: Long) {
        if (failedTorProbeGeneration != generation) {
            failedTorProbeGeneration = generation
            pendingTorIdentity = null
            torNarration.reset(completedThrough = CliTorNarrationStage.CONNECTED)
            clearProgress(CliProgressLeg.TOR)
            commitPendingVpnIdentity()
            append(strings.torExitFailed, CliLineTone.ERR)
        }
        torResultCommitted = true
        routeIntent = CliRouteIntent.NONE
        vpnTorExpected = false
        syncI2pProgress()
    }

    internal fun expireProgress(progressId: Long) {
        if (progress?.id != progressId) return
        when (progressLeg) {
            CliProgressLeg.VPN -> expireVpnIdentityWait()
            CliProgressLeg.TOR -> {
                if (vpnAwaitingIdentity && vpnLegUp) {
                    expireVpnIdentityWait()
                    return
                }
                if (lastTorPhase != TorNetworkPhase.CONNECTED) return
                finishTorIdentityFailure(lastTorProbeState.generation)
            }
            CliProgressLeg.I2P,
            CliProgressLeg.DISCONNECT,
            CliProgressLeg.GEO,
            null,
            -> Unit
        }
    }

    private fun expireVpnIdentityWait() {
        if (!vpnLegUp) return
        vpnAwaitingIdentity = false
        pendingVpnIdentity = null
        clearProgress(CliProgressLeg.VPN)
        append(strings.vpnExitFailed, CliLineTone.ERR)
        if (vpnTorExpected || torLegOpened) {
            if (!torLegOpened) openTorLeg(catchUp = true, primeStarting = true)
            resumeTorAfterVpnIdentityOutcome()
        } else {
            routeIntent = CliRouteIntent.NONE
            vpnTorExpected = false
            syncI2pProgress()
        }
    }

    private fun finishConnectionSuccess(identitiesCommitted: Boolean = false) {
        progress = null
        progressLeg = null
        if (!identitiesCommitted) {
            pendingVpnIdentity?.let { info ->
                appendEstablishedBlock(title = strings.tunnelUp, tone = CliLineTone.OK, info = info)
            }
            pendingVpnIdentity = null
        }
        settledProfileId = observedProfileId
        routeIntent = CliRouteIntent.NONE
        vpnTorExpected = false
    }

    private fun commitPendingVpnIdentity() {
        pendingVpnIdentity?.let { info ->
            appendEstablishedBlock(
                title = strings.vpnEstablished,
                tone = CliLineTone.VPN,
                info = info,
            )
        }
        pendingVpnIdentity = null
    }

    private fun appendEstablishedBlock(
        title: String,
        tone: CliLineTone,
        info: IpInfo,
    ) {
        append(title, tone)
        val ip = info.ip.trim()
        if (ip.isNotBlank()) {
            append(
                text = strings.exitKeyIp,
                tone = CliLineTone.DIM,
                icon = CliLineIcon.IP,
                value = ip,
                valueTone = tone,
                valueLeading = true,
                typed = false,
                footnote = true,
            )
        }
        val countryCode = info.countryCode?.trim()?.takeIf(String::isNotBlank)?.uppercase(Locale.US)
        val city = info.city?.trim()?.takeIf(String::isNotBlank)
        val geo = listOfNotNull(countryCode, city).joinToString(" · ")
        if (geo.isNotEmpty()) {
            append(
                text = strings.exitKeyGeo,
                tone = CliLineTone.DIM,
                icon = CliLineIcon.LOCATION,
                flagCountry = info.countryCode,
                value = geo,
                valueTone = tone,
                valueLeading = true,
                typed = false,
                footnote = true,
            )
        }
    }

    fun note(text: String, tone: CliLineTone = CliLineTone.DIM) = append(text, tone)

    fun footnote(text: String) = append(text, CliLineTone.DIM, typed = false, footnote = true)

    private val blockQueue = mutableStateListOf<CliTerminalRow>()

    val blockPending: Boolean
        get() = blockQueue.isNotEmpty()

    fun emitBlock(rows: List<CliTerminalRow>, inlineValues: Boolean = false) {
        if (rows.isEmpty()) return
        blockQueue.addAll(
            if (inlineValues) rows.map { row -> row.copy(inlineValue = true) } else rows,
        )
    }

    fun drainBlockRow(): Boolean {
        if (blockQueue.isEmpty()) return false
        val row = blockQueue.removeAt(0)
        append(
            text = row.key,
            tone = row.keyTone,
            flagCountry = row.flagCountry,
            value = row.value,
            valueTone = row.tone,
            packages = row.packages,
            valueLeading = row.valueLeading,
            inlineValue = row.inlineValue,
            typed = row.typed,
        )
        return true
    }

    internal var lastTypedLineId = -1L

    var promptText: String? by mutableStateOf(null)
        private set

    var promptTypedCount: Int by mutableIntStateOf(0)

    fun command(
        text: String,
        output: String? = null,
        outputTone: CliLineTone = CliLineTone.INFO,
        outputCountry: String? = null,
    ) {
        statusNoticeArmed = false
        lines.removeAll { line -> line.id == BOOT_NOTICE_LINE_ID }
        val normalizedCommand = text.trim().lowercase(Locale.US)
        if (normalizedCommand == CliCommands.STOP || normalizedCommand == CliCommands.CANCEL) {
            cancelTerminalProgress()
        }
        routeIntent = routeIntentAfterCommand(text, routeIntent)
        promptText?.let(::appendCommand)
        flushQueues()
        val issuedAtMs = System.currentTimeMillis()
        if (normalizedCommand.startsWith(MODE_PREFIX)) {
            lastModeCommandAtMs = issuedAtMs
        }
        promptText = text
        promptTypedCount = 0
        lastUserCommandAtMs = issuedAtMs
        pendingOutput = output?.let { CliTerminalLine(issuedAtMs, it, outputTone, flagCountry = outputCountry) }
    }

    fun commitPrompt() {
        promptText?.let(::appendCommand)
        promptText = null
        flushQueues()
        if (shouldOpenTorLeg(routeIntent, vpnLegUp)) {
            openTorLeg(catchUp = true, primeStarting = true)
        }
    }

    fun beginGeoRefresh(text: String) {
        if (progress != null) return
        updateProgress(
            leg = CliProgressLeg.GEO,
            text = text,
            tone = CliLineTone.PENDING,
        )
    }

    fun finishGeoRefresh() {
        clearProgress(CliProgressLeg.GEO)
    }

    private var pendingOutput: CliTerminalLine? = null
    private val held = mutableListOf<CliTerminalLine>()
    private var nextLineId = 0L
    private var lastUserCommandAtMs = 0L
    private var lastModeCommandAtMs = 0L

    private fun flushQueues() {
        pendingOutput?.let(::commit)
        pendingOutput = null
        held.forEach(::commit)
        held.clear()
    }

    private fun updateVpnConnectProgress(
        step: String,
        tone: CliLineTone = CliLineTone.PENDING,
    ) = updateProgress(
        leg = CliProgressLeg.VPN,
        text = step,
        tone = tone,
        title = strings.connecting,
        titleTone = CliLineTone.PENDING,
    )

    private fun updateProgress(
        leg: CliProgressLeg,
        text: String,
        tone: CliLineTone,
        title: String? = null,
        titleTone: CliLineTone = tone,
        animated: Boolean = true,
    ) {
        val current = progress
        val reusesCurrentConnection =
            current != null &&
                (progressLeg == leg || progressLeg == CliProgressLeg.VPN && leg == CliProgressLeg.TOR)
        progress = if (reusesCurrentConnection) {
            current.copy(
                text = text,
                tone = tone,
                title = title,
                titleTone = titleTone,
                animated = animated,
            )
        } else {
            CliTerminalProgress(
                id = nextProgressId++,
                timestampMs = System.currentTimeMillis(),
                text = text,
                tone = tone,
                title = title,
                titleTone = titleTone,
                animated = animated,
            )
        }
        progressLeg = leg
    }

    private fun clearProgress(leg: CliProgressLeg) {
        if (progressLeg != leg) return
        progress = null
        progressLeg = null
    }

    private fun cancelTorProgress() {
        torLegOpened = false
        torResultCommitted = false
        failedTorProbeGeneration = null
        pendingTorIdentity = null
        torNarration.reset()
        if (progressLeg == CliProgressLeg.TOR && vpnAwaitingIdentity && vpnLegUp) {
            progressLeg = CliProgressLeg.VPN
            updateVpnConnectProgress(strings.connected, CliLineTone.OK)
        } else {
            clearProgress(CliProgressLeg.TOR)
        }
    }

    internal fun cancelTerminalProgress() {
        torLegOpened = false
        torResultCommitted = false
        failedTorProbeGeneration = null
        vpnAwaitingIdentity = false
        pendingVpnIdentity = null
        vpnIdentityNotBeforeMs = null
        pendingTorIdentity = null
        vpnTorExpected = false
        routeIntent = CliRouteIntent.NONE
        torNarration.reset()
        progress = null
        progressLeg = null
    }

    private fun appendCommand(text: String) {
        lines.pruneBefore(System.currentTimeMillis() - retentionHours() * MS_PER_HOUR)
        commit(
            CliTerminalLine(
                timestampMs = lastUserCommandAtMs,
                text = text,
                tone = CliLineTone.PLAIN,
                prompt = true,
            ),
        )
    }

    internal fun append(
        text: String,
        tone: CliLineTone,
        prompt: Boolean = false,
        flagCountry: String? = null,
        value: String? = null,
        valueTone: CliLineTone = tone,
        packages: List<String> = emptyList(),
        valueLeading: Boolean = false,
        inlineValue: Boolean = false,
        typed: Boolean = true,
        footnote: Boolean = false,
        icon: CliLineIcon? = null,
    ) {
        lines.pruneBefore(System.currentTimeMillis() - retentionHours() * MS_PER_HOUR)
        val line = CliTerminalLine(
            timestampMs = System.currentTimeMillis(),
            text = text,
            tone = tone,
            icon = icon,
            prompt = prompt,
            flagCountry = flagCountry,
            value = value,
            valueTone = valueTone,
            packages = packages,
            valueLeading = valueLeading,
            inlineValue = inlineValue,
            typed = typed,
            footnote = footnote,
        )
        if (coldStartHeld) {
            held.add(line)
            restoredForColdStart?.let(::onJournalRestored)
            return
        }
        if (promptText != null && !prompt) {
            held.add(line)
            return
        }
        commit(line)
    }

    private val hasNarrated: Boolean
        get() = held.isNotEmpty() || lines.any { line -> line.id != BOOT_NOTICE_LINE_ID }

    private fun commit(line: CliTerminalLine) {
        val stampMs = maxOf(line.timestampMs, lines.lastOrNull()?.timestampMs ?: line.timestampMs)
        val committed = line.copy(timestampMs = stampMs, id = nextLineId++)
        lines.add(committed)
        while (lines.size > TERMINAL_MAX_LINES) {
            lines.removeAt(0)
        }
        onCommitted(committed)
    }

    companion object {
        internal const val SCREEN_LINE_LIMIT = TERMINAL_MAX_LINES
        internal const val DEFAULT_RETENTION_HOURS = 12
    }
}

internal fun isCliWelcomeLine(text: String): Boolean =
    text.startsWith(WELCOME_LINE_PREFIX) || text.startsWith(LEGACY_WELCOME_LINE_PREFIX)

private fun welcomeLine(versionName: String): String = "${WELCOME_LINE_PREFIX}v$versionName"

internal fun CliTerminalState.onPendingFirewallActions(
    packages: List<String>,
    message: String,
) = firewallReminder.update(packages, message)

private fun disconnectingText(
    strings: CliTerminalStrings,
    phase: RuntimeTeardownPhase?,
): String = when (phase) {
    RuntimeTeardownPhase.VPN -> strings.disconnectingVpn
    RuntimeTeardownPhase.TOR -> strings.disconnectingTor
    RuntimeTeardownPhase.I2P -> strings.disconnectingI2p
    RuntimeTeardownPhase.ANDROID_TUNNEL, null -> strings.disconnectingAndroidTunnel
}

private const val START_PREFIX = "start "
private const val MODE_PREFIX = "mode "
private const val TOKEN_VPN_TOR = "vpn+tor"
private const val TOKEN_TOR = "tor"
private const val TOKEN_VPN = "vpn"
private const val MODE_COMMAND_DEDUPE_MS = 6_000L
private const val CONNECTED_IDENTITY_PUBLISH_LEAD_MS = 5_000L

internal fun terminalConnectedIdentityNotBefore(connectedAtMs: Long): Long =
    (connectedAtMs - CONNECTED_IDENTITY_PUBLISH_LEAD_MS).coerceAtLeast(0L)

private fun shouldOpenTorLeg(routeIntent: CliRouteIntent, vpnLegUp: Boolean): Boolean =
    routeIntent == CliRouteIntent.TOR || (routeIntent == CliRouteIntent.VPN_TOR && vpnLegUp)

private fun shouldPrimeTorStarting(vpnAwaitingIdentity: Boolean, vpnTorExpected: Boolean): Boolean =
    vpnAwaitingIdentity && vpnTorExpected

private fun isRecentModeCommand(lastModeCommandAtMs: Long): Boolean =
    System.currentTimeMillis() - lastModeCommandAtMs < MODE_COMMAND_DEDUPE_MS

private fun connectedRouteNamesWithI2p(vpnLegUp: Boolean, torConnected: Boolean): String = buildList {
    if (vpnLegUp) add("VPN")
    if (torConnected) add("Tor")
    add("I2P")
}.joinToString(" + ")

private fun i2pEstablishedLine(
    strings: CliTerminalStrings,
    vpnLegUp: Boolean,
    torConnected: Boolean,
): String = String.format(Locale.US, strings.tunnelUpNamed, connectedRouteNamesWithI2p(vpnLegUp, torConnected))

internal fun CliTerminalLine.prefix(): String = "[${CliFormat.clock(timestampMs)}]"

private data class RouteIdentityKey(
    val ip: String,
    val countryCode: String?,
    val countryName: String?,
    val city: String?,
    val isp: String?,
)

private fun IpInfo.routeIdentityKey() = RouteIdentityKey(
    ip = ip.trim(),
    countryCode = countryCode?.trim()?.uppercase(Locale.US),
    countryName = countryName?.trim(),
    city = city?.trim(),
    isp = isp?.trim(),
)

internal fun CliTerminalState.claimTyping(lineId: Long): Boolean {
    if (lineId <= lastTypedLineId) return false
    lastTypedLineId = lineId
    return true
}

internal fun CliTerminalState.restoreLines(restored: List<CliTerminalLine>) {
    if (restored.isEmpty()) {
        return
    }
    val reindexed = restored.mapIndexed { index, line -> line.copy(id = -(index + 1).toLong()) }
    lines.addAll(0, reindexed)
    while (lines.size > CliTerminalState.SCREEN_LINE_LIMIT) {
        lines.removeAt(0)
    }
}

private fun MutableList<CliTerminalLine>.pruneBefore(cutoffMs: Long) {
    while (isNotEmpty() && first().timestampMs < cutoffMs) {
        removeAt(0)
    }
}

private fun ConnectionSnapshot.localizedReason(labels: Map<AutoConnectReasonCode, String>): String? =
    reasonCode?.let(labels::get)
