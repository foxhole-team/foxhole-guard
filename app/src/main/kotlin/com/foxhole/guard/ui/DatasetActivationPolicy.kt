package com.foxhole.guard.ui

/** Features whose first activation may depend on a remotely verified data set. */
internal enum class DatasetActivationFeature {
    TOR_BRIDGES,
    FOXHOLE_SENTINEL,
}

/** User-visible source choices shared by Settings and the first-run wizard. */
internal enum class DatasetActivationSource {
    TOR_PROJECT,
    FOXHOLE_DB,
}

/** The pure state of the source -> required data -> verified activation flow. */
internal enum class DatasetActivationStep {
    SOURCE,
    DATASET_REQUIRED,
    DOWNLOADING,
    VERIFIED,
    FAILED,
}

internal enum class DatasetActivationEvent {
    USE,
    START_DOWNLOAD,
    VERIFY,
    FAIL,
    RETRY,
}

internal data class DatasetActivationState(
    val feature: DatasetActivationFeature,
    val source: DatasetActivationSource = feature.defaultDatasetSource(),
    val step: DatasetActivationStep = DatasetActivationStep.SOURCE,
)

internal fun DatasetActivationFeature.defaultDatasetSource(): DatasetActivationSource =
    when (this) {
        DatasetActivationFeature.TOR_BRIDGES -> DatasetActivationSource.TOR_PROJECT
        DatasetActivationFeature.FOXHOLE_SENTINEL -> DatasetActivationSource.FOXHOLE_DB
    }

internal fun DatasetActivationFeature.availableDatasetSources(): List<DatasetActivationSource> =
    when (this) {
        DatasetActivationFeature.TOR_BRIDGES ->
            listOf(DatasetActivationSource.TOR_PROJECT, DatasetActivationSource.FOXHOLE_DB)
        DatasetActivationFeature.FOXHOLE_SENTINEL -> listOf(DatasetActivationSource.FOXHOLE_DB)
    }

/** Only FoxHole DB choices require the signed remote data-set gate. */
internal fun datasetDownloadRequired(
    feature: DatasetActivationFeature,
    source: DatasetActivationSource,
): Boolean = when (feature) {
    DatasetActivationFeature.TOR_BRIDGES,
    DatasetActivationFeature.FOXHOLE_SENTINEL,
    -> source == DatasetActivationSource.FOXHOLE_DB
}

internal fun DatasetActivationState.selectSource(source: DatasetActivationSource): DatasetActivationState =
    if (source in feature.availableDatasetSources()) {
        copy(source = source, step = DatasetActivationStep.SOURCE)
    } else {
        this
    }

/** Invalid/out-of-order events are ignored, so a late callback cannot skip the verification gate. */
internal fun DatasetActivationState.reduce(event: DatasetActivationEvent): DatasetActivationState =
    when (event) {
        DatasetActivationEvent.USE ->
            if (step == DatasetActivationStep.SOURCE) {
                copy(
                    step =
                    if (datasetDownloadRequired(feature, source)) {
                        DatasetActivationStep.DATASET_REQUIRED
                    } else {
                        DatasetActivationStep.VERIFIED
                    },
                )
            } else {
                this
            }
        DatasetActivationEvent.START_DOWNLOAD ->
            if (step == DatasetActivationStep.DATASET_REQUIRED || step == DatasetActivationStep.FAILED) {
                copy(step = DatasetActivationStep.DOWNLOADING)
            } else {
                this
            }
        DatasetActivationEvent.VERIFY ->
            if (step == DatasetActivationStep.DOWNLOADING) {
                copy(step = DatasetActivationStep.VERIFIED)
            } else {
                this
            }
        DatasetActivationEvent.FAIL ->
            if (step == DatasetActivationStep.DOWNLOADING) {
                copy(step = DatasetActivationStep.FAILED)
            } else {
                this
            }
        DatasetActivationEvent.RETRY ->
            if (step == DatasetActivationStep.FAILED) {
                copy(step = DatasetActivationStep.DATASET_REQUIRED)
            } else {
                this
            }
    }

/** Shared final commit gate for Settings and onboarding. */
internal fun datasetActivationAllowed(
    requested: Boolean,
    feature: DatasetActivationFeature,
    source: DatasetActivationSource,
    verifiedDataset: Boolean,
): Boolean =
    requested &&
        source in feature.availableDatasetSources() &&
        (!datasetDownloadRequired(feature, source) || verifiedDataset)
