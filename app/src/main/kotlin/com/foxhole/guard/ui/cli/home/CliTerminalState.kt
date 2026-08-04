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
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.guard.ui.cli.CliCommands
import com.foxhole.guard.ui.cli.CliFormat
import java.util.Locale

/** Log-line tones. [VPN]/[TOR] are the semantic route colors matching the home facts panel. */
internal enum class CliLineTone { PLAIN, DIM, ACCENT, OK, WARN, ERR, INFO, VPN, TOR }

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
    // Monotonic LazyColumn key: timestampMs collides within a ms, and head-prune without a key
    // rebound every visible row. Assigned only by [CliTerminalState.commit].
    val id: Long = 0L,
)

/** Key→value row of block output; [flagCountry] draws the only graphic allowed in the log. */
@Immutable
internal data class CliTerminalRow(
    val key: String,
    val value: String,
    val tone: CliLineTone = CliLineTone.INFO,
    val keyTone: CliLineTone = CliLineTone.DIM,
    val flagCountry: String? = null,
)

/**
 * Route ordered by the last canonical command ([CliCommands]) — the terminal's only signal of
 * `bypassVpnTunnel`: [ConnectionSnapshot] carries no preset, and `torActive` is equally true
 * for the TOR-over-VPN chain and for TOR beside VPN.
 */
private enum class CliRouteIntent { NONE, VPN, TOR, VPN_TOR }

/**
 * Terminal line templates, resolved from string resources in composition and handed in as
 * plain values because this state holder has no Context.
 */
@Immutable
internal data class CliTerminalStrings(
    val ready: String,
    val connecting: String,
    val tunnelUp: String,
    val tunnelUpNamed: String,
    val reconnecting: String,
    val error: String,
    val unknown: String,
    val closed: String,
    val torBesideVpn: String,
    val torConnecting: String,
    val torCircuits: String,
    val torConnected: String,
    val torStopped: String,
    val i2pStarting: String,
    val i2pDiscovering: String,
    val i2pTunnels: String,
    val i2pConnected: String,
    val i2pStopped: String,
    val exitKeyIp: String,
    val exitKeyGeo: String,
    val exitKeyIsp: String,
    // Localized disconnect/error reasons: the terminal never prints raw snapshot.message
    // (Java exception text, always English) — only these labels or unknown.
    val reasonLabels: Map<AutoConnectReasonCode, String> = emptyMap(),
)

/**
 * Plain state holder (deliberately not a ViewModel): converts connection/Tor/I2P snapshot
 * transitions into scrolling terminal lines, appending only when observable state changes.
 * Each leg narrates itself ([onConnection] VPN, [onTorPhase], [onI2pPhase]); in a TOR-over-VPN
 * chain the VPN steps print first simply by snapshot arrival order.
 * Multi-line command replies queue via [emitBlock] and leave one row per tick ([drainBlockRow]).
 */
internal class CliTerminalState(
    private val strings: CliTerminalStrings,
    // Provider, not a value: the retention setting can change at runtime from cfg.
    private val retentionHours: () -> Int = { DEFAULT_RETENTION_HOURS },
) {

    val lines = mutableStateListOf<CliTerminalLine>()

    private var lastConnectionKey: String? = null
    private var lastTorPhase: TorNetworkPhase? = null
    private var lastTorProgress: Int = -1
    private var lastI2pPhase: I2pNetworkPhase? = null
    private var lastIp: String? = null
    private var welcomed = false

    // Together these frame joint TOR+VPN work: chain (VPN then TOR) or two parallel legs.
    private var vpnLegUp = false
    private var routeIntent = CliRouteIntent.NONE

    // "network connected" prints once per network session, strictly BEFORE circuit-build lines.
    private var torNetworkAnnounced = false
    private var i2pNetworkAnnounced = false

    fun welcome(versionName: String) {
        if (welcomed) return
        welcomed = true
        append("✻ FoxHole Guard · $versionName", CliLineTone.ACCENT)
        append(strings.ready, CliLineTone.DIM)
    }

    fun onConnection(snapshot: ConnectionSnapshot) {
        val key = "${snapshot.state}|${snapshot.profileName}|${snapshot.torActive}|${snapshot.message}"
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

    private fun appendConnectionTransition(snapshot: ConnectionSnapshot) {
        // Tor-only runtime narrates via onTorPhase: VPN wording would be a lie for it.
        if (snapshot.profileId == TOR_ONLY_PROFILE_ID) return
        // The local guard (firewall / DNS / I2P transport) publishes a regular CONNECTED for its
        // own VpnService — a filter, not a connection; announcing it faked a tunnel-up line.
        if (snapshot.profileId == LOCAL_GUARD_PROFILE_ID) return
        when (snapshot.state) {
            // A user START already echoed its command at the prompt (6 s window) — the
            // state-transition auto-command would duplicate it. Autostarts print the canonical one.
            ConnectionState.CONNECTING -> {
                if (!recentUserCommand()) {
                    val command = if (snapshot.torActive) {
                        CliCommands.startVpnTor(snapshot.profileName)
                    } else {
                        CliCommands.startVpn(snapshot.profileName)
                    }
                    append(command, CliLineTone.PLAIN, prompt = true)
                }
                append(STEP_INDENT + strings.connecting, CliLineTone.WARN)
            }
            // One terse fact line: profile plus protocol (same token the home facts panel shows).
            ConnectionState.CONNECTED -> append(connectedLine(snapshot), CliLineTone.OK)
            ConnectionState.RECONNECTING -> {
                val reason = snapshot.localizedReason(strings.reasonLabels)
                val text = if (reason != null) "${strings.reconnecting} · $reason" else strings.reconnecting
                append(STEP_INDENT + text, CliLineTone.WARN)
            }
            ConnectionState.ERROR -> appendError(snapshot)
            ConnectionState.IDLE -> if (lines.isNotEmpty()) append(strings.closed, CliLineTone.DIM)
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

    /** "connection established" plus profile/protocol facts when known. */
    private fun connectedLine(snapshot: ConnectionSnapshot): String {
        val facts = listOfNotNull(
            snapshot.profileName,
            snapshot.protocolHint
                ?.takeIf { it != ProtocolHint.UNKNOWN }
                ?.name?.lowercase(Locale.US),
        )
        return if (facts.isEmpty()) {
            strings.tunnelUp
        } else {
            String.format(Locale.US, strings.tunnelUpNamed, facts.joinToString(" · "))
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
        if (first && phase.phase == TorNetworkPhase.OFFLINE) return
        when (phase.phase) {
            TorNetworkPhase.CONNECTING -> openTorLeg()
            TorNetworkPhase.BUILDING_CIRCUITS -> {
                announceTorNetworkUp()
                append(STEP_INDENT + strings.torCircuits, CliLineTone.WARN)
            }
            // Bootstrap may skip the circuits phase — then the announcement prints here.
            TorNetworkPhase.CONNECTED -> announceTorNetworkUp()
            TorNetworkPhase.OFFLINE -> {
                torNetworkAnnounced = false
                if (lines.isNotEmpty()) append(strings.torStopped, CliLineTone.DIM)
            }
        }
    }

    /**
     * Opens the TOR leg: command echo (autostarts only), a "TOR beside VPN" header for two
     * parallel legs, then the connecting line.
     */
    private fun openTorLeg() {
        torNetworkAnnounced = false
        if (!recentUserCommand()) append(CliCommands.START_TOR, CliLineTone.PLAIN, prompt = true)
        if (vpnLegUp && routeIntent == CliRouteIntent.TOR) {
            append(STEP_INDENT + strings.torBesideVpn, CliLineTone.WARN)
        }
        append(STEP_INDENT + strings.torConnecting, CliLineTone.WARN)
    }

    /** "TOR network connected" — announced before circuit progress. */
    private fun announceTorNetworkUp() {
        if (torNetworkAnnounced) return
        torNetworkAnnounced = true
        append(strings.torConnected, CliLineTone.OK)
    }

    fun onI2pPhase(snapshot: I2pPhaseSnapshot) {
        if (snapshot.phase == lastI2pPhase) return
        val first = lastI2pPhase == null
        lastI2pPhase = snapshot.phase
        if (first && snapshot.phase == I2pNetworkPhase.OFFLINE) return
        when (snapshot.phase) {
            I2pNetworkPhase.STARTING -> {
                i2pNetworkAnnounced = false
                append(STEP_INDENT + strings.i2pStarting, CliLineTone.WARN)
            }
            I2pNetworkPhase.DISCOVERING_PEERS ->
                append(STEP_INDENT + strings.i2pDiscovering, CliLineTone.WARN)
            I2pNetworkPhase.BUILDING_TUNNELS -> {
                announceI2pNetworkUp()
                append(STEP_INDENT + strings.i2pTunnels, CliLineTone.WARN)
            }
            I2pNetworkPhase.CONNECTED -> announceI2pNetworkUp()
            I2pNetworkPhase.OFFLINE -> {
                i2pNetworkAnnounced = false
                if (lines.isNotEmpty()) append(strings.i2pStopped, CliLineTone.DIM)
            }
        }
    }

    /** Same rule as TOR: announced before tunnel progress. */
    private fun announceI2pNetworkUp() {
        if (i2pNetworkAnnounced) return
        i2pNetworkAnnounced = true
        append(strings.i2pConnected, CliLineTone.OK)
    }

    /**
     * Exit summary as a key→value block (ip / geo / isp), queued like a `status` reply —
     * row by row, after the prompt commits.
     */
    fun onIpInfo(ipInfo: IpInfo?) {
        val ip = ipInfo?.ip ?: return
        if (ip == lastIp) return
        lastIp = ip
        val geo = listOfNotNull(
            ipInfo.countryCode?.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
            ipInfo.city?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        emitBlock(
            buildList {
                add(CliTerminalRow(key = strings.exitKeyIp, value = ip, tone = CliLineTone.INFO))
                if (geo.isNotEmpty()) {
                    add(
                        CliTerminalRow(
                            key = strings.exitKeyGeo,
                            value = geo,
                            tone = CliLineTone.INFO,
                            flagCountry = ipInfo.countryCode,
                        ),
                    )
                }
                ipInfo.isp?.takeIf { it.isNotBlank() }?.let { isp ->
                    add(CliTerminalRow(key = strings.exitKeyIsp, value = isp, tone = CliLineTone.INFO))
                }
            },
        )
    }

    fun note(text: String, tone: CliLineTone = CliLineTone.DIM) = append(text, tone)

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
        )
        return true
    }

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
        captureRouteIntent(text)
        promptText?.let(::appendCommand)
        flushPendingOutput()
        flushHeld()
        promptText = text
        promptTypedCount = 0
        lastUserCommandAtMs = System.currentTimeMillis()
        pendingOutput = output?.let { CliTerminalLine(0L, it, outputTone, flagCountry = outputCountry) }
    }

    /**
     * Route from the canonical command: `start`/`mode` carry the target, `stop`/`cancel`
     * clear it. Command tokens are never localized ([CliCommands]).
     */
    private fun captureRouteIntent(text: String) {
        val token = text.trim().lowercase(Locale.US)
        if (token == CliCommands.STOP || token == CliCommands.CANCEL) {
            routeIntent = CliRouteIntent.NONE
            return
        }
        // Target is exactly the first token after the prefix: contains() matched "tor"/"vpn"
        // inside profile names (`start vpn -p victoria` registered as a TOR order).
        val target = when {
            token.startsWith(START_PREFIX) -> token.removePrefix(START_PREFIX)
            token.startsWith(MODE_PREFIX) -> token.removePrefix(MODE_PREFIX)
            else -> return
        }.trim().substringBefore(' ')
        routeIntent = when (target) {
            TOKEN_VPN_TOR -> CliRouteIntent.VPN_TOR
            TOKEN_TOR -> CliRouteIntent.TOR
            TOKEN_VPN -> CliRouteIntent.VPN
            else -> routeIntent
        }
    }

    fun commitPrompt() {
        promptText?.let(::appendCommand)
        promptText = null
        flushPendingOutput()
        flushHeld()
    }

    private var pendingOutput: CliTerminalLine? = null
    private val held = mutableListOf<CliTerminalLine>()
    private var nextLineId = 0L
    private var lastUserCommandAtMs = 0L

    internal fun recentUserCommand(): Boolean =
        System.currentTimeMillis() - lastUserCommandAtMs < USER_COMMAND_DEDUPE_MS

    private fun flushPendingOutput() {
        pendingOutput?.let { commit(it.copy(timestampMs = System.currentTimeMillis())) }
        pendingOutput = null
    }

    private fun flushHeld() {
        held.forEach(::commit)
        held.clear()
    }

    private fun appendCommand(text: String) = append(text, CliLineTone.PLAIN, prompt = true)

    private fun append(
        text: String,
        tone: CliLineTone,
        prompt: Boolean = false,
        flagCountry: String? = null,
        value: String? = null,
        valueTone: CliLineTone = tone,
    ) {
        pruneExpired()
        val line = CliTerminalLine(
            timestampMs = System.currentTimeMillis(),
            text = text,
            tone = tone,
            prompt = prompt,
            flagCountry = flagCountry,
            value = value,
            valueTone = valueTone,
        )
        // A command is still typing at the prompt: status lines wait for its commit.
        if (promptText != null && !prompt) {
            held.add(line)
            return
        }
        commit(line)
    }

    private fun commit(line: CliTerminalLine) {
        lines.add(line.copy(id = nextLineId++))
        while (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
    }

    private fun pruneExpired() {
        val cutoffMs = System.currentTimeMillis() - retentionHours() * MS_PER_HOUR
        while (lines.isNotEmpty() && lines.first().timestampMs < cutoffMs) {
            lines.removeAt(0)
        }
    }

    companion object {
        private const val MAX_LINES = 120

        // Single owner of the retention default; CliTerminalPrefs references this.
        internal const val DEFAULT_RETENTION_HOURS = 12
        private const val MS_PER_HOUR = 3_600_000L
        private const val USER_COMMAND_DEDUPE_MS = 6_000L

        // Intermediate steps print indented: command → steps → result, like a real CLI.
        private const val STEP_INDENT = "  "

        // Canonical-command route tokens from CliCommands, always lowercase.
        private const val START_PREFIX = "start "
        private const val MODE_PREFIX = "mode "
        private const val TOKEN_VPN_TOR = "vpn+tor"
        private const val TOKEN_TOR = "tor"
        private const val TOKEN_VPN = "vpn"
    }
}

/**
 * Timestamp prefix without a trailing space: the renderer gives it a fixed-width slot —
 * proportional LanaPixel cannot align with spaces.
 */
internal fun CliTerminalLine.prefix(): String = "[${CliFormat.clock(timestampMs)}]"

private fun ConnectionSnapshot.localizedReason(labels: Map<AutoConnectReasonCode, String>): String? =
    reasonCode?.let(labels::get)
