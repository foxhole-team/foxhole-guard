package com.foxhole.guard.router

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpProgressMonitor
import java.io.FileInputStream
import java.util.UUID
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.CharBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class RouterSshTrustRequired(val key: ByteArray) : IOException("router_host_key_required")
internal class RouterSshFailure : IOException("router_ssh_failed")
internal class RouterSshAuthenticationFailed : IOException("router_ssh_authentication_failed")

internal fun interface RouterSocketProvider { fun createSocket(): Socket }

internal interface RouterSshClient : Closeable {
    fun connect(password: CharArray?)
    fun connectForDiscovery() = connect(null)
    fun connectWithKey(privateKey: ByteArray, passphrase: CharArray) {
        privateKey.fill(0); passphrase.fill('\u0000')
        throw RouterSshAuthenticationFailed()
    }
    fun read(command: RouterReadCommand): ByteArray
    fun internetAccess(): RouterInternetAccess = RouterInternetAccess.UNKNOWN
    fun stageInstallBundle(bundle: RouterDeviceInstallBundle, facts: RouterBundleTransferFacts, requiredRam: Long,
        onProgress: (RouterInstallProgress) -> Unit): RouterRemoteInstallBundle = throw RouterSshFailure()
    fun readCompactBackupBytes(): ByteArray = throw RouterInspectionFailure()
    fun install(script: RouterVerifiedInstallScript, command: RouterInstallCommand)
    fun install(script: RouterVerifiedInstallScript, command: RouterInstallCommand, onProgress: (RouterInstallProgress) -> Unit) {
        onProgress(RouterInstallProgress(RouterInstallProgressStage.INSTALL))
        install(script, command)
    }
    fun bootstrapEnrollment(): ByteArray
}

internal enum class RouterReadCommand(val script: String, val maximumOutputBytes: Int = 262144) {
    INTERNET_PROBE("""
        v4=${'$'}(ip -4 route show default 2>/dev/null) || { printf 'UNKNOWN\n'; exit 0; }
        v6=${'$'}(ip -6 route show default 2>/dev/null) || { printf 'UNKNOWN\n'; exit 0; }
        if [ -z "${'$'}v4${'$'}v6" ]; then printf 'NO_INTERNET\n'; exit 0; fi
        reply=${'$'}(uclient-fetch --no-proxy -T 4 -O /dev/null https://github.com 2>&1)
        code=${'$'}?
        [ "${'$'}{#reply}" -le 16384 ] || { printf 'UNKNOWN\n'; exit 0; }
        if [ "${'$'}code" = 0 ]; then printf 'ONLINE\n'; exit 0; fi
        case "${'$'}reply" in *SSL*|*ssl*|*certificate*|*Certificate*) printf 'UNKNOWN\n'; exit 0 ;; esac
        if [ "${'$'}code" = 4 ]; then
            case "${'$'}reply" in *'Connection error: Connection failed'*|*'Connection error: Connection timed out'*) printf 'NO_INTERNET\n'; exit 0 ;; esac
        fi
        printf 'UNKNOWN\n'
    """.trimIndent(), 16384),
    BUNDLE_TRANSFER_FACTS("""
        [ "${'$'}(id -u)" = 0 ] && [ -d /tmp ] && [ ! -L /tmp ] || exit 1
        awk '${'$'}2=="/tmp"&&${'$'}3=="tmpfs" {found=1} END {exit !found}' /proc/mounts || exit 1
        cat /proc/sys/kernel/random/boot_id
        awk '/^MemAvailable:/ {print ${'$'}2}' /proc/meminfo
        df -Pk /tmp | awk 'NR==2 {print ${'$'}4}'
    """.trimIndent(), 256),
    BUNDLE_UPLOAD_CAPABILITY("""
        if [ -x /usr/libexec/sftp-server ] || [ -x /usr/lib/openssh/sftp-server ] || [ -x /usr/lib/sftp-server ]; then printf 'SFTP\n'; exit 0; fi
        for path in /usr/libexec/sftp-server /usr/lib/openssh/sftp-server /usr/lib/sftp-server; do
            if [ -e "${'$'}path" ] || [ -L "${'$'}path" ]; then printf 'UNKNOWN\n'; exit 0; fi
        done
        printf 'EXEC\n'
    """.trimIndent(), 256),
    BOARD("ubus call system board"),
    ARCHITECTURE("if [ -r /etc/apk/arch ]; then cat /etc/apk/arch; else opkg print-architecture; fi"),
    KERNEL_PACKAGE("apk info -v kernel"),
    FLASH("df -Pk /overlay"),
    MEMORY("cat /proc/meminfo"),
    CONFIGURATION("uci changes"),
    MOBILE_CERTIFICATE("cat /etc/foxhole/mobile.crt"),
    PACKAGE_MANIFEST("cat /usr/share/foxhole/package-manifest.json", 524288),
    RELEASE_BUNDLE_FACTS("""
        set -eu
        file_info() {
            ucode -e '
                import * as fs from "fs";
                let info = fs.lstat(ARGV[1]);
                if (!info) exit(1);
                switch (ARGV[0]) {
                    case "%u": print(info.uid); break;
                    case "%a": print(sprintf("%o", info.mode & 07777)); break;
                    case "%h": print(info.nlink); break;
                    case "%s": print(info.size); break;
                    default: exit(1);
                }
            ' -- "${'$'}1" "${'$'}2"
        }
        bundle=/mnt/foxhole-release
        manifest="${'$'}bundle/SHA256SUMS"
        [ -d "${'$'}bundle" ] && [ ! -L "${'$'}bundle" ] && [ -f "${'$'}manifest" ] && [ ! -L "${'$'}manifest" ] || exit 1
        [ "${'$'}(file_info %u "${'$'}bundle")" = 0 ] && [ "${'$'}(file_info %a "${'$'}bundle")" = 700 ] || exit 1
        [ "${'$'}(file_info %u "${'$'}manifest")" = 0 ] && [ "${'$'}(file_info %h "${'$'}manifest")" = 1 ] || exit 1
        case "${'$'}(file_info %a "${'$'}manifest")" in 400|600) ;; *) exit 1 ;; esac
        [ "${'$'}(file_info %s "${'$'}manifest")" -le 1048576 ]
        mount="${'$'}(awk '${'$'}2=="/mnt/foxhole-release" && ${'$'}4 ~ /(^|,)ro(,|${'$'})/ { print ${'$'}1 }' /proc/mounts)"
        case "${'$'}mount" in ''|*[!A-Za-z0-9_/:.-]*) exit 1 ;; esac
        boot="${'$'}(cat /proc/sys/kernel/random/boot_id)"
        case "${'$'}boot" in ''|*[!a-f0-9-]*) exit 1 ;; esac
        hash="${'$'}(sha256sum "${'$'}manifest" | cut -d ' ' -f1)"
        printf '{"schema":1,"bootId":"%s","mountDevice":"%s","manifestSha256":"%s","rootOwned":true,"readOnly":true}\n' "${'$'}boot" "${'$'}mount" "${'$'}hash"
    """.trimIndent()),
}

internal class RouterSshConnection(
    private val endpoint: InetSocketAddress,
    private val alias: String,
    expectedKey: ByteArray?,
    private val socketProvider: RouterSocketProvider,
) : RouterSshClient {
    private val repository = RouterHostKeyRepository(alias, expectedKey)
    private val trusted = expectedKey != null
    private val expectedIdentity = expectedKey?.copyOf()
    private val closed = AtomicBoolean(false)
    private val socket = AtomicReference<Socket?>()
    private val session = AtomicReference<Session?>()
    private val activeChannel = AtomicReference<ChannelExec?>()
    private val activeSftp = AtomicReference<ChannelSftp?>()

    init {
        require(!endpoint.isUnresolved && endpoint.port in 1..65535)
    }

    override fun connect(password: CharArray?) {
        try { connectInternal(password) }
        finally { password?.fill('\u0000') }
    }

    override fun connectForDiscovery() = connectInternal(null, timeoutMillis = 2000)

    override fun connectWithKey(privateKey: ByteArray, passphrase: CharArray) {
        try {
            require(privateKey.size in 1..65536 && passphrase.size <= 1024)
            connectInternal(null, privateKey, passphrase)
        } finally { privateKey.fill(0); passphrase.fill('\u0000') }
    }

    private fun connectInternal(password: CharArray?, privateKey: ByteArray? = null, passphrase: CharArray? = null,
        timeoutMillis: Int = 15000) {
        check(session.get() == null && !closed.get())
        require(trusted || password == null && privateKey == null)
        val client = JSch().apply { hostKeyRepository = repository }
        val connection = client.getSession("root", endpoint.address.hostAddress, endpoint.port)
        session.set(connection)
        connection.setHostKeyAlias(alias)
        connection.setConfig("StrictHostKeyChecking", "yes")
        connection.setConfig("PreferredAuthentications", when { privateKey != null -> "publickey"; trusted -> "password"; else -> "none" })
        connection.setConfig("MaxAuthTries", "1")
        connection.setServerAliveInterval(15000)
        connection.setServerAliveCountMax(3)
        connection.setConfig("compression.s2c", "none")
        connection.setConfig("compression.c2s", "none")
        connection.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                if (closed.get()) throw RouterSshFailure()
                val created = socketProvider.createSocket()
                socket.set(created)
                if (closed.get()) { created.close(); throw RouterSshFailure() }
                created.connect(endpoint, timeoutMillis)
                created.soTimeout = timeoutMillis
                return created
            }
            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })
        try {
            if (privateKey != null) {
                val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(requireNotNull(passphrase)))
                val bytes = ByteArray(encoded.remaining())
                encoded.get(bytes)
                try { client.addIdentity(alias, privateKey, null, bytes) }
                catch (_: com.jcraft.jsch.JSchException) { throw RouterSshAuthenticationFailed() }
                finally {
                    bytes.fill(0)
                    if (encoded.hasArray()) encoded.array().fill(0)
                }
            }
            if (password != null) {
                val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
                val bytes = ByteArray(encoded.remaining())
                encoded.get(bytes)
                try { connection.setPassword(bytes) }
                finally {
                    bytes.fill(0)
                    if (encoded.hasArray()) encoded.array().fill(0)
                }
            }
            connection.connect(timeoutMillis)
            if (closed.get()) throw RouterSshFailure()
        } catch (failure: Exception) {
            val candidate = repository.observedKey()
            close()
            if (!trusted && candidate != null) throw RouterSshTrustRequired(candidate)
            if (candidate != null && expectedIdentity != null && !java.security.MessageDigest.isEqual(candidate, expectedIdentity)) {
                throw RouterIdentityChanged()
            }
            if (failure is RouterSshAuthenticationFailed) throw failure
            if (failure is com.jcraft.jsch.JSchException && failure.message.orEmpty().startsWith("Auth fail")) {
                throw RouterSshAuthenticationFailed()
            }
            throw RouterSshFailure()
        } finally { client.removeAllIdentity() }
    }

    override fun read(command: RouterReadCommand): ByteArray {
        return execute(command.script, null, 30_000_000_000L, command.maximumOutputBytes)
    }

    override fun internetAccess(): RouterInternetAccess {
        require(trusted)
        val value = execute(RouterReadCommand.INTERNET_PROBE.script, null, 8_000_000_000L, 16384)
            .toString(Charsets.US_ASCII).trim()
        return RouterInternetAccess.entries.singleOrNull { it.name == value } ?: RouterInternetAccess.UNKNOWN
    }

    override fun stageInstallBundle(bundle: RouterDeviceInstallBundle, facts: RouterBundleTransferFacts, requiredRam: Long,
        onProgress: (RouterInstallProgress) -> Unit): RouterRemoteInstallBundle {
        require(trusted && requiredRam > 0)
        if (requiredRam > facts.availableRamBytes || requiredRam > facts.availableTmpBytes) throw RouterSetupFailure(RouterWizardError.STORAGE)
        bundle.plan.assets.forEach { verifyRouterBundleAsset(bundle.file(it), it) }
        val capability = read(RouterReadCommand.BUNDLE_UPLOAD_CAPABILITY).toString(Charsets.US_ASCII).trim()
        if (capability !in setOf("SFTP", "EXEC")) throw RouterSshFailure()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val path = "/tmp/foxhole-device-install-$nonce"
        val owner = routerBundleOwner(bundle, facts, nonce)
        val ownerHash = routerReleaseSha256(owner)
        var sftp: ChannelSftp? = null
        val timeout = java.util.Timer("router-bundle-timeout", true)
        timeout.schedule(object : java.util.TimerTask() { override fun run() { this@RouterSshConnection.close() } }, 600000L)
        var created = false
        val cleanup = routerBundleActionCommand(path, nonce, facts.bootId, ownerHash, "cleanup")
        try {
            if (capability == "SFTP") {
                val opened = requireNotNull(session.get()).openChannel("sftp") as ChannelSftp
                check(activeSftp.compareAndSet(null, opened))
                sftp = opened
                opened.connect(10000)
            }
            execute(routerBundleCreateCommand(path, nonce, facts.bootId, ownerHash, requiredRam), ByteArrayInputStream(owner), 15_000_000_000L, 1024)
            created = true
            var completed = 0L
            val deadline = System.nanoTime() + 600_000_000_000L
            for (asset in bundle.plan.assets) {
                val start = completed
                RouterBundleInputStream(FileInputStream(bundle.file(asset)), asset.bytes) { count ->
                    if (closed.get() || System.nanoTime() >= deadline) throw RouterSshFailure()
                    onProgress(RouterInstallProgress(RouterInstallProgressStage.UPLOAD, start + count, bundle.plan.bytes))
                }.use { input ->
                    val channel = sftp
                    if (channel == null) execute(routerBundleUploadCommand(path, asset), input,
                        (deadline - System.nanoTime()).coerceAtLeast(1), 1024)
                    else {
                        val info = channel.lstat("$path/${asset.name}")
                        require(info.isReg() && info.getUId() == 0 && (info.getPermissions() and 4095) == 384 && info.getSize() == 0L)
                        channel.put(input, "$path/${asset.name}", null as SftpProgressMonitor?, ChannelSftp.OVERWRITE)
                    }
                }
                completed = Math.addExact(completed, asset.bytes)
            }
            execute(routerBundleActionCommand(path, nonce, facts.bootId, ownerHash, "verify"), null, 120_000_000_000L, 1024)
            return RouterRemoteInstallBundle(path) { execute(cleanup, null, 30_000_000_000L, 1024) }
        } catch (failure: Exception) {
            if (created && !closed.get()) try { execute(cleanup, null, 30_000_000_000L, 1024) } catch (_: Exception) { }
            throw RouterSshFailure()
        } finally {
            timeout.cancel()
            owner.fill(0)
            sftp?.disconnect()
            if (sftp != null) activeSftp.compareAndSet(sftp, null)
        }
    }

    override fun readCompactBackupBytes(): ByteArray = execute(ROUTER_COMPACT_BACKUP_BYTES, null, 30_000_000_000L)

    override fun install(script: RouterVerifiedInstallScript, command: RouterInstallCommand) = install(script, command) {}

    override fun install(script: RouterVerifiedInstallScript, command: RouterInstallCommand, onProgress: (RouterInstallProgress) -> Unit) {
        val bytes = script.bytes()
        RouterInstallInputStream(ByteArrayInputStream(bytes), bytes.size.toLong(), onProgress).use { input ->
            execute(command.command, input, 600_000_000_000L)
        }
    }

    override fun bootstrapEnrollment(): ByteArray = execute("/usr/libexec/foxhole-bootstrap-enroll", null, 30_000_000_000L)

    private fun execute(command: String, input: InputStream?, timeoutNs: Long, maximumOutputBytes: Int = 262144): ByteArray {
        val connection = session.get()?.takeIf { it.isConnected && !closed.get() } ?: throw RouterSshFailure()
        val channel = connection.openChannel("exec") as ChannelExec
        val output = BoundedRouterOutput(maximumOutputBytes)
        val error = BoundedRouterOutput(16384)
        if (!activeChannel.compareAndSet(null, channel)) { channel.disconnect(); throw RouterSshFailure() }
        try {
            channel.setCommand(command)
            channel.setInputStream(input)
            channel.setOutputStream(output, true)
            channel.setErrStream(error, true)
            channel.connect(10000)
            val deadline = System.nanoTime() + timeoutNs
            while (!channel.isClosed) {
                if (closed.get() || output.overflowed || error.overflowed || System.nanoTime() >= deadline) throw RouterSshFailure()
                Thread.sleep(20)
            }
            if (channel.exitStatus != 0 || output.overflowed || error.overflowed) throw RouterSshFailure()
            return output.bytes()
        } catch (_: Exception) {
            close()
            throw RouterSshFailure()
        } finally {
            channel.disconnect()
            activeChannel.compareAndSet(channel, null)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            activeChannel.getAndSet(null)?.disconnect()
            activeSftp.getAndSet(null)?.disconnect()
            try { socket.getAndSet(null)?.close() } catch (_: IOException) {}
            session.getAndSet(null)?.disconnect()
        }
    }
}

internal class BoundedRouterOutput(private val limit: Int) : OutputStream() {
    private val contents = ByteArrayOutputStream()
    @Volatile var overflowed: Boolean = false
        private set
    @Synchronized override fun write(value: Int) {
        if (contents.size() >= limit) { overflowed = true; throw IOException("router_output_limit") }
        contents.write(value)
    }
    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length < 0 || length > limit - contents.size()) { overflowed = true; throw IOException("router_output_limit") }
        contents.write(bytes, offset, length)
    }
    @Synchronized fun bytes(): ByteArray = contents.toByteArray()
}

// Fresh compact installs cannot replace an existing FoxHole configuration. No file names leave SSH.
private val ROUTER_COMPACT_BACKUP_BYTES = """
    set -eu
    [ ! -e /etc/foxhole ] && [ ! -L /etc/foxhole ] || exit 1
    paths="${'$'}(sysupgrade -l 2>/dev/null)" || exit 1
    [ "${'$'}{#paths}" -le 131072 ] || exit 1
    total=0
    count=0
    while IFS= read -r path; do
        [ -n "${'$'}path" ] || continue
        count=${'$'}((count + 1))
        [ "${'$'}count" -le 2048 ] || exit 1
        [ -f "${'$'}path" ] && [ -r "${'$'}path" ] || exit 1
        bytes="${'$'}(wc -c < "${'$'}path")" || exit 1
        case "${'$'}bytes" in ''|*[!0-9[:space:]]*) exit 1 ;; esac
        bytes=${'$'}((bytes + 0))
        [ "${'$'}bytes" -ge 0 ] && [ "${'$'}bytes" -le 536870912 ] || exit 1
        total=${'$'}((total + bytes + 2048))
        [ "${'$'}total" -le 536870912 ] || exit 1
    done <<EOF
    ${'$'}paths
    EOF
    printf '%s\n' "${'$'}total"
""".trimIndent()
