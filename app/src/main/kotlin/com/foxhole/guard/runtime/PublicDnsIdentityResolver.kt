package com.foxhole.guard.runtime

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PublicDnsIdentityResult(
    val serverAddress: String,
    val countryCode: String?,
)

internal class PublicDnsIdentityResolver(
    private val networkProvider: () -> Network?,
    private val countryCodeForIpAddress: (String) -> String?,
) {
    suspend fun resolve(): PublicDnsIdentityResult =
        withContext(Dispatchers.IO) {
            withTimeout(RESOLVER_LOOKUP_TIMEOUT_MS) {
                val network = networkProvider()
                val serverAddress = resolveServerAddress(network)
                PublicDnsIdentityResult(
                    serverAddress = serverAddress,
                    countryCode = countryCodeForIpAddress(serverAddress),
                )
            }
        }

    private suspend fun resolveServerAddress(network: Network?): String {
        val txtAddress =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                withTimeoutOrNull(TXT_LOOKUP_TIMEOUT_MS) {
                    runCatching { queryAkahelpTxt(network) }.getOrNull()
                }
            } else {
                null
            }
        return txtAddress ?: queryLegacyARecord(network)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun queryAkahelpTxt(network: Network?): String =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            DnsResolver.getInstance().rawQuery(
                network,
                AKAHELP_RESOLVER_HOST,
                DnsResolver.CLASS_IN,
                DNS_TYPE_TXT,
                DnsResolver.FLAG_NO_CACHE_LOOKUP or DnsResolver.FLAG_NO_CACHE_STORE,
                DIRECT_EXECUTOR,
                cancellationSignal,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (!continuation.isActive) return
                        if (rcode != DNS_RCODE_SUCCESS) {
                            continuation.resumeWithException(IOException("DNS response code $rcode"))
                            return
                        }
                        val address = parseResolverIpFromDnsTxtResponse(answer)
                        if (address == null) {
                            continuation.resumeWithException(IOException("resolver address missing from DNS response"))
                        } else {
                            continuation.resume(address)
                        }
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                },
            )
        }

    private suspend fun queryLegacyARecord(network: Network?): String =
        runInterruptible(Dispatchers.IO) {
            val answers =
                network?.getAllByName(LEGACY_RESOLVER_HOST)
                    ?: InetAddress.getAllByName(LEGACY_RESOLVER_HOST)
            answers.firstNotNullOfOrNull { address -> publicIpv4AddressOrNull(address.hostAddress) }
                ?: error("public resolver address unavailable")
        }
}

@Suppress("CyclomaticComplexMethod", "ReturnCount")
internal fun parseResolverIpFromDnsTxtResponse(message: ByteArray): String? {
    if (message.size < DNS_HEADER_SIZE) return null
    val questionCount = message.readUnsignedShort(DNS_QUESTION_COUNT_OFFSET) ?: return null
    val answerCount = message.readUnsignedShort(DNS_ANSWER_COUNT_OFFSET) ?: return null
    var offset = DNS_HEADER_SIZE
    repeat(questionCount) {
        offset = message.skipDnsName(offset) ?: return null
        if (offset + DNS_QUESTION_SUFFIX_SIZE > message.size) return null
        offset += DNS_QUESTION_SUFFIX_SIZE
    }
    repeat(answerCount) {
        offset = message.skipDnsName(offset) ?: return null
        if (offset + DNS_RESOURCE_HEADER_SIZE > message.size) return null
        val type = message.readUnsignedShort(offset) ?: return null
        val recordClass = message.readUnsignedShort(offset + 2) ?: return null
        val dataLength = message.readUnsignedShort(offset + 8) ?: return null
        val dataStart = offset + DNS_RESOURCE_HEADER_SIZE
        val dataEnd = dataStart + dataLength
        if (dataEnd > message.size) return null
        if (type == DNS_TYPE_TXT && recordClass == DNS_CLASS_IN) {
            val segments = message.readTxtSegments(dataStart, dataEnd) ?: return null
            val address = segments.resolverAddressOrNull()
            if (address != null) return address
        }
        offset = dataEnd
    }
    return null
}

private fun ByteArray.readTxtSegments(start: Int, end: Int): List<String>? {
    val values = mutableListOf<String>()
    var offset = start
    while (offset < end) {
        val size = this[offset].toInt() and 0xff
        offset += 1
        if (offset + size > end) return null
        values += copyOfRange(offset, offset + size).toString(Charsets.US_ASCII)
        offset += size
    }
    return values
}

private fun List<String>.resolverAddressOrNull(): String? {
    for (index in 0 until lastIndex) {
        if (this[index].equals("ns", ignoreCase = true)) {
            publicIpv4AddressOrNull(this[index + 1])?.let { return it }
        }
    }
    val joined = joinToString(" ").trim()
    val candidate = RESOLVER_TXT_PATTERN.find(joined)?.groupValues?.getOrNull(1)
    return candidate?.let(::publicIpv4AddressOrNull)
}

private fun ByteArray.readUnsignedShort(offset: Int): Int? {
    if (offset < 0 || offset + 2 > size) return null
    return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
}

private fun ByteArray.skipDnsName(start: Int): Int? {
    var offset = start
    var labels = 0
    while (offset < size && labels <= DNS_MAX_LABELS) {
        val length = this[offset].toInt() and 0xff
        when {
            length == 0 -> return offset + 1
            length and DNS_POINTER_MASK == DNS_POINTER_MASK ->
                return (offset + DNS_POINTER_SIZE).takeIf { it <= size }
            length > DNS_MAX_LABEL_LENGTH || offset + 1 + length > size -> return null
            else -> {
                offset += 1 + length
                labels += 1
            }
        }
    }
    return null
}

@Suppress("ComplexCondition")
private fun publicIpv4AddressOrNull(candidate: String?): String? {
    val normalized = candidate?.trim()?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%')
        ?.takeIf { value -> value.isNotEmpty() && value.all { it.isDigit() || it in ".:abcdefABCDEF" } }
        ?: return null
    val address = runCatching { InetAddress.getByName(normalized) }.getOrNull() as? Inet4Address ?: return null
    if (
        address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) {
        return null
    }
    return address.hostAddress?.substringBefore('%')
}

private val DIRECT_EXECUTOR = Executor(Runnable::run)
private val RESOLVER_TXT_PATTERN = Regex("(?:^|\\s)ns\\s+([0-9a-fA-F:.]+)(?:$|\\s)")

private const val AKAHELP_RESOLVER_HOST = "whoami.ds.akahelp.net"
private const val LEGACY_RESOLVER_HOST = "whoami.akamai.net"
private const val DNS_TYPE_TXT = 16
private const val DNS_CLASS_IN = 1
private const val DNS_RCODE_SUCCESS = 0
private const val DNS_HEADER_SIZE = 12
private const val DNS_QUESTION_COUNT_OFFSET = 4
private const val DNS_ANSWER_COUNT_OFFSET = 6
private const val DNS_QUESTION_SUFFIX_SIZE = 4
private const val DNS_RESOURCE_HEADER_SIZE = 10
private const val DNS_POINTER_MASK = 0xc0
private const val DNS_POINTER_SIZE = 2
private const val DNS_MAX_LABEL_LENGTH = 63
private const val DNS_MAX_LABELS = 127
private const val TXT_LOOKUP_TIMEOUT_MS = 4_000L
private const val RESOLVER_LOOKUP_TIMEOUT_MS = 8_000L
