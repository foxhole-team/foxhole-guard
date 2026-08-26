package com.foxhole.guard.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class PublicDnsIdentityResolverTest {
    @Test
    fun `akahelp txt answer yields recursive resolver address`() {
        val response = dnsTxtResponse(
            listOf("ecs", "198.51.100.0/24/0"),
            listOf("ns", "8.8.8.8"),
            listOf("ip", "198.51.100.42"),
        )

        assertEquals("8.8.8.8", parseResolverIpFromDnsTxtResponse(response))
    }

    @Test
    fun `parser skips private and ipv6 resolvers in favour of public ipv4`() {
        val response = dnsTxtResponse(
            listOf("ns", "192.168.1.1"),
            listOf("ns", "2606:4700:4700::1111"),
            listOf("ns", "1.1.1.1"),
        )

        assertEquals("1.1.1.1", parseResolverIpFromDnsTxtResponse(response))
    }

    @Test
    fun `parser rejects resolver response without public ipv4`() {
        val response = dnsTxtResponse(
            listOf("ns", "2606:4700:4700::1111"),
            listOf("ns", "10.0.0.1"),
        )

        assertNull(parseResolverIpFromDnsTxtResponse(response))
    }

    @Test
    fun `malformed or non resolver responses are rejected`() {
        assertNull(parseResolverIpFromDnsTxtResponse(byteArrayOf(0x00, 0x01)))
        assertNull(parseResolverIpFromDnsTxtResponse(dnsTxtResponse(listOf("ip", "8.8.4.4"))))
    }
}

private fun dnsTxtResponse(vararg answers: List<String>): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { dns ->
        dns.writeShort(0x1234)
        dns.writeShort(0x8180)
        dns.writeShort(1)
        dns.writeShort(answers.size)
        dns.writeShort(0)
        dns.writeShort(0)
        writeDnsName(dns, "whoami.ds.akahelp.net")
        dns.writeShort(16)
        dns.writeShort(1)
        answers.forEach { segments ->
            dns.writeShort(0xc00c)
            dns.writeShort(16)
            dns.writeShort(1)
            dns.writeInt(0)
            val encoded = segments.map { it.toByteArray(Charsets.US_ASCII) }
            dns.writeShort(encoded.sumOf { it.size + 1 })
            encoded.forEach { segment ->
                dns.writeByte(segment.size)
                dns.write(segment)
            }
        }
    }
    return output.toByteArray()
}

private fun writeDnsName(output: DataOutputStream, name: String) {
    name.split('.').forEach { label ->
        val encoded = label.toByteArray(Charsets.US_ASCII)
        output.writeByte(encoded.size)
        output.write(encoded)
    }
    output.writeByte(0)
}
