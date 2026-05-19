package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryActiveProfileTest {
    @Test
    fun `missing active profile id does not clear current active profile`() =
        runBlocking {
            val dao = FakeProfileDao(profiles = mutableMapOf(1L to profile(id = 1L, isActive = true)))

            try {
                dao.setActiveProfileIfPresent(404L)
            } catch (_: IllegalStateException) {
                // expected
            }

            assertFalse(dao.clearActiveCalled)
            assertEquals(1L, dao.getActiveProfile()?.id)
        }

    @Test
    fun `existing active profile id clears and marks requested profile active`() =
        runBlocking {
            val profiles =
                mutableMapOf(
                    1L to profile(id = 1L, isActive = true),
                    2L to profile(id = 2L, isActive = false),
                )
            val dao =
                FakeProfileDao(
                    profiles = profiles,
                )

            dao.setActiveProfileIfPresent(2L)

            assertTrue(dao.clearActiveCalled)
            assertEquals(2L, dao.getActiveProfile()?.id)
        }

    private fun profile(
        id: Long,
        isActive: Boolean,
    ): ProfileEntity =
        ProfileEntity(
            id = id,
            name = "Profile $id",
            sourceType = ProfileSourceType.RAW_SINGBOX_JSON.name,
            secretRef = "secret-$id",
            protocolHint = ProtocolHint.VLESS.name,
            lastUpdatedAt = null,
            lastEtag = null,
            isActive = isActive,
        )

    private class FakeProfileDao(
        private val profiles: MutableMap<Long, ProfileEntity>,
    ) : ProfileDao {
        var clearActiveCalled = false
            private set

        override fun observeProfiles(): Flow<List<ProfileEntity>> = emptyFlow()

        override fun observeActiveProfile(): Flow<ProfileEntity?> = emptyFlow()

        override suspend fun getById(id: Long): ProfileEntity? = profiles[id]

        override suspend fun getAllProfiles(): List<ProfileEntity> = profiles.values.sortedBy(ProfileEntity::id)

        override suspend fun getActiveProfile(): ProfileEntity? = profiles.values.firstOrNull(ProfileEntity::isActive)

        override suspend fun insert(entity: ProfileEntity): Long = error("unused")

        override suspend fun updateMetadata(
            id: Long,
            name: String,
            protocolHint: String,
            lastUpdatedAt: Long?,
            lastEtag: String?,
        ) {
            error("unused")
        }

        override suspend fun updateMetadataAndSecretRef(
            id: Long,
            name: String,
            secretRef: String,
            protocolHint: String,
            lastUpdatedAt: Long?,
            lastEtag: String?,
        ) {
            error("unused")
        }

        override suspend fun updateProtocolHint(
            id: Long,
            protocolHint: String,
        ) {
            error("unused")
        }

        override suspend fun clearActive() {
            clearActiveCalled = true
            profiles.replaceAll { _, profile -> profile.copy(isActive = false) }
        }

        override suspend fun setActive(id: Long): Int {
            val profile = profiles[id] ?: return 0
            profiles[id] = profile.copy(isActive = true)
            return 1
        }

        override suspend fun delete(id: Long) {
            error("unused")
        }

        override suspend fun count(): Int = profiles.size

        override suspend fun getMostRecentProfileId(): Long? = profiles.keys.maxOrNull()
    }
}
