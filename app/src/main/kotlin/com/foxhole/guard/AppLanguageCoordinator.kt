package com.foxhole.guard

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PendingAppLanguage<L : Any>(val prior: L, val target: L, val resumePrior: L? = null)
internal data class AppLanguageJournal<L : Any>(val completed: L? = null, val pending: PendingAppLanguage<L>? = null)

internal interface AppLanguagePort<L : Any> {
    /** Null means that the encrypted state is not currently known durable. */
    suspend fun readStored(): L?
    suspend fun readPlatform(): L?
    suspend fun readJournal(): AppLanguageJournal<L>
    suspend fun savePending(pending: PendingAppLanguage<L>)
    suspend fun writeStored(expected: L, desired: L): Boolean
    suspend fun applyPlatform(desired: L)
    suspend fun complete(desired: L, pending: PendingAppLanguage<L>?): Boolean
    suspend fun clearPending(pending: PendingAppLanguage<L>): Boolean
}

/** Caller owns lifetime and serializes this coordinator with its other locale operations. */
internal class AppLanguageCoordinator<L : Any>(
    private val port: AppLanguagePort<L>,
    private val system: L,
    private val platformAuthoritative: Boolean,
) {
    suspend fun select(desired: L): Boolean {
        currentCoroutineContext().ensureActive()
        val prior = port.readStored() ?: return false
        val existing = port.readJournal().pending
        val priorIntent = prior.takeIf { existing != null && (prior == existing.target || prior == existing.resumePrior) }
        if (existing != null && priorIntent == null && !port.clearPending(existing)) return false
        val pending = PendingAppLanguage(prior, desired, priorIntent)
        return operation(desired, pending) {
            port.savePending(pending)
            currentCoroutineContext().ensureActive()
            if (prior != desired && !port.writeStored(prior, desired)) return@operation false
            currentCoroutineContext().ensureActive()
            applyIfNeeded(desired)
            currentCoroutineContext().ensureActive()
            checkpointIfAgreed(desired, pending)
        }
    }

    suspend fun synchronize(): Boolean {
        currentCoroutineContext().ensureActive()
        var stored = port.readStored() ?: return false
        var journal = port.readJournal()
        val pending = journal.pending
        if (pending != null) {
            if (stored == pending.target) {
                return operation(pending.target, pending) {
                    applyIfNeeded(pending.target)
                    currentCoroutineContext().ensureActive()
                    checkpointIfAgreed(pending.target, pending)
                }
            }
            if (stored == pending.resumePrior) {
                val restored = PendingAppLanguage(stored, stored)
                port.savePending(restored)
                return operation(stored, restored) {
                    applyIfNeeded(stored)
                    currentCoroutineContext().ensureActive()
                    checkpointIfAgreed(stored, restored)
                }
            }
            if (!port.clearPending(pending)) return false
            // Do not overwrite a newer unrelated durable choice while discarding stale intent.
            if (stored != pending.prior) return false
            stored = port.readStored() ?: return false
            journal = port.readJournal()
        }
        val platform = port.readPlatform()
        val decision = if (!platformAuthoritative) {
            AppLanguageDecision(stored, true)
        } else {
            // Only the journal identifies an app intent; a partial OS mirror is not one.
            decideAppLanguage(stored, platform, if (journal.completed == null) null else stored, system)
                ?: return false
        }
        return operation(decision.language, null) {
            if (decision.language != stored && !port.writeStored(stored, decision.language)) return@operation false
            currentCoroutineContext().ensureActive()
            if (decision.applyStored) applyIfNeeded(decision.language)
            currentCoroutineContext().ensureActive()
            checkpointIfAgreed(decision.language, null)
        }
    }

    private suspend fun applyIfNeeded(desired: L) {
        currentCoroutineContext().ensureActive()
        if (port.readPlatform() != desired) port.applyPlatform(desired)
    }

    private suspend fun checkpointIfAgreed(desired: L, pending: PendingAppLanguage<L>?): Boolean {
        if (port.readStored() != desired || port.readPlatform() != desired) return false
        return port.complete(desired, pending)
    }

    private suspend fun settle(desired: L, pending: PendingAppLanguage<L>?) {
        if (pending == null) {
            checkpointIfAgreed(desired, null)
            return
        }
        if (port.readJournal().pending != pending) return
        val actual = port.readStored() ?: return
        if (actual != pending.target) {
            if (actual == pending.resumePrior) port.savePending(PendingAppLanguage(actual, actual))
            else port.clearPending(pending)
            return
        }
        checkpointIfAgreed(pending.target, pending)
    }

    private suspend fun operation(
        desired: L,
        pending: PendingAppLanguage<L>?,
        block: suspend () -> Boolean,
    ): Boolean {
        var primary: Throwable? = null
        try {
            return block()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            // Cleanup never persists a language or applies an OS locale.
            withContext(NonCancellable) {
                try {
                    settle(desired, pending)
                } catch (cleanup: Throwable) {
                    val original = primary
                    if (original != null) original.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }
}
