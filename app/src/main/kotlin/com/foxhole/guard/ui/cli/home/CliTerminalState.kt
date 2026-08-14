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

/** Log-line tones. [VPN]/[TOR] are the semantic route colors matching the home facts panel. */
internal enum class CliLineTone {
    PLAIN,
    DIM,
    ACCENT,
    OK,
    WARN,
    ERR,
    INFO,
    VPN,
    TOR,
    I2P,
    FIREWALL,
    DNS_FILTER,
}

@Immutable
internal data class CliTerminalLine(
    val timestampMs: Long,
    val text: String,
    val tone: CliLineTone = CliLineTone.PLAIN,
    val prompt: Boolean = false,
    // ISO2 country code: the line renders a pixel flag before the text.
    val flagCountry: String? = null,
    // Non-null makes the line two-column ([text] key left, [value] right-aligned). Alignment is
    // layout-only: LanaPixel is proportional, spaces cannot pad to a shared column.
    val value: String? = null,
    val valueTone: CliLineTone = tone,
    // Packages whose launcher icons stand in the value column instead of their names.
    val packages: List<String> = emptyList(),
    // Monotonic LazyColumn key: timestampMs collides within a ms, and head-prune without a key
    // rebound every visible row. Assigned only by [CliTerminalState.commit].
    val id: Long = 0L,
)

/**
 * The one mutable row at the bottom of the terminal while a network leg is being established.
 *
 * It is intentionally not part of [CliTerminalState.lines] and is never journalled: CONNECTING,
 * bootstrap and peer-discovery updates replace this row in place. Only the final, truthful result
 * is committed to history. [id] changes when a new leg starts and stays stable across its phases,
 * which lets the renderer keep one spinner instead of recreating a row for every status update.
 */
@Immutable
internal data class CliTerminalProgress(
    val id: Long,
    val timestampMs: Long,
    val text: String,
    val tone: CliLineTone,
)

/**
 * Key→value row of block output; [flagCountry] draws the only graphic allowed in the log besides
 * the app icons of [packages]. A null [value] makes the row a plain full-width line — a message
 * that has no "how much" half must not be squeezed into the narrow value column.
 */
@Immutable
internal data class CliTerminalRow(
    val key: String,
    val value: String? = null,
    val tone: CliLineTone = CliLineTone.INFO,
    val keyTone: CliLineTone = CliLineTone.DIM,
    val flagCountry: String? = null,
    val packages: List<String> = emptyList(),
)

/**
 * Route ordered by the last canonical command ([CliCommands]) — the terminal's only signal of
 * `bypassVpnTunnel`: [ConnectionSnapshot] carries no preset, and `torActive` is equally true
 * for the TOR-over-VPN chain and for TOR beside VPN.
 */
private enum class CliRouteIntent { NONE, VPN, TOR, VPN_TOR }

private fun routeIntentAfterCommand(text: String, current: CliRouteIntent): CliRouteIntent {
    val token = text.trim().lowercase(Locale.US)
    if (token == CliCommands.STOP || token == CliCommands.CANCEL) return CliRouteIntent.NONE
    // Target is exactly the first token after the prefix: contains() matched "tor"/"vpn"
    // inside profile names (`start vpn -p victoria` registered as a TOR order).
    // `mode …` is a selection request, not proof that the route was applied. Treating it as a
    // start order opened a Tor spinner underneath the confirmation sheet; Cancel then left that
    // row alive forever even though no Tor runtime existed. Runtime phases own progress, while
    // only an actual `start …` command may prime the intended route order.
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

// Cold-boot narration stage: nothing said yet → loading line printed → ready line printed.
private enum class CliBootStage { NONE, LOADING, READY }

/**
 * Terminal line templates, resolved from string resources in composition and handed in as
 * plain values because this state holder has no Context.
 */
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
    val vpnExitKeyIp: String = exitKeyIp,
    val torExitKeyIp: String = exitKeyIp,
    // Localized disconnect/error reasons: the terminal never prints raw snapshot.message
    // (Java exception text, always English) — only these labels or unknown.
    val reasonLabels: Map<AutoConnectReasonCode, String> = emptyMap(),
)

private enum class CliProgressLeg { VPN, TOR, I2P, DISCONNECT }

private const val TERMINAL_MAX_LINES = 120
private const val BOOT_NOTICE_LINE_ID = Long.MIN_VALUE
private const val COLD_START_HOLD_LIMIT = TERMINAL_MAX_LINES
private const val MS_PER_HOUR = 3_600_000L
private const val STEP_INDENT = "  "

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

/**
 * Plain state holder (deliberately not a ViewModel): converts connection/Tor/I2P snapshot
 * transitions into scrolling terminal lines, appending only when observable state changes.
 * Each leg narrates itself ([onConnection] VPN, [onTorPhase], [onI2pPhase]); in a TOR-over-VPN
 * chain the VPN steps print first simply by snapshot arrival order.
 * Multi-line command replies queue via [emitBlock] and leave one row per tick ([drainBlockRow]).
 */
// This is the terminal's single transition reducer: connection, Tor, I2P, prompt and journal
// events must share its latches to preserve one deterministic timeline. Splitting two tiny helpers
// out merely to meet the raw count would create a second mutable owner of live-row ordering.
@Suppress("TooManyFunctions", "LargeClass")
internal class CliTerminalState(
    private val strings: CliTerminalStrings,
    // Provider, not a value: the retention setting can change at runtime from cfg.
    private val retentionHours: () -> Int = { DEFAULT_RETENTION_HOURS },
    /**
     * Every line as it lands, for the journal on disk. Only newly committed lines are handed over,
     * never the visible list: the file keeps the whole retention window while [lines] keeps the
     * much shorter screen window, so writing the screen back would truncate the journal to it.
     *
     * Called on whatever thread committed the line — the caller does the work off it.
     */
    private val onCommitted: (CliTerminalLine) -> Unit = {},
    /** Deletes the persisted journal when the visible history is explicitly cleared. */
    private val onCleared: () -> Unit = {},
    /**
     * Whether this terminal starts held (see [coldStartHeld]). Always true in the app — the hold is
     * what a cold start IS. A test about what the narration says, rather than about when it becomes
     * visible, opts out so its assertions are about the lines and not about the release.
     */
    startsHeld: Boolean = true,
) {

    val lines = mutableStateListOf<CliTerminalLine>()

    /** The single live row rendered below [lines]; phase changes update it rather than append. */
    var progress: CliTerminalProgress? by mutableStateOf(null)
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

    /**
     * Cold start publishes the log ONCE, and until it does nothing this session narrates reaches
     * the screen.
     *
     * The journal is read from disk asynchronously and belongs ABOVE everything this run prints, so
     * the old order — print the session's boot lines immediately, splice the history in front of
     * them a frame or two later — moved every visible line down at once. That is the jump. Held
     * back, the cold start shows a single notice while the store decrypts and then the whole log
     * arrives in one layout pass.
     */
    private var coldStartHeld = startsHeld
    private var restoredForColdStart: List<CliTerminalLine>? = null
    private var historyCleared = false

    // Together these frame joint TOR+VPN work: chain (VPN then TOR) or two parallel legs.
    private var vpnLegUp = false
    private var routeIntent = CliRouteIntent.NONE
    private var vpnTorExpected = false

    // The bridge can publish a validation-grade VPN identity immediately before CONNECTED. Keep
    // the CONNECTING generation boundary so that result stays current even though CONNECTED gets
    // a slightly newer lastChangeAt (the deferred whole-device VPN+Tor startup does exactly this).
    internal var vpnIdentityNotBeforeMs: Long? = null

    // A deferred Tor route may skip CONNECTING, so any later phase must still open the same live row.
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
        updateProgress(CliProgressLeg.TOR, text, tone)
    }
    internal val firewallReminder = CliFirewallReminder { message ->
        note(message, CliLineTone.INFO)
    }

    /** Compose observes this token and advances at most one Tor stage after a readable pause. */
    val torNarrationRevision: Int
        get() = torNarration.revision

    val torNarrationPending: Boolean
        get() = torNarration.hasQueuedStages || (torNarration.isLookupVisible && pendingTorIdentity != null)

    fun welcome(versionName: String) {
        welcomeVersionName = versionName
        if (welcomed) return
        welcomed = true
        append("✻ FoxHole Guard · $versionName", CliLineTone.ACCENT)
    }

    /**
     * Cold-boot narration under the banner: while profiles are still decrypting the first line
     * says so, and the ready line prints exactly once when the store opens. A warm re-entry
     * arrives with [profilesLoaded] already true and prints only the ready line.
     *
     * The decrypting notice is written straight onto the screen and never into the journal: it is
     * the one thing a cold start shows (see [coldStartHeld]), and it describes this run rather than
     * an event worth keeping. It is dropped again the moment the real log is published.
     */
    fun onBootStage(profilesLoaded: Boolean) {
        if (profilesLoaded) {
            if (bootStage == CliBootStage.READY) return
            bootStage = CliBootStage.READY
            append(strings.bootReady, CliLineTone.OK)
            // The store opening is the other half of the release; re-offer whatever the journal
            // read has already left waiting.
            restoredForColdStart?.let(::onJournalRestored)
        } else if (bootStage == CliBootStage.NONE) {
            bootStage = CliBootStage.LOADING
            lines.add(
                CliTerminalLine(
                    id = BOOT_NOTICE_LINE_ID,
                    timestampMs = System.currentTimeMillis(),
                    text = strings.bootLoading,
                    tone = CliLineTone.DIM,
                ),
            )
        }
    }

    /**
     * The journal read has landed — and, re-entered from the two callers above, the point where the
     * cold-start hold is released.
     *
     * Release needs BOTH signals: the history from disk and the profile store reporting open. The
     * screen then changes exactly once. The [COLD_START_HOLD_LIMIT] clause is the escape for a
     * store that never opens — a late log beats a permanently blank one.
     */
    fun onJournalRestored(restored: List<CliTerminalLine>) {
        val accepted = if (historyCleared) emptyList() else restored
        if (!coldStartHeld) {
            // A late read on an already-published terminal is an ordinary restore.
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
        // The notice goes with the hold: what replaces it is the log it was standing in for.
        lines.clear()
        restoreLines(accepted)
        session.forEach(::commit)
    }

    /** Clears the journal, then restores the two canonical session facts: version and readiness. */
    fun clearHistory() {
        historyCleared = true
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
            append("✻ FoxHole Guard · $versionName", CliLineTone.ACCENT)
        }
        if (bootStage == CliBootStage.READY) {
            append(strings.bootReady, CliLineTone.OK)
        }
    }

    fun onConnection(snapshot: ConnectionSnapshot) {
        // While CONNECTED the identity is exactly what `connectedLine` renders. Tor attaching to a
        // live tunnel flips `torActive` and rewrites `message`, which made the key change and
        // printed "connection established" a second time — the Tor leg narrates itself in
        // onTorPhase.
        val key = when (snapshot.state) {
            ConnectionState.CONNECTED -> "${snapshot.state}|${snapshot.profileName}|${snapshot.protocolHint}"
            else ->
                "${snapshot.state}|${snapshot.profileName}|${snapshot.torActive}|" +
                    "${snapshot.teardownPhase}|${snapshot.message}"
        }
        observedProfileId = snapshot.profileId
        if (shouldIgnoreSettledInPlaceReload(snapshot)) {
            // A service-owned hot apply temporarily publishes RECONNECTING while the already-live
            // route is validated. Its proven identity remains authoritative; starting a new row
            // here left a permanent "checking connection" spinner after validation succeeded.
            lastConnectionKey = key
            return
        }
        // Route framing reads the live tunnel even when the line itself deduped away.
        vpnLegUp = snapshot.state == ConnectionState.CONNECTED &&
            snapshot.profileId != TOR_ONLY_PROFILE_ID &&
            snapshot.profileId != LOCAL_GUARD_PROFILE_ID
        if (key == lastConnectionKey) return
        // The very first snapshot is the app's boot state, not a transition - nothing happened yet.
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
        if (snapshot.state == ConnectionState.ERROR || snapshot.state == ConnectionState.IDLE) {
            cancelTorProgress()
            clearProgress(CliProgressLeg.DISCONNECT)
        }
        // Tor-only runtime narrates via onTorPhase: VPN wording would be a lie for it.
        if (snapshot.profileId == TOR_ONLY_PROFILE_ID && snapshot.state != ConnectionState.DISCONNECTING) return
        // The local guard (firewall / DNS / I2P transport) publishes a regular CONNECTED for its
        // own VpnService — a filter, not a connection; announcing it faked a tunnel-up line.
        if (snapshot.profileId == LOCAL_GUARD_PROFILE_ID && snapshot.state != ConnectionState.DISCONNECTING) return
        when (snapshot.state) {
            // A user START already echoed its command at the prompt. Subscription refresh and an
            // I2P/local-guard handoff may take much longer than a debounce window, so the explicit
            // route intent owns the whole transaction until success, failure or STOP. Autostarts
            // have no such intent and print the canonical command here.
            ConnectionState.CONNECTING -> {
                settledProfileId = null
                lastVpnIdentity = null
                pendingVpnIdentity = null
                vpnIdentityNotBeforeMs = snapshot.lastChangeAt
                // A Tor/I2P phase may arrive in the same bridge publication. Keep the VPN row in
                // front until both its runtime and validation identity are proven.
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
                updateProgress(CliProgressLeg.VPN, strings.connecting, CliLineTone.WARN)
            }
            // The runtime is up, but the final row is committed only with its vpn-bound identity.
            // Until that fetch lands the same live row changes from “establishing” to “connected”.
            ConnectionState.CONNECTED -> {
                if (vpnIdentityNotBeforeMs == null) {
                    // Compose may coalesce the short CONNECTING edge. Validation publishes its
                    // identity immediately before CONNECTED, so keep that small ordered window
                    // without accepting an arbitrary retained address from an older route.
                    vpnIdentityNotBeforeMs = terminalConnectedIdentityNotBefore(snapshot.lastChangeAt)
                }
                vpnTorExpected = vpnTorExpected || snapshot.torActive || routeIntent == CliRouteIntent.VPN_TOR
                val pendingIdentity = pendingVpnIdentity
                vpnAwaitingIdentity = lastVpnIdentity == null && pendingIdentity == null
                if (pendingIdentity != null) {
                    commitVpnIdentity(pendingIdentity)
                } else if (vpnAwaitingIdentity) {
                    updateProgress(CliProgressLeg.VPN, strings.connected, CliLineTone.OK)
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
                updateProgress(CliProgressLeg.VPN, text, CliLineTone.WARN)
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
                    CliLineTone.WARN,
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
        // Raw message (exception text) goes as an indented DIM line only when no coded reason.
        if (reason == null) {
            snapshot.message?.takeIf { it.isNotBlank() }?.let {
                append(STEP_INDENT + it, CliLineTone.DIM)
            }
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
            // A start command may have primed the live row before the bridge publishes its first
            // snapshot. OFFLINE is still authoritative and must clear that row without printing a
            // fake "Tor stopped" event for the boot snapshot.
            cancelTorProgress()
            return
        }
        when (phase.phase) {
            TorNetworkPhase.CONNECTING -> openTorLeg()
            TorNetworkPhase.BUILDING_CIRCUITS -> openTorLeg(catchUp = true)
            // Bootstrap may skip CONNECTING and BUILDING_CIRCUITS. The current phase is still
            // reflected in the same row; it never manufactures the skipped phases as history.
            TorNetworkPhase.CONNECTED -> openTorLeg(catchUp = true)
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

    /**
     * Opens the TOR leg: command echo (autostarts only), a "TOR beside VPN" header for two
     * parallel legs, then the connecting step. Opens ONCE per leg — a leg ends at OFFLINE, which
     * clears the flag — so an order and a later CONNECTING phase cannot print the step twice.
     *
     * [catchUp] is the deferred-route path. "All traffic through TOR inside VPN" starts VPN-first
     * on purpose (`shouldDeferTorRouteForVpnFirstStartup`): the first session carries no Tor route,
     * so the bridge derives OFFLINE from `torActive == false`, and by the time the deferred hot
     * reload lands the session is already CONNECTED with `torActive` true. The phase steps straight
     * from OFFLINE to CONNECTED, CONNECTING never happens, and the whole leg — the step the user is
     * waiting to read — printed nothing until the final line. A catch-up opens the leg late: it
     * no-ops once the leg is open, and prints no command echo, because the user ordered VPN+TOR
     * once and a second `start TOR` prompt would claim an order they never gave.
     *
     * Deliberately keyed on the leg never having been opened, never on the Tor exit ip: the pill
     * reads ON without waiting for that probe, so gating on it would leave the log saying
     * "connecting" while the pill already says ON.
     */
    private fun openTorLeg(catchUp: Boolean = false) {
        if (!torLegOpened) {
            torLegOpened = true
            torResultCommitted = false
            lastTorIdentity = null
            torNarration.reset()
            if (!catchUp && routeIntent == CliRouteIntent.NONE && !isRecentModeCommand(lastModeCommandAtMs)) {
                append(CliCommands.START_TOR, CliLineTone.PLAIN, prompt = true)
            }
        }
        syncTorProgress()
    }

    /** VPN+Tor is narrated sequentially: the Tor row appears only after the VPN identity row. */
    private fun syncTorProgress() {
        if (!torLegOpened || vpnAwaitingIdentity || torResultCommitted) return
        when (lastTorPhase) {
            TorNetworkPhase.CONNECTING -> requestTorNarration(CliTorNarrationStage.CONNECTING)
            TorNetworkPhase.BUILDING_CIRCUITS -> requestTorNarration(CliTorNarrationStage.CIRCUITS)
            // The current bridge can jump straight to CONNECTED after a deferred hot reload. The
            // route really did connect and construct circuits; replay those completed stages in
            // one live row instead of skipping directly to a permanent "Tor connected" spinner.
            TorNetworkPhase.CONNECTED -> requestTorNarration(CliTorNarrationStage.CONNECTED)
            TorNetworkPhase.OFFLINE, null -> requestTorNarration(CliTorNarrationStage.CONNECTING)
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

    /** I2P is the final leg in a combined run: VPN identity, then Tor identity, then I2P. */
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
                updateProgress(CliProgressLeg.I2P, strings.i2pStarting, CliLineTone.WARN)
            I2pNetworkPhase.DISCOVERING_PEERS ->
                updateProgress(CliProgressLeg.I2P, strings.i2pDiscovering, CliLineTone.WARN)
            I2pNetworkPhase.BUILDING_TUNNELS ->
                updateProgress(
                    CliProgressLeg.I2P,
                    if (current.tunnelsBuilt > 0) {
                        String.format(Locale.US, strings.i2pTunnelsCount, current.tunnelsBuilt)
                    } else {
                        strings.i2pTunnels
                    },
                    CliLineTone.WARN,
                )
            I2pNetworkPhase.CONNECTED -> {
                updateProgress(CliProgressLeg.I2P, strings.i2pConnected, CliLineTone.OK)
                append(
                    // A CONNECTED Tor phase proves only that the route exists. It is not the
                    // release-grade identity proof: after a bounded Tor-IP failure, claiming
                    // "VPN + Tor + I2P" produced a success-shaped final row on the Pixel. Name
                    // Tor only after its verified exit identity has actually been committed.
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
                // Tunnel validation can publish the identity while CONNECTING. Retain it, but do
                // not claim success until the runtime itself reaches CONNECTED.
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
            // VPN is independently proven before Tor starts. Commit that truthful boundary now,
            // then reuse the same live row for the Tor leg; a later Tor failure must not erase it.
            commitPendingVpnIdentity()
            append(strings.vpnEstablished, CliLineTone.VPN)
            if (!torLegOpened) openTorLeg(catchUp = true)
            resumeTorAfterVpnIdentityOutcome()
        } else {
            finishConnectionSuccess()
        }
    }

    /** Advances exactly one queued stage; called by the UI after a short readable pause. */
    fun advanceTorNarration() {
        if (!torLegOpened || vpnAwaitingIdentity) return
        if (torNarration.advance()) return
        if (torNarration.isLookupVisible) {
            pendingTorIdentity?.let(::commitTorIdentity)
        }
    }

    private fun requestTorNarration(target: CliTorNarrationStage) {
        if (!torLegOpened) return
        torNarration.request(target, allowDisplay = !vpnAwaitingIdentity)
        if (target == CliTorNarrationStage.LOOKUP && pendingTorIdentity != null) torNarration.signalIdentityReady()
    }

    private fun commitTorIdentity(info: IpInfo) {
        pendingTorIdentity = null
        lastTorIdentity = info.routeIdentityKey()
        torResultCommitted = true
        torNarration.reset(completedThrough = CliTorNarrationStage.LOOKUP)
        clearProgress(CliProgressLeg.TOR)
        commitPendingVpnIdentity()
        appendRouteEstablished(
            label = strings.torExitKeyIp,
            info = info,
            tone = CliLineTone.TOR,
        )
        finishConnectionSuccess(identitiesCommitted = true)
        syncI2pProgress()
    }

    /** A VPN identity result (success or bounded failure) releases the queued Tor timeline. */
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

    /**
     * Last-resort UI fence. Runtime/Tor probes own their shorter deadlines; this guarantees that a
     * dropped publication still ends with a truthful result and cannot leave the live row forever.
     */
    internal fun expireProgress(progressId: Long) {
        if (progress?.id != progressId) return
        when (progressLeg) {
            CliProgressLeg.VPN -> {
                // Runtime establishment has its own supervisor deadline. This fence starts only
                // after CONNECTED proves the route and the remaining wait is display identity.
                if (!vpnLegUp) return
                vpnAwaitingIdentity = false
                pendingVpnIdentity = null
                clearProgress(CliProgressLeg.VPN)
                append(strings.vpnExitFailed, CliLineTone.ERR)
                if (vpnTorExpected || torLegOpened) {
                    if (!torLegOpened) openTorLeg(catchUp = true)
                    resumeTorAfterVpnIdentityOutcome()
                } else {
                    routeIntent = CliRouteIntent.NONE
                    vpnTorExpected = false
                    syncI2pProgress()
                }
            }
            CliProgressLeg.TOR -> {
                // Bootstrap/circuit construction may legitimately take longer; the operation
                // supervisor owns that deadline. Only a CONNECTED route may time out on identity.
                if (lastTorPhase != TorNetworkPhase.CONNECTED) return
                finishTorIdentityFailure(lastTorProbeState.generation)
            }
            CliProgressLeg.I2P,
            CliProgressLeg.DISCONNECT,
            null,
            -> Unit
        }
    }

    /** Spinner disappears first; verified route facts and the final success line follow it. */
    private fun finishConnectionSuccess(identitiesCommitted: Boolean = false) {
        progress = null
        progressLeg = null
        if (!identitiesCommitted) commitPendingVpnIdentity()
        append(strings.tunnelUp, CliLineTone.OK)
        settledProfileId = observedProfileId
        routeIntent = CliRouteIntent.NONE
        vpnTorExpected = false
    }

    private fun commitPendingVpnIdentity() {
        pendingVpnIdentity?.let { info ->
            appendRouteEstablished(
                label = strings.vpnExitKeyIp,
                info = info,
                tone = CliLineTone.VPN,
            )
        }
        pendingVpnIdentity = null
    }

    private fun appendRouteEstablished(
        label: String,
        info: IpInfo,
        tone: CliLineTone,
    ) {
        val identity = info.routeIdentityValue()
        append(
            text = label,
            tone = tone,
            flagCountry = info.countryCode,
            value = identity,
            valueTone = tone,
        )
    }

    fun note(text: String, tone: CliLineTone = CliLineTone.DIM) = append(text, tone)

    /**
     * Cold-start/new-install reminder for unresolved firewall quarantine. The package set is the
     * identity, so ordinary recomposition and tab changes never duplicate the line; resolving all
     * entries resets the latch and a later install can announce itself again.
     */
    // Command-reply rows wait here and leave the log ONE per panel tick, not as a batch —
    // a batch would fall into the held queue in a single frame.
    private val blockQueue = mutableStateListOf<CliTerminalRow>()

    /** True while block rows remain — the panel has something to tick. */
    val blockPending: Boolean
        get() = blockQueue.isNotEmpty()

    /**
     * Queues a multi-row command reply (e.g. `status`). Rows print only as the panel ticks
     * [drainBlockRow], and only while the prompt is free — command first, then its reply.
     */
    fun emitBlock(rows: List<CliTerminalRow>) {
        if (rows.isEmpty()) return
        blockQueue.addAll(rows)
    }

    /** Prints one queued block row; `false` = queue empty. */
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
        )
        return true
    }

    /** Highest line id whose typing animation has already played; see [claimTyping]. */
    internal var lastTypedLineId = -1L

    /**
     * The command currently being "typed" at the bottom `fox > █` prompt. The panel
     * animates it character by character and calls [commitPrompt] when done; only then
     * the command becomes a log line. One command types at a time - a new one commits
     * the previous instantly.
     */
    var promptText: String? by mutableStateOf(null)
        private set

    /**
     * How much of [promptText] has been typed. Kept here rather than in the panel: leaving the
     * home screen disposes that composable, and a locally remembered counter restarted the
     * animation from zero every time the user came back.
     */
    var promptTypedCount: Int by mutableIntStateOf(0)

    /**
     * A user-initiated command: typed live at the prompt, then committed to the log.
     * [output] lands right AFTER the commit, like a real terminal reply; while the command
     * types, ALL status lines are held and flush only after the commit.
     */
    fun command(
        text: String,
        output: String? = null,
        outputTone: CliLineTone = CliLineTone.INFO,
        outputCountry: String? = null,
    ) {
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
        // The reply is stamped with the command, not with the commit: it prints right after it,
        // ahead of the status lines that arrived while the command was typing.
        pendingOutput = output?.let { CliTerminalLine(issuedAtMs, it, outputTone, flagCountry = outputCountry) }
    }

    fun commitPrompt() {
        promptText?.let(::appendCommand)
        promptText = null
        flushQueues()
        // An ORDER opens the Tor leg by itself instead of waiting for a CONNECTING phase that may
        // never arrive. Switching an already-connected tunnel to VPN+TOR is a hot reload: the
        // connection never leaves CONNECTED and the route is applied in place, so the bridge
        // reports OFFLINE and then, if Tor came up at all, CONNECTED — and the log showed the
        // command with nothing under it, then `stop`. Opened here, after the command line and after
        // the lines held while it typed, so it reads under them; the leg opens once, so a phase
        // that DOES report CONNECTING has already opened it and this is a no-op.
        //
        // A VPN+TOR order on an IDLE runtime is deliberately not opened: that chain is built from
        // nothing, the VPN steps come first by canon, and the Tor leg opens when its own phase
        // says so. Only a chain ordered onto a tunnel already up has no VPN steps left to print.
        if (shouldOpenTorLeg(routeIntent, vpnLegUp)) {
            openTorLeg(catchUp = true)
        }
    }

    private var pendingOutput: CliTerminalLine? = null
    private val held = mutableListOf<CliTerminalLine>()
    private var nextLineId = 0L
    private var lastUserCommandAtMs = 0L
    private var lastModeCommandAtMs = 0L

    // Pending single-line output first, then the held burst — the order both call sites used.
    private fun flushQueues() {
        pendingOutput?.let(::commit)
        pendingOutput = null
        held.forEach(::commit)
        held.clear()
    }

    /** Starts a connection or replaces the phase of its one existing live row in place. */
    private fun updateProgress(
        leg: CliProgressLeg,
        text: String,
        tone: CliLineTone,
    ) {
        val current = progress
        val reusesCurrentConnection =
            current != null &&
                (progressLeg == leg || progressLeg == CliProgressLeg.VPN && leg == CliProgressLeg.TOR)
        progress = if (reusesCurrentConnection) {
            current.copy(text = text, tone = tone)
        } else {
            CliTerminalProgress(
                id = nextProgressId++,
                timestampMs = System.currentTimeMillis(),
                text = text,
                tone = tone,
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
        clearProgress(CliProgressLeg.TOR)
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

    /**
     * The prompt commits when the typing animation ends, but the command happened when the user
     * pressed the button: stamping the commit printed `[18:15:30] > start VPN` above the
     * `[18:15:28] connecting` line that had been held while it typed.
     */
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
    ) {
        lines.pruneBefore(System.currentTimeMillis() - retentionHours() * MS_PER_HOUR)
        val line = CliTerminalLine(
            timestampMs = System.currentTimeMillis(),
            text = text,
            tone = tone,
            prompt = prompt,
            flagCountry = flagCountry,
            value = value,
            valueTone = valueTone,
            packages = packages,
        )
        // Cold start holds EVERYTHING, prompt echoes included: the log is published in one piece
        // or not at all.
        if (coldStartHeld) {
            held.add(line)
            // A session that keeps narrating past the screenful releases itself; see the escape
            // clause in [onJournalRestored].
            restoredForColdStart?.let(::onJournalRestored)
            return
        }
        // A command is still typing at the prompt: status lines wait for its commit.
        if (promptText != null && !prompt) {
            held.add(line)
            return
        }
        commit(line)
    }

    /**
     * Has this session said anything yet? The three "…stopped" lines ask before printing, so a boot
     * that opens on an already-off module does not announce it stopping.
     *
     * Not `lines.isNotEmpty()`: during the cold-start hold the visible list carries only the
     * decrypting notice while everything narrated so far waits in [held], and the question is about
     * what was narrated, not about what is on screen.
     */
    private val hasNarrated: Boolean
        get() = held.isNotEmpty() || lines.any { line -> line.id != BOOT_NOTICE_LINE_ID }

    private fun commit(line: CliTerminalLine) {
        // The log reads as a timeline, so it must never step back: held lines keep the time of the
        // event that produced them while prompt lines commit on their own schedule, and an
        // autostart echo can even overtake lines that are still held.
        val stampMs = maxOf(line.timestampMs, lines.lastOrNull()?.timestampMs ?: line.timestampMs)
        val committed = line.copy(timestampMs = stampMs, id = nextLineId++)
        lines.add(committed)
        // Head-prune is the SCREEN's window only. The journal keeps its own, longer one, so a line
        // scrolled off the top here is still on disk until it expires.
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

// Canonical-command route tokens from CliCommands, always lowercase.
private const val START_PREFIX = "start "
private const val MODE_PREFIX = "mode "
private const val TOKEN_VPN_TOR = "vpn+tor"
private const val TOKEN_TOR = "tor"
private const val TOKEN_VPN = "vpn"
private const val MODE_COMMAND_DEDUPE_MS = 6_000L
private const val CONNECTED_IDENTITY_PUBLISH_LEAD_MS = 5_000L

/** Validation can publish just before CONNECTED; only this bounded lead is accepted on coalescing. */
internal fun terminalConnectedIdentityNotBefore(connectedAtMs: Long): Long =
    (connectedAtMs - CONNECTED_IDENTITY_PUBLISH_LEAD_MS).coerceAtLeast(0L)

private fun shouldOpenTorLeg(routeIntent: CliRouteIntent, vpnLegUp: Boolean): Boolean =
    routeIntent == CliRouteIntent.TOR || (routeIntent == CliRouteIntent.VPN_TOR && vpnLegUp)

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

/**
 * Timestamp prefix without a trailing space: the renderer gives it a fixed-width slot —
 * proportional LanaPixel cannot align with spaces.
 */
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

private fun IpInfo.routeIdentityValue(): String =
    listOfNotNull(
        ip.takeIf(String::isNotBlank),
        countryCode?.takeIf(String::isNotBlank)?.uppercase(Locale.US),
        city?.takeIf(String::isNotBlank),
    ).joinToString(" · ")

/**
 * Claims the typing animation for a line, once and forever.
 *
 * The panel is disposed every time the user leaves home, so a counter remembered inside it
 * restarted from zero on the way back and the last line retyped itself on every return. The log is
 * history: a line types when it arrives and never again, and only the state holder — which outlives
 * the panel — can know which lines already did.
 */
internal fun CliTerminalState.claimTyping(lineId: Long): Boolean {
    if (lineId <= lastTypedLineId) return false
    lastTypedLineId = lineId
    return true
}

/**
 * Puts the journal read back from disk in front of whatever this session has already narrated.
 *
 * Restored lines are always older than anything the running session printed, so they go at the
 * head, and the screen's own window is re-applied afterwards — the file keeps the whole retention
 * period, this list keeps the last [CliTerminalState.SCREEN_LINE_LIMIT] of it.
 *
 * Ids are re-issued from the negative side: [CliTerminalLine.id] is a LazyColumn key handed out by
 * the running state's counter, which only ever goes up from zero, so negative keys cannot collide
 * with it. They also sort below every live line, which is exactly what they are.
 */
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
