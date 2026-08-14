package com.foxhole.guard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetActivationPolicyTest {
    @Test
    fun `Tor defaults to Tor Project and only FoxHole DB enters the download gate`() {
        val initial = DatasetActivationState(DatasetActivationFeature.TOR_BRIDGES)

        assertEquals(DatasetActivationSource.TOR_PROJECT, initial.source)
        assertEquals(
            DatasetActivationStep.VERIFIED,
            initial.reduce(DatasetActivationEvent.USE).step,
        )
        assertEquals(
            DatasetActivationStep.DATASET_REQUIRED,
            initial
                .selectSource(DatasetActivationSource.FOXHOLE_DB)
                .reduce(DatasetActivationEvent.USE)
                .step,
        )
    }

    @Test
    fun `Sentinel exposes only FoxHole DB and cannot skip verification`() {
        val initial = DatasetActivationState(DatasetActivationFeature.FOXHOLE_SENTINEL)

        assertEquals(listOf(DatasetActivationSource.FOXHOLE_DB), initial.feature.availableDatasetSources())
        assertEquals(initial, initial.selectSource(DatasetActivationSource.TOR_PROJECT))
        val required = initial.reduce(DatasetActivationEvent.USE)
        assertEquals(DatasetActivationStep.DATASET_REQUIRED, required.step)
        assertEquals(required, required.reduce(DatasetActivationEvent.VERIFY))
        assertFalse(
            datasetActivationAllowed(
                requested = true,
                feature = DatasetActivationFeature.FOXHOLE_SENTINEL,
                source = DatasetActivationSource.FOXHOLE_DB,
                verifiedDataset = false,
            ),
        )
    }

    @Test
    fun `download reaches activation only through running then verified`() {
        val running =
            DatasetActivationState(DatasetActivationFeature.FOXHOLE_SENTINEL)
                .reduce(DatasetActivationEvent.USE)
                .reduce(DatasetActivationEvent.START_DOWNLOAD)
        assertEquals(DatasetActivationStep.DOWNLOADING, running.step)
        assertEquals(DatasetActivationStep.VERIFIED, running.reduce(DatasetActivationEvent.VERIFY).step)
        assertEquals(DatasetActivationStep.FAILED, running.reduce(DatasetActivationEvent.FAIL).step)

        val failed = running.reduce(DatasetActivationEvent.FAIL)
        assertEquals(
            DatasetActivationStep.DOWNLOADING,
            failed
                .reduce(DatasetActivationEvent.RETRY)
                .reduce(DatasetActivationEvent.START_DOWNLOAD)
                .step,
        )
    }

    @Test
    fun `activation matrix requires verified data exactly for FoxHole DB`() {
        assertTrue(
            datasetActivationAllowed(
                requested = true,
                feature = DatasetActivationFeature.TOR_BRIDGES,
                source = DatasetActivationSource.TOR_PROJECT,
                verifiedDataset = false,
            ),
        )
        assertFalse(
            datasetActivationAllowed(
                requested = true,
                feature = DatasetActivationFeature.TOR_BRIDGES,
                source = DatasetActivationSource.FOXHOLE_DB,
                verifiedDataset = false,
            ),
        )
        assertTrue(
            datasetActivationAllowed(
                requested = true,
                feature = DatasetActivationFeature.TOR_BRIDGES,
                source = DatasetActivationSource.FOXHOLE_DB,
                verifiedDataset = true,
            ),
        )
        assertFalse(
            datasetActivationAllowed(
                requested = false,
                feature = DatasetActivationFeature.TOR_BRIDGES,
                source = DatasetActivationSource.TOR_PROJECT,
                verifiedDataset = true,
            ),
        )
    }
}
