package com.foxhole.guard.core.data

import androidx.room.withTransaction
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.SettingsRestoreSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ProfileMutationCoordinator {
    internal val mutex = Mutex()
    internal val secretMutex = Mutex()
    private val retryLock = Any()
    private val retryEffects = linkedMapOf<String, ProfileCommittedEffect>()

    suspend fun <T> write(onEffectFailure: (Throwable) -> Unit, block: suspend (ProfileMutationLease) -> T): T {
        val lease = ProfileMutationLease(this, onEffectFailure)
        val outcome = mutex.withLock(lease) {
            lease.open()
            try {
                Result.success(block(lease))
            } catch (error: Throwable) {
                Result.failure(error)
            } finally {
                lease.close()
            }
        }
        var primary = outcome.exceptionOrNull()
        try {
            withContext(NonCancellable) {
                primary = settleEffects(lease.committedEffects(), primary)
            }
        } catch (handoffFailure: Throwable) {
            if (primary == null) {
                primary = handoffFailure
            } else if (primary !== handoffFailure) primary!!.addSuppressed(handoffFailure)
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            if (primary == null) {
                primary = cancelled
            } else if (primary !== cancelled) primary!!.addSuppressed(cancelled)
        }
        primary?.let { throw it }
        return outcome.getOrThrow()
    }

    suspend fun <T> readOwned(block: suspend (ProfileMutationLease) -> T): T {
        val lease = ProfileMutationLease(this) { error -> throw error }
        return mutex.withLock(lease) {
            lease.open()
            try { block(lease) } finally { lease.close() }
        }
    }

    suspend fun <T> readCommitted(block: suspend () -> T): T = mutex.withLock { block() }

    private fun settleEffects(current: List<ProfileCommittedEffect>, operationFailure: Throwable?): Throwable? {
        val effects = synchronized(retryLock) {
            val pending = retryEffects.values.toMutableList()
            retryEffects.clear()
            current.forEach { effect ->
                effect.retryKey?.let { key -> pending.removeAll { it.retryKey == key } }
                pending += effect
            }
            pending
        }
        var primary = operationFailure
        effects.forEach { effect -> primary = settleEffect(effect, primary) }
        return primary
    }

    private fun settleEffect(effect: ProfileCommittedEffect, operationFailure: Throwable?): Throwable? {
        val error = try {
            effect.run()
            null
        } catch (failure: Throwable) { failure } ?: return operationFailure
        var primary = operationFailure ?: error.takeIf(Throwable::requiresPropagation)
        val secondaryFailures = listOfNotNull(reportEffectFailure(effect, error), retainRetry(effect))
        secondaryFailures.forEach { secondary ->
            if (primary == null && secondary.requiresPropagation()) {
                primary = secondary
            } else if (secondary !== primary && secondary !== error) error.addSuppressed(secondary)
        }
        if (primary !== error) primary?.addSuppressed(error)
        return primary
    }

    private fun reportEffectFailure(effect: ProfileCommittedEffect, error: Throwable): Throwable? =
        try {
            effect.reportFailure(error)
            null
        } catch (reportingFailure: Throwable) {
            reportingFailure
        }

    private fun retainRetry(effect: ProfileCommittedEffect): Throwable? {
        val key = effect.retryKey ?: return null
        val retained = synchronized(retryLock) {
            if (key in retryEffects || retryEffects.size < MAX_PENDING_EFFECTS) {
                retryEffects[key] = effect
                true
            } else {
                false
            }
        }
        return if (retained) {
            null
        } else {
            reportEffectFailure(
                effect,
                IllegalStateException("post-commit effect retry capacity exceeded"),
            )
        }
    }

    private companion object {
        const val MAX_PENDING_EFFECTS = 8
    }
}

internal data class ProfileCommittedEffect(
    val retryKey: String?,
    val reportFailure: (Throwable) -> Unit,
    val run: () -> Unit,
)

internal class ProfileMutationLease internal constructor(
    private val coordinator: ProfileMutationCoordinator,
    private val onEffectFailure: (Throwable) -> Unit,
) {
    @Volatile
    private var active = false
    private var committed = false
    private val effects = mutableListOf<ProfileCommittedEffect>()
    private val touchedSecretRefs = mutableSetOf<String>()
    private var restoreTransaction: ProfileDatabase? = null
    private var secretUndo: ProfileSecretUndo? = null
    internal var restoreSettings: SettingsRestoreSession? = null
        private set

    internal fun open() {
        check(coordinator.mutex.holdsLock(this)) { "profile mutation owner is not locked" }
        active = true
    }

    internal fun close() {
        active = false
        restoreSettings = null
        restoreTransaction = null
        secretUndo = null
    }

    fun requireOwner(expected: ProfileMutationCoordinator) {
        check(active && coordinator === expected && coordinator.mutex.holdsLock(this)) {
            "profile mutation owner is no longer current"
        }
    }

    suspend fun <T> secrets(mutex: Mutex, block: suspend () -> T): T {
        requireOwner(coordinator)
        return if (mutex.holdsLock(this)) block() else mutex.withLock(this) { block() }
    }

    fun bindSettings(session: SettingsRestoreSession) {
        requireOwner(coordinator)
        check(restoreSettings == null) { "restore settings are already bound" }
        restoreSettings = session
    }

    suspend fun <T> transaction(database: ProfileDatabase, block: suspend () -> T): T {
        requireOwner(coordinator)
        return if (restoreTransaction === database) block() else database.withTransaction { block() }
    }

    suspend fun <T> restoreTransaction(database: ProfileDatabase, block: suspend () -> T): T {
        requireOwner(coordinator)
        check(restoreTransaction == null) { "restore transaction is already bound" }
        return database.withTransaction {
            restoreTransaction = database
            try { block() } finally { restoreTransaction = null }
        }
    }

    fun bindUndo(undo: ProfileSecretUndo) {
        requireOwner(coordinator)
        check(secretUndo == null) { "profile undo is already bound" }
        secretUndo = undo
    }

    fun unbindUndo(undo: ProfileSecretUndo) {
        requireOwner(coordinator)
        check(secretUndo === undo) { "profile undo owner changed" }
        secretUndo = null
    }

    fun touchedSecrets(): Set<String> {
        requireOwner(coordinator)
        return touchedSecretRefs.toSet()
    }

    fun recordingSecrets(store: ProfileSecretStore): ProfileSecretStore = object : ProfileSecretStore {
        override suspend fun read(secretRef: String): StoredProfileSecret? {
            requireOwner(coordinator)
            return store.read(secretRef)
        }
        override suspend fun write(secretRef: String, value: StoredProfileSecret) {
            requireOwner(coordinator)
            secretUndo?.capture(secretRef)
            touchedSecretRefs += secretRef
            store.write(secretRef, value)
        }
        override suspend fun delete(secretRef: String): Boolean {
            requireOwner(coordinator)
            secretUndo?.capture(secretRef)
            touchedSecretRefs += secretRef
            return store.delete(secretRef)
        }
    }

    fun markLocalCommit() {
        requireOwner(coordinator)
        if (restoreSettings == null) committed = true
    }

    fun markRestoreCommitted() {
        requireOwner(coordinator)
        check(restoreSettings != null) { "restore settings are not bound" }
        committed = true
    }

    fun markRestoreRolledBack() {
        requireOwner(coordinator)
        committed = false
        effects.clear()
    }

    suspend fun currentSettings(repository: SettingsRepository): Settings {
        requireOwner(coordinator)
        return restoreSettings?.current() ?: repository.current()
    }

    suspend fun updateSettings(repository: SettingsRepository, transform: (Settings) -> Settings) {
        requireOwner(coordinator)
        val session = restoreSettings
        if (session != null) session.update(transform) else repository.update(transform)
    }

    fun afterCommit(retryKey: String? = null, effect: () -> Unit) {
        requireOwner(coordinator)
        effects += ProfileCommittedEffect(retryKey, onEffectFailure, effect)
    }

    fun effectCheckpoint(): Int {
        requireOwner(coordinator)
        return effects.size
    }

    fun rollbackEffectsTo(index: Int) {
        requireOwner(coordinator)
        check(index in 0..effects.size)
        while (effects.size > index) effects.removeAt(effects.lastIndex)
    }

    internal fun committedEffects(): List<ProfileCommittedEffect> {
        check(!active && !coordinator.mutex.holdsLock(this)) { "profile effects must run outside the mutation lock" }
        return effects.toList().takeIf { committed }.orEmpty().also { effects.clear() }
    }
}

internal class ProfileSecretUndo(private val store: ProfileSecretStore) {
    private val before = linkedMapOf<String, StoredProfileSecret?>()

    suspend fun capture(ref: String) {
        if (ref !in before) before[ref] = store.read(ref)
    }

    suspend fun restore() {
        before.forEach { (ref, secret) ->
            if (secret == null) store.delete(ref) else store.write(ref, secret)
        }
    }
}

private fun Throwable.requiresPropagation(): Boolean = this is CancellationException || this is Error
