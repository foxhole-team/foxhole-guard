package com.foxhole.guard.ui

internal enum class DatasetActivationFeature {
    TOR_BRIDGES,
    FOXHOLE_SENTINEL,
}

internal enum class DatasetActivationSource {
    TOR_PROJECT,
    FOXHOLE_DB,
}

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

internal fun datasetActivationAllowed(
    requested: Boolean,
    feature: DatasetActivationFeature,
    source: DatasetActivationSource,
    verifiedDataset: Boolean,
): Boolean =
    requested &&
        source in feature.availableDatasetSources() &&
        (!datasetDownloadRequired(feature, source) || verifiedDataset)
