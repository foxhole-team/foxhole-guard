package com.foxhole.beta.ui

import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.foxhole.beta.R
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.profile.EditableProfileConfig
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import com.foxhole.beta.core.profile.PreparedProfileExport
import com.foxhole.beta.core.profile.ProfileConfigFormCodec
import com.foxhole.beta.core.profile.ProfileExportChoice
import com.foxhole.beta.core.profile.deleteProfileExportArtifact
import com.foxhole.beta.core.profile.exportableProfileChoices
import com.foxhole.beta.ui.FoxholeCard
import com.foxhole.beta.ui.FoxholeChoiceCard
import com.foxhole.beta.ui.FoxholePreferenceCard
import com.foxhole.beta.ui.FoxholeScaffold
import com.foxhole.beta.ui.FoxholeSearchField
import com.foxhole.beta.ui.FoxholeValuePill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import kotlin.math.max

internal enum class InstalledAppFilter {
    ALL,
    USER,
    SYSTEM,
}

@Composable
fun ProfilesScreen(
    state: ProfilesRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onSetActiveProfile: (Long) -> Unit,
    onEditProfile: (Long) -> Unit,
    onSelectProtocolOption: (Long, String) -> Unit,
    onUpdateAutoConnectExcludedOptions: (Long, Set<String>) -> Unit,
    onRefreshSmartProfileMetrics: (Long) -> Unit,
    onCancelSmartProfileMetricsRefresh: () -> Unit,
    onRefreshProfile: (Long) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onCreateProfileExport: suspend (List<ProfileExportSelectionRequest>) -> PreparedProfileExport,
    onCreateProfileExportShareIntent: (PreparedProfileExport) -> Intent,
) {
    val context = LocalContext.current
    val profileExportSavedMessage = stringResource(R.string.profile_export_saved)
    val profileExportSaveFailedMessage = stringResource(R.string.profile_export_save_failed)
    val shareArchiveTitle = stringResource(R.string.share_archive)
    val scope = rememberCoroutineScope()
    val exportChoicesByProfileId =
        remember(state.profiles) {
            state.profiles.associate { profile ->
                profile.id to exportableProfileChoices(profile)
            }
        }
    var deleteProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var refreshProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var exportMode by rememberSaveable { mutableStateOf(false) }
    var exportSelectionState by rememberSaveable(stateSaver = ProfilesExportSelectionStateSaver) {
        mutableStateOf(ProfilesExportSelectionState())
    }
    var exportDestinationDialogVisible by rememberSaveable { mutableStateOf(false) }
    var exportInFlight by rememberSaveable { mutableStateOf(false) }
    var pendingProfileExportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingProfileExportFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingProfileExportMimeType by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedExportRequests =
        remember(exportSelectionState) {
            exportSelectionState.requests()
        }
    val selectedExportConfigCount =
        remember(selectedExportRequests) {
            selectedExportRequests.sumOf { request -> request.selectionKeys.size }
        }
    val rememberedSmartStartLatenciesByProfileId = state.smartStartRememberedLatenciesByProfileId
    var sessionProfileOrderIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    val profileMembershipKey =
        remember(state.profiles) {
            state.profiles.map(Profile::id).sorted()
        }
    val visibleProfiles =
        remember(state.profiles, sessionProfileOrderIds) {
            visibleProfilesForProfilesSession(
                currentProfiles = state.profiles,
                currentSessionOrderIds = sessionProfileOrderIds,
            )
        }

    LaunchedEffect(state.profiles, exportMode) {
        if (!exportMode) {
            exportSelectionState = ProfilesExportSelectionState()
            return@LaunchedEffect
        }
        exportSelectionState = exportSelectionState.pruneTo(state.profiles)
    }

    LaunchedEffect(state.profilesLoaded, profileMembershipKey) {
        if (!state.profilesLoaded) {
            return@LaunchedEffect
        }
        sessionProfileOrderIds =
            reconcileProfilesScreenSessionOrderIds(
                currentProfiles = state.profiles,
                currentSessionOrderIds = sessionProfileOrderIds,
            )
    }

    val saveProfileExportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val export =
                pendingProfileExportPath
                    ?.let { filePath ->
                        pendingProfileExportFileName?.let { fileName ->
                            pendingProfileExportMimeType?.let { mimeType ->
                                PreparedProfileExport(
                                    file = java.io.File(filePath),
                                    fileName = fileName,
                                    mimeType = mimeType,
                                )
                            }
                        }
                    }
            pendingProfileExportPath = null
            pendingProfileExportFileName = null
            pendingProfileExportMimeType = null
            if (uri == null || export == null) {
                export?.let(::deleteProfileExportArtifact)
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            export.file.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("failed to open export target")
                    }
                }.onSuccess {
                    snackbarHostState.showBanner(
                        profileExportSavedMessage,
                        FoxholeBannerTone.SUCCESS,
                    )
                }.onFailure {
                    snackbarHostState.showBanner(
                        profileExportSaveFailedMessage,
                        FoxholeBannerTone.ERROR,
                    )
                }.also {
                    deleteProfileExportArtifact(export)
                }
            }
        }

    SettingsScaffold(
        title = stringResource(R.string.profile_list_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        actions = {
            SettingsHelpAction(
                title = stringResource(R.string.help_profiles_subscriptions_title),
                body = stringResource(R.string.help_profiles_subscriptions_body),
                icon = Icons.Outlined.VpnKey,
            )
            if (exportMode) {
                IconButton(
                    onClick = {
                        exportMode = false
                        exportSelectionState = ProfilesExportSelectionState()
                        exportDestinationDialogVisible = false
                    },
                    modifier = Modifier.testTag("profiles_export_cancel_action"),
                ) {
                    Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = stringResource(R.string.cancel))
                }
                FoxholeSaveAction(
                    onClick = { exportDestinationDialogVisible = true },
                    enabled = selectedExportRequests.isNotEmpty() && !exportInFlight,
                    icon = Icons.Outlined.Save,
                    modifier = Modifier.testTag("profiles_export_action"),
                )
            } else {
                IconButton(
                    onClick = { exportMode = true },
                    enabled = state.profiles.isNotEmpty(),
                    modifier = Modifier.testTag("profiles_export_action"),
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.export_label))
                }
            }
        },
    ) {
        if (!state.profilesLoaded) {
            items(3) { index ->
                ProfileListLoadingCard(tag = "profiles_loading_$index")
            }
        } else if (state.profiles.isEmpty()) {
            item {
                ProfilesEmptyInfoBlock()
            }
        }
        items(visibleProfiles, key = Profile::id) { profile ->
            val isSelected = profile.id == state.activeProfileId
            val isSmartProfile = MultiProtocolProfileSupport.hasMultipleSupportedOptions(profile)
            val showInlineRefreshAction =
                isSmartProfile && profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL
            val showInsecureTlsActionBadge = selectedProtocolRequiresInsecureTls(profile)
            val profileSelectionColor = foxholeSystemProfileSelectionColor()
            val exportChoices = exportChoicesByProfileId[profile.id].orEmpty()
            val exportSelectedKeys = exportSelectionState.selectedKeys(profile.id)
            val exportSelectionMode =
                if (exportChoices.size > 1) {
                    exportSelectionState.smartProfileSelectionState(profile)
                } else if (exportSelectedKeys.isNotEmpty()) {
                    SmartProfileExportSelectionState.ALL
                } else {
                    SmartProfileExportSelectionState.NONE
                }
            val exportCardSelected = exportMode && exportSelectedKeys.isNotEmpty()
            val profileSwipeActions =
                if (exportMode) {
                    emptyList()
                } else {
                    buildList {
                        if (showInlineRefreshAction) {
                            add(
                                FoxholeSwipeAction(
                                    icon = Icons.Outlined.Refresh,
                                    contentDescription = stringResource(R.string.refresh),
                                    testTag = "profiles_profile_refresh_action_${profile.id}",
                                    onClick = { refreshProfileId = profile.id },
                                ),
                            )
                        }
                        add(
                            FoxholeSwipeAction(
                                icon = Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.edit_label),
                                testTag = "profiles_profile_edit_action_${profile.id}",
                                onClick = { onEditProfile(profile.id) },
                            ),
                        )
                        add(
                            FoxholeSwipeAction(
                                icon = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.delete_label),
                                testTag = "profiles_profile_delete_action_${profile.id}",
                                tint = MaterialTheme.colorScheme.error,
                                onClick = { deleteProfileId = profile.id },
                            ),
                        )
                    }
                }
            FoxholeSwipeActions(
                key = "profile-${profile.id}",
                actions = profileSwipeActions,
            ) {
                FoxholeCard(
                    onClick = {
                        if (exportMode) {
                            exportSelectionState =
                                when {
                                    exportChoices.size > 1 -> exportSelectionState.toggleSmartProfileExpanded(profile.id)
                                    else -> exportSelectionState.toggleSingleProfile(profile)
                                }
                        } else if (!isSelected) {
                            onSetActiveProfile(profile.id)
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("profiles_profile_row_${profile.id}"),
                    containerColor =
                        if (exportCardSelected) {
                            FoxholeInfoAccent.copy(alpha = 0.08f)
                        } else {
                            Color.Unspecified
                        },
                    borderColor =
                        if (exportCardSelected) {
                            FoxholeInfoAccent.copy(alpha = 0.42f)
                        } else if (isSelected) {
                            profileSelectionColor.copy(alpha = 0.42f)
                        } else {
                            Color.Unspecified
                        },
                ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .foxholeAnimateContentSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (exportMode) {
                        ProfileExportSelector(
                            selectionState = exportSelectionMode,
                            onClick = {
                                exportSelectionState =
                                    when {
                                        exportChoices.size > 1 -> exportSelectionState.toggleSmartProfileAll(profile)
                                        else -> exportSelectionState.toggleSingleProfile(profile)
                                    }
                            },
                            modifier = Modifier.padding(top = 2.dp).testTag("profiles_export_profile_selector_${profile.id}"),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        InlineSmartProfileTitle(
                            title = profile.name,
                            isSmartProfile = isSmartProfile,
                            showV2RayTunBadge = profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL && !isSmartProfile,
                        )
                        Text(
                            text = rememberProfileSourceSummary(profile),
                            style =
                                if (profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL && profile.subscriptionExpiresAt != null) {
                                    MaterialTheme.typography.labelLarge
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ProtocolMetadataRow(
                            protocol = profile.protocolHint,
                            subscriptionExpiresAt = null,
                            protocolOptions = profile.protocolOptions,
                            selectedProtocolOptionId = profile.selectedProtocolOptionId,
                            onProtocolOptionSelected = { optionId -> onSelectProtocolOption(profile.id, optionId) },
                            compact = true,
                            latencyByOptionId = rememberedSmartStartLatenciesByProfileId[profile.id].orEmpty(),
                            downProtocolOptionIds = state.smartProfileDownOptionIdsByProfileId[profile.id].orEmpty(),
                            recommendedProtocolOptionId = state.recommendedProtocolOptionByProfileId[profile.id],
                            recommendedProtocolOptionIds = state.recommendedProtocolOptionsByProfileId[profile.id].orEmpty(),
                            favoriteProtocolOptionId = state.favoriteProtocolOptionByProfileId[profile.id],
                            requiresInsecureTls = profile.requiresInsecureTls,
                            showInsecureTlsBadge = false,
                            reserveTrailingSpace = false,
                            expand = true,
                            leadingContent =
                                if (isSmartProfile) {
                                    {
                                        SmartProfileAutoConnectMenu(
                                            profile = profile,
                                            excludedOptionIds = state.smartProfileExcludedOptionIdsByProfileId[profile.id].orEmpty(),
                                            onUpdateExcludedOptionIds = { excludedIds ->
                                                onUpdateAutoConnectExcludedOptions(profile.id, excludedIds)
                                            },
                                            latencyByOptionId = rememberedSmartStartLatenciesByProfileId[profile.id].orEmpty(),
                                            unavailableOptionIds = state.smartProfileDownOptionIdsByProfileId[profile.id].orEmpty(),
                                            serverPingByOptionId = state.smartProfileServerPings(profile.id),
                                            serverPingUnavailableOptionIds = state.smartProfileServerPingUnavailable(profile.id),
                                            metricsUpdatedAtByOptionId = state.smartProfileMetricsUpdatedAt(profile.id),
                                            metricsRefreshing = profile.id in state.smartProfileMetricsRefreshingProfileIds,
                                            refreshingOptionId = state.smartProfileMetricsRefreshingOptionIdByProfileId[profile.id],
                                            recommendedOptionId = state.recommendedProtocolOptionByProfileId[profile.id],
                                            recommendedOptionIds = state.recommendedProtocolOptionsByProfileId[profile.id].orEmpty(),
                                            favoriteOptionId = state.favoriteProtocolOptionByProfileId[profile.id],
                                            onRefreshMetrics = { onRefreshSmartProfileMetrics(profile.id) },
                                            onCancelRefreshMetrics = onCancelSmartProfileMetricsRefresh,
                                            refreshWarningRequired = true,
                                            showLatency =
                                                profile.id in state.smartProfileMetricsRefreshingProfileIds ||
                                                    rememberedSmartStartLatenciesByProfileId[profile.id]?.isNotEmpty() == true,
                                            compact = true,
                                            showTransportBadges = true,
                                            latencyProbeMethod = state.settings.connection.latencyProbeMethod,
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                        if (exportMode && exportChoices.size > 1) {
                            Text(
                                text =
                                    pluralStringResource(
                                        R.plurals.profile_export_selection_summary,
                                        exportChoices.size,
                                        exportSelectedKeys.size,
                                        exportChoices.size,
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (exportMode) {
                        if (exportChoices.size > 1) {
                            Icon(
                                imageVector =
                                    if (exportSelectionState.isSmartProfileExpanded(profile.id)) {
                                        Icons.Outlined.ExpandLess
                                    } else {
                                        Icons.Outlined.ExpandMore
                                    },
                                contentDescription = null,
                                modifier = Modifier.padding(top = 4.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (showInsecureTlsActionBadge) {
                        InsecureTlsProfileBadge(
                            modifier = Modifier.align(Alignment.CenterVertically),
                            compact = true,
                        )
                    }
                }
                if (exportMode && exportChoices.size > 1 && exportSelectionState.isSmartProfileExpanded(profile.id)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(start = 42.dp, top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f))
                        exportChoices.forEachIndexed { index, choice ->
                            ProfileExportChoiceRow(
                                title = choice.displayName,
                                selected = choice.selectionKey in exportSelectedKeys,
                                onClick = {
                                    exportSelectionState =
                                        exportSelectionState.toggleSmartProfileChoice(
                                            profile = profile,
                                            selectionKey = choice.selectionKey,
                                        )
                                },
                                modifier = Modifier.testTag("profiles_export_protocol_selector_${profile.id}_$index"),
                                showDivider = index < exportChoices.lastIndex,
                            )
                        }
                    }
                }
                }
            }
        }
    }

    if (exportDestinationDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                if (!exportInFlight) {
                    exportDestinationDialogVisible = false
                }
            },
            title = { Text(stringResource(R.string.profile_export_destination_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.profile_export_destination_summary,
                        selectedExportConfigCount,
                        selectedExportConfigCount,
                    ),
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FoxholeDialogConfirmButton(
                        onClick = {
                            val exportRequests = selectedExportRequests
                            scope.launch {
                                exportInFlight = true
                                try {
                                    val export = onCreateProfileExport(exportRequests)
                                    pendingProfileExportPath = export.file.absolutePath
                                    pendingProfileExportFileName = export.fileName
                                    pendingProfileExportMimeType = export.mimeType
                                    exportDestinationDialogVisible = false
                                    saveProfileExportLauncher.launch(export.fileName)
                                    exportMode = false
                                    exportSelectionState = ProfilesExportSelectionState()
                                } finally {
                                    exportInFlight = false
                                }
                            }
                        },
                        enabled = selectedExportRequests.isNotEmpty() && !exportInFlight,
                        label = stringResource(R.string.profile_export_save_to_disk),
                    )
                    FoxholeDialogSecondaryButton(
                        label = stringResource(R.string.share_archive),
                        onClick = {
                            val exportRequests = selectedExportRequests
                            scope.launch {
                                exportInFlight = true
                                try {
                                    val export = onCreateProfileExport(exportRequests)
                                    exportDestinationDialogVisible = false
                                    val chooser =
                                        Intent.createChooser(
                                            onCreateProfileExportShareIntent(export),
                                            shareArchiveTitle,
                                        )
                                    runCatching { context.startActivity(chooser) }
                                        .onFailure {
                                            deleteProfileExportArtifact(export)
                                            throw it
                                        }
                                    launch {
                                        delay(PROFILE_EXPORT_SHARE_CLEANUP_DELAY_MS)
                                        deleteProfileExportArtifact(export)
                                    }
                                    exportMode = false
                                    exportSelectionState = ProfilesExportSelectionState()
                                } finally {
                                    exportInFlight = false
                                }
                            }
                        },
                        enabled = selectedExportRequests.isNotEmpty() && !exportInFlight,
                    )
                    FoxholeDialogDismissButton(
                        onClick = { exportDestinationDialogVisible = false },
                        enabled = !exportInFlight,
                    )
                }
            },
            dismissButton = {},
        )
    }

    deleteProfileId?.let { profileId ->
        val profileName = state.profile(profileId)?.name.orEmpty()
        val deleteSummary = stringResource(R.string.delete_profile_summary)
        ConfirmDialog(
            title = stringResource(R.string.delete_profile_title),
            body =
                if (profileName.isBlank()) {
                    deleteSummary
                } else {
                    "$deleteSummary\n\n$profileName"
                },
            confirmLabel = stringResource(R.string.delete_label),
            icon = Icons.Outlined.Delete,
            iconTint = MaterialTheme.colorScheme.error,
            iconContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { deleteProfileId = null },
            onConfirm = {
                onDeleteProfile(profileId)
                deleteProfileId = null
            },
        )
    }

    refreshProfileId?.let { profileId ->
        ProfileRefreshConfirmDialog(
            onDismiss = { refreshProfileId = null },
            onConfirm = {
                onRefreshProfile(profileId)
                refreshProfileId = null
            },
        )
    }
}

@Composable
private fun ProfilesEmptyInfoBlock() {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("profiles_empty_state"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.profiles_empty_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfileExportSelector(
    selectionState: SmartProfileExportSelectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(28.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val (icon, tint) =
                when (selectionState) {
                    SmartProfileExportSelectionState.ALL -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
                    SmartProfileExportSelectionState.PARTIAL -> Icons.Outlined.RemoveCircleOutline to FoxholeInfoAccent
                    SmartProfileExportSelectionState.NONE -> Icons.Outlined.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
                }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ProfileExportChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileExportSelector(
                selectionState =
                    if (selected) {
                        SmartProfileExportSelectionState.ALL
                    } else {
                        SmartProfileExportSelectionState.NONE
                    },
                onClick = onClick,
            )
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
        }
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList")
fun ProfileDetailScreen(
    profile: Profile?,
    activeProfileId: Long?,
    excludedAutoConnectOptionIds: Set<String>,
    rememberedSmartStartLatenciesByOptionId: Map<String, Long>,
    downOptionIds: Set<String>,
    serverPingByOptionId: Map<String, Long>,
    serverPingUnavailableOptionIds: Set<String>,
    metricsUpdatedAtByOptionId: Map<String, Long>,
    metricsRefreshing: Boolean,
    refreshingOptionId: String?,
    recommendedProtocolOptionId: String?,
    recommendedProtocolOptionIds: Set<String>,
    favoriteProtocolOptionId: String?,
    latencyProbeMethod: LatencyProbeMethod,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onSetActiveProfile: (Long) -> Unit,
    onSelectProtocolOption: (Long, String) -> Unit,
    onUpdateAutoConnectExcludedOptions: (Long, Set<String>) -> Unit,
    onRefreshSmartProfileMetrics: (Long) -> Unit,
    onCancelSmartProfileMetricsRefresh: () -> Unit,
    onRefreshProfile: (Long) -> Unit,
    onDeleteProfile: () -> Unit,
    onViewConfig: () -> Unit,
    onEditConfig: () -> Unit,
) {
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showRefreshDialog by rememberSaveable { mutableStateOf(false) }

    if (profile == null) {
        SettingsScaffold(
            title = stringResource(R.string.profile),
            snackbarHostState = snackbarHostState,
            onNavigateUp = onNavigateUp,
        ) {
            item {
                WarningBlock(
                    title = stringResource(R.string.profile_not_found_title),
                    body = stringResource(R.string.profile_not_found_summary),
                )
            }
        }
        return
    }

    SettingsScaffold(
        title = profile.name,
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            val sourceTitle =
                if (profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL) {
                    stringResource(R.string.profile_source_subscription)
                } else {
                    stringResource(R.string.profile_source)
                }
            val sourceValue = rememberProfileDetailSourceValue(profile)
            SettingValueRow(
                title = sourceTitle,
                value = sourceValue,
                onClick = null,
                trailingContent = {
                    Text(
                        text = sourceValue,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
            )
        }
        item {
            FoxholeCard {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    InlineSmartProfileTitle(
                        title = stringResource(R.string.protocol),
                        isSmartProfile = MultiProtocolProfileSupport.hasMultipleSupportedOptions(profile),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                    ProtocolMetadataRow(
                        protocol = profile.protocolHint,
                        subscriptionExpiresAt = null,
                        protocolOptions = profile.protocolOptions,
                        selectedProtocolOptionId = profile.selectedProtocolOptionId,
                        onProtocolOptionSelected = { optionId -> onSelectProtocolOption(profile.id, optionId) },
                        compact = true,
                        latencyByOptionId = rememberedSmartStartLatenciesByOptionId,
                        downProtocolOptionIds = downOptionIds,
                        recommendedProtocolOptionId = recommendedProtocolOptionId,
                        recommendedProtocolOptionIds = recommendedProtocolOptionIds,
                        favoriteProtocolOptionId = favoriteProtocolOptionId,
                        requiresInsecureTls = profile.requiresInsecureTls,
                        reserveTrailingSpace = false,
                        expand = true,
                        leadingContent =
                            if (MultiProtocolProfileSupport.hasMultipleSupportedOptions(profile)) {
                                {
                                    SmartProfileAutoConnectMenu(
                                        profile = profile,
                                        excludedOptionIds = excludedAutoConnectOptionIds,
                                        onUpdateExcludedOptionIds = { excludedIds ->
                                            onUpdateAutoConnectExcludedOptions(profile.id, excludedIds)
                                        },
                                        latencyByOptionId = rememberedSmartStartLatenciesByOptionId,
                                        unavailableOptionIds = downOptionIds,
                                        serverPingByOptionId = serverPingByOptionId,
                                        serverPingUnavailableOptionIds = serverPingUnavailableOptionIds,
                                        metricsUpdatedAtByOptionId = metricsUpdatedAtByOptionId,
                                        metricsRefreshing = metricsRefreshing,
                                        refreshingOptionId = refreshingOptionId,
                                        recommendedOptionId = recommendedProtocolOptionId,
                                        recommendedOptionIds = recommendedProtocolOptionIds,
                                        favoriteOptionId = favoriteProtocolOptionId,
                                        onRefreshMetrics = { onRefreshSmartProfileMetrics(profile.id) },
                                        onCancelRefreshMetrics = onCancelSmartProfileMetricsRefresh,
                                        refreshWarningRequired = true,
                                        showLatency = metricsRefreshing || rememberedSmartStartLatenciesByOptionId.isNotEmpty(),
                                        compact = true,
                                        showTransportBadges = true,
                                        latencyProbeMethod = latencyProbeMethod,
                                    )
                                }
                            } else {
                                null
                            },
                    )
                }
            }
        }
        item {
            SettingValueRow(
                title = stringResource(R.string.last_updated),
                value = formatProfileUpdatedAt(profile.lastUpdatedAt),
                onClick = null,
            )
        }
        item {
            SettingValueRow(
                title = stringResource(R.string.profile_status),
                value =
                    if (activeProfileId == profile.id) {
                        stringResource(R.string.active_label)
                    } else {
                        stringResource(R.string.inactive_label)
                    },
                onClick = null,
            )
        }
        if (activeProfileId != profile.id) {
            item {
                SettingsNavigationRow(
                    icon = Icons.Outlined.CheckCircle,
                    title = stringResource(R.string.use_profile),
                    summary = stringResource(R.string.use_profile_summary),
                    onClick = { onSetActiveProfile(profile.id) },
                )
            }
        }
        if (profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL) {
            item {
                SettingsNavigationRow(
                    icon = Icons.Outlined.Refresh,
                    title = stringResource(R.string.refresh),
                    summary = stringResource(R.string.profile_refresh_summary),
                    summaryMaxLines = 2,
                    onClick = { showRefreshDialog = true },
                )
            }
        }
        item {
            SettingsNavigationRow(
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.view_profile_config),
                summary = stringResource(R.string.view_profile_config_summary),
                onClick = onViewConfig,
            )
        }
        item {
            SettingsNavigationRow(
                icon = Icons.Outlined.Edit,
                title = stringResource(R.string.edit_profile_config),
                summary = stringResource(R.string.edit_profile_config_summary),
                onClick = onEditConfig,
            )
        }
        item {
            SettingsNavigationRow(
                icon = Icons.Outlined.Delete,
                title = stringResource(R.string.delete_profile_title),
                summary = stringResource(R.string.delete_profile_summary),
                onClick = { showDeleteDialog = true },
            )
        }
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = stringResource(R.string.delete_profile_title),
            body = stringResource(R.string.delete_profile_summary),
            confirmLabel = stringResource(R.string.delete_label),
            icon = Icons.Outlined.Delete,
            iconTint = MaterialTheme.colorScheme.error,
            iconContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                onDeleteProfile()
            },
        )
    }

    if (showRefreshDialog) {
        ProfileRefreshConfirmDialog(
            onDismiss = { showRefreshDialog = false },
            onConfirm = {
                showRefreshDialog = false
                onRefreshProfile(profile.id)
            },
        )
    }
}

internal fun reconcileProfilesScreenSessionOrderIds(
    currentProfiles: List<Profile>,
    currentSessionOrderIds: List<Long>,
): List<Long> {
    if (currentProfiles.isEmpty()) {
        return emptyList()
    }
    if (currentSessionOrderIds.isEmpty()) {
        return currentProfiles.map(Profile::id)
    }
    val currentIds = currentProfiles.map(Profile::id)
    val currentIdSet = currentIds.toSet()
    val retainedIds = currentSessionOrderIds.filter(currentIdSet::contains)
    val retainedIdSet = retainedIds.toSet()
    val appendedIds = currentIds.filterNot(retainedIdSet::contains)
    return retainedIds + appendedIds
}

internal fun visibleProfilesForProfilesSession(
    currentProfiles: List<Profile>,
    currentSessionOrderIds: List<Long>,
): List<Profile> {
    if (currentProfiles.isEmpty()) {
        return emptyList()
    }
    if (currentSessionOrderIds.isEmpty()) {
        return currentProfiles
    }
    val profilesById = currentProfiles.associateBy(Profile::id)
    val orderedProfiles = currentSessionOrderIds.mapNotNull(profilesById::get)
    if (orderedProfiles.size == currentProfiles.size) {
        return orderedProfiles
    }
    val orderedIds = orderedProfiles.map(Profile::id).toSet()
    return orderedProfiles + currentProfiles.filterNot { profile -> profile.id in orderedIds }
}

@Composable
fun ProfileConfigViewScreen(
    profile: Profile?,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onEditConfig: () -> Unit,
    onLoadConfig: suspend (Long) -> String,
) {
    val codec = remember { ProfileConfigFormCodec() }
    val loadTimeoutMessage = stringResource(R.string.profile_config_load_timeout)
    var draft by remember(profile?.id) { mutableStateOf<EditableProfileConfig?>(null) }
    var loadError by remember(profile?.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(profile?.id) {
        draft = null
        loadError = null
        val currentProfile = profile ?: return@LaunchedEffect
        runCatching {
            val loadedConfig =
                withTimeoutOrNull(ProfileConfigLoadTimeoutMs) {
                    onLoadConfig(currentProfile.id)
                } ?: error(loadTimeoutMessage)
            codec.decode(loadedConfig)
        }.onSuccess { draft = it }
            .onFailure { loadError = it.message ?: "failed to load config" }
    }

    SettingsScaffold(
        title = profile?.name ?: stringResource(R.string.view_profile_config),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        actions = {
            if (profile != null) {
                IconButton(onClick = onEditConfig) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.edit_label))
                }
            }
        },
    ) {
        item {
            if (profile == null) {
                WarningBlock(
                    title = stringResource(R.string.profile_not_found_title),
                    body = stringResource(R.string.profile_not_found_summary),
                )
            } else {
                when {
                    loadError != null -> {
                        Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                    draft == null -> {
                        Text(stringResource(R.string.loading_label))
                    }
                    else -> {
                        ProfileConfigForm(
                            profile = profile,
                            draft = draft ?: return@item,
                            editable = false,
                            onDraftChanged = {},
                            onEditRequested = { _, _, _, _ -> },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileConfigEditScreen(
    profile: Profile?,
    canReconnectNow: Boolean,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onLoadConfig: suspend (Long, String?) -> String,
    onSaveConfig: suspend (Long, String?, String, Boolean) -> Unit,
) {
    val codec = remember { ProfileConfigFormCodec() }
    val scope = rememberCoroutineScope()
    val loadTimeoutMessage = stringResource(R.string.profile_config_load_timeout)
    var sourceConfig by remember(profile?.id) { mutableStateOf<String?>(null) }
    var draft by remember(profile?.id) { mutableStateOf<EditableProfileConfig?>(null) }
    var loadError by remember(profile?.id) { mutableStateOf<String?>(null) }
    var editorProtocolOptionId by rememberSaveable(profile?.id) { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var fieldDialog by remember { mutableStateOf<ProfileFieldDialogState?>(null) }

    fun loadEditorConfig(
        currentProfile: Profile,
        protocolOptionId: String?,
    ) {
        sourceConfig = null
        draft = null
        loadError = null
        editorProtocolOptionId = protocolOptionId
        scope.launch {
            runCatching {
                val loadedConfig =
                    withTimeoutOrNull(ProfileConfigLoadTimeoutMs) {
                        onLoadConfig(currentProfile.id, protocolOptionId)
                    } ?: error(loadTimeoutMessage)
                loadedConfig to codec.decode(loadedConfig)
            }
                .onSuccess { (loadedConfig, loadedDraft) ->
                    sourceConfig = loadedConfig
                    draft = loadedDraft
                }.onFailure {
                    loadError = it.message ?: "failed to load config"
                }
        }
    }

    LaunchedEffect(profile?.id) {
        val currentProfile = profile ?: return@LaunchedEffect
        loadEditorConfig(
            currentProfile = currentProfile,
            protocolOptionId = MultiProtocolProfileSupport.selectedOption(currentProfile)?.id,
        )
    }

    SettingsScaffold(
        title = profile?.name ?: stringResource(R.string.edit_profile_config),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        actions = {
            FoxholeSaveAction(
                enabled = profile != null && sourceConfig != null && draft != null && !saving,
                onClick = { showSaveDialog = true },
                label = stringResource(R.string.save),
                icon = Icons.Outlined.Save,
            )
        },
    ) {
        item {
            if (profile == null) {
                WarningBlock(
                    title = stringResource(R.string.profile_not_found_title),
                    body = stringResource(R.string.profile_not_found_summary),
                )
            } else {
                when {
                    loadError != null -> {
                        Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                    draft == null -> {
                        Text(stringResource(R.string.loading_label))
                    }
                    else -> {
                        ProfileConfigForm(
                            profile = profile,
                            draft = draft ?: return@item,
                            editable = true,
                            selectedProtocolOptionId = editorProtocolOptionId,
                            onProtocolOptionSelected = { optionId -> loadEditorConfig(profile, optionId) },
                            onDraftChanged = { draft = it },
                            onEditRequested = { title, value, singleLine, onConfirm ->
                                fieldDialog =
                                    ProfileFieldDialogState(
                                        title = title,
                                        initialValue = value,
                                        singleLine = singleLine,
                                        onConfirm = onConfirm,
                                    )
                            },
                        )
                    }
                }
            }
        }
    }

    fieldDialog?.let { dialog ->
        ProfileFieldDialog(
            title = dialog.title,
            initialValue = dialog.initialValue,
            singleLine = dialog.singleLine,
            onDismiss = { fieldDialog = null },
            onConfirm = {
                dialog.onConfirm(it)
                fieldDialog = null
            },
        )
    }

    if (showSaveDialog) {
        fun saveProfile(reconnectAfterSave: Boolean) {
            val currentProfile = profile ?: return
            val currentSourceConfig = sourceConfig ?: return
            val currentDraft = draft ?: return
            showSaveDialog = false
            scope.launch {
                saving = true
                try {
                    val updatedConfig = codec.encode(currentSourceConfig, currentDraft)
                    onSaveConfig(currentProfile.id, editorProtocolOptionId, updatedConfig, reconnectAfterSave)
                } finally {
                    saving = false
                }
            }
        }

        ProfileSaveConfirmDialog(
            canReconnectNow = canReconnectNow,
            onDismiss = { showSaveDialog = false },
            onSave = { saveProfile(reconnectAfterSave = false) },
            onSaveAndReconnect = { saveProfile(reconnectAfterSave = true) },
        )
    }
}

private data class ProfileFieldDialogState(
    val title: String,
    val initialValue: String,
    val singleLine: Boolean,
    val onConfirm: (String) -> Unit,
)

private const val ProfileConfigLoadTimeoutMs = 8_000L
private const val PROFILE_EXPORT_SHARE_CLEANUP_DELAY_MS = 5L * 60L * 1000L
