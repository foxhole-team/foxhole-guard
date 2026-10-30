package com.foxhole.guard.core.security

import com.foxhole.guard.guardian.GuardEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun FoxholeSecurityComponents.captureJournalWriteEpoch(): Long = guardJournal.captureWriteEpoch()

internal fun FoxholeSecurityComponents.journalEventAtEpoch(event: GuardEvent, epoch: Long): Boolean =
    guardJournal.appendAtEpoch(event, epoch)

internal suspend fun FoxholeSecurityComponents.prepareFactoryResetJournal() = withContext(Dispatchers.IO) {
    guardSentinel.clearHosts()
    guardJournal.deleteAll { guardSentinel.invalidateQueueEpoch() }
    guardSentinel.settleQueueEpochInvalidation()
}
