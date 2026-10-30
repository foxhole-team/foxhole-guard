package com.foxhole.guard.router

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest

internal class RouterHostKeyRepository(private val alias: String, expected: ByteArray?) : HostKeyRepository {
    private val expected = expected?.copyOf()
    private var observed: ByteArray? = null
    fun observedKey(): ByteArray? = observed?.copyOf()
    init {
        require(alias.isNotBlank() && alias.length <= 128)
        require(expected == null || expected.size in 1..16384)
    }
    override fun check(host: String, key: ByteArray): Int {
        if (host != alias || key.isEmpty() || key.size > 16384) return HostKeyRepository.CHANGED
        observed = key.copyOf()
        val trusted = expected ?: return HostKeyRepository.NOT_INCLUDED
        return if (MessageDigest.isEqual(trusted, key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }
    override fun add(hostkey: HostKey, ui: UserInfo?) { throw UnsupportedOperationException("router identity is immutable") }
    override fun remove(host: String?, type: String?) { throw UnsupportedOperationException("router identity is immutable") }
    override fun remove(host: String?, type: String?, key: ByteArray?) { throw UnsupportedOperationException("router identity is immutable") }
    override fun getKnownHostsRepositoryID(): String = "paired-router"
    override fun getHostKey(): Array<HostKey> = expected?.let { arrayOf(HostKey(alias, it)) } ?: emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        getHostKey().filter { (host == null || it.host == host) && (type == null || it.type == type) }.toTypedArray()
}
