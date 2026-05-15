package com.foxhole.beta.core.statistics

import com.foxhole.beta.core.data.AnomalyDao
import com.foxhole.beta.core.data.RuntimeTimelineEventEntity
import kotlinx.coroutines.flow.Flow

data class RuntimeTimelineEvent(
    val id: Long = 0,
    val timestampMs: Long,
    val generationId: Long,
    val sessionId: String?,
    val owner: String,
    val mode: String,
    val stage: String,
    val status: String,
    val durationMs: Long?,
    val profileId: Long?,
    val protocol: String?,
    val details: Map<String, String> = emptyMap(),
)

class RuntimeTimelineRepository(
    private val dao: AnomalyDao,
) {
    fun observeEvents(
        startMs: Long,
        endMs: Long,
    ): Flow<List<RuntimeTimelineEventEntity>> = dao.observeRuntimeTimelineEvents(startMs, endMs)

    suspend fun record(event: RuntimeTimelineEvent): Long =
        dao.insertRuntimeTimelineEvent(
            RuntimeTimelineEventEntity(
                id = event.id,
                timestampMs = event.timestampMs,
                generationId = event.generationId,
                sessionId = event.sessionId,
                owner = event.owner,
                mode = event.mode,
                stage = event.stage,
                status = event.status,
                durationMs = event.durationMs,
                profileId = event.profileId,
                protocol = event.protocol,
                details = event.details,
            ),
        )
}
