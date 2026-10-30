package com.foxhole.guard.router

import com.jcraft.jsch.ChannelExec
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
    fun read(command: RouterReadCommand): ByteArray
    fun readCompactBackupBytes(): ByteArray = throw RouterInspectionFailure()
    fun install(script: RouterVerifiedInstallScript, command: RouterInstallCommand)
    fun install(script: RouterVerifiedInstallScript, command: RouterInstallCommand, onProgress: (RouterInstallProgress) -> Unit) {
        onProgress(RouterInstallProgress(RouterInstallProgressStage.INSTALL))
        install(script, command)
    }
    fun bootstrapEnrollment(): ByteArray
}

internal enum class RouterReadCommand(val script: String, val maximumOutputBytes: Int = 262144) {
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

    init {
        require(!endpoint.isUnresolved && endpoint.port in 1..65535)
    }

    override fun connect(password: CharArray?) {
        try { connectInternal(password) }
        finally { password?.fill('\u0000') }
    }

    private fun connectInternal(password: CharArray?) {
        check(session.get() == null && !closed.get())
        require(trusted || password == null)
        val client = JSch().apply { hostKeyRepository = repository }
        val connection = client.getSession("root", endpoint.address.hostAddress, endpoint.port)
        session.set(connection)
        connection.setHostKeyAlias(alias)
        connection.setConfig("StrictHostKeyChecking", "yes")
        connection.setConfig("PreferredAuthentications", if (trusted) "password" else "none")
        connection.setConfig("MaxAuthTries", "1")
        connection.setConfig("compression.s2c", "none")
        connection.setConfig("compression.c2s", "none")
        connection.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                if (closed.get()) throw RouterSshFailure()
                val created = socketProvider.createSocket()
                socket.set(created)
                if (closed.get()) { created.close(); throw RouterSshFailure() }
                created.connect(endpoint, 15000)
                created.soTimeout = 15000
                return created
            }
            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })
        try {
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
            connection.connect(15000)
            if (closed.get()) throw RouterSshFailure()
        } catch (failure: Exception) {
            val candidate = repository.observedKey()
            close()
            if (!trusted && candidate != null) throw RouterSshTrustRequired(candidate)
            if (candidate != null && expectedIdentity != null && !java.security.MessageDigest.isEqual(candidate, expectedIdentity)) {
                throw RouterIdentityChanged()
            }
            if (failure is com.jcraft.jsch.JSchException && failure.message.orEmpty().startsWith("Auth fail")) {
                throw RouterSshAuthenticationFailed()
            }
            throw RouterSshFailure()
        }
    }

    override fun read(command: RouterReadCommand): ByteArray {
        return execute(command.script, null, 30_000_000_000L, command.maximumOutputBytes)
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
