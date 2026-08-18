package com.foxhole.guard.ui.cli.profiles

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliCommands
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.cliMarchingBorder
import com.foxhole.guard.ui.cli.components.cliModalSurfaceColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

@Composable
internal fun CliQrScannerOverlay(
    title: String,
    onDismiss: () -> Unit,
    onQrDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalCliColors.current
    var cameraGranted by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var payload by rememberSaveable { mutableStateOf<String?>(null) }
    var scanner by remember { mutableStateOf<BarcodeView?>(null) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraGranted = it }
    LaunchedEffect(cameraGranted) {
        if (!cameraGranted) permission.launch(Manifest.permission.CAMERA)
    }
    DisposableEffect(Unit) { onDispose { scanner?.pause() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(cliModalSurfaceColor(LocalCliPanelAppearance.current, colors.panel))
                .padding(CliSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text(text = "> ${CliCommands.SCAN_QR}", style = CliType.title, color = colors.accent)
            Text(text = title, style = CliType.small, color = colors.dim)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(cliModalSurfaceColor(LocalCliPanelAppearance.current, colors.panel))
                    .then(
                        if (payload == null) {
                            Modifier.cliMarchingBorder(colors.accent)
                        } else {
                            Modifier.border(2.dp, colors.ok)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (cameraGranted) {
                    AndroidView(
                        factory = { viewContext ->
                            BarcodeView(viewContext).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                                decodeContinuous(object : BarcodeCallback {
                                    override fun barcodeResult(result: BarcodeResult?) {
                                        val raw = result?.text?.trim().orEmpty()
                                        if (raw.isNotEmpty() && payload == null) payload = raw
                                    }

                                    override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) = Unit
                                })
                                scanner = this
                            }
                        },
                        update = { view -> if (payload == null) view.resume() else view.pause() },
                        modifier = Modifier.fillMaxSize(),
                    )
                    CliQrReticle(color = colors.accent)
                } else {
                    Text(
                        text = stringResource(R.string.camera_permission_required),
                        style = CliType.body,
                        color = colors.warn,
                        modifier = Modifier.padding(CliSpacing.lg),
                    )
                }
            }
            payload?.let { detected ->
                CliQrConfirmation(
                    payload = detected,
                    fallbackTitle = title,
                    onConfirm = { onQrDetected(detected) },
                    onReject = { payload = null },
                )
            }
            CliButton(
                label = stringResource(R.string.cli_common_no_cancel),
                color = colors.err,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CliQrReticle(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        val cell = 3.dp.toPx()
        val arm = cell * 5
        for ((sx, sy) in listOf(1 to 1, -1 to 1, 1 to -1, -1 to -1)) {
            val ox = if (sx > 0) 0f else size.width
            val oy = if (sy > 0) 0f else size.height
            drawRect(
                color = color,
                topLeft = Offset(if (sx > 0) ox else ox - arm, if (sy > 0) oy else oy - cell),
                size = Size(arm, cell),
            )
            drawRect(
                color = color,
                topLeft = Offset(if (sx > 0) ox else ox - cell, if (sy > 0) oy else oy - arm),
                size = Size(cell, arm),
            )
        }
    }
}

@Composable
private fun CliQrConfirmation(
    payload: String,
    fallbackTitle: String,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliPanel(
        icon = R.drawable.pix_qr,
        title = stringResource(R.string.cli_qr_detected_title),
        modifier = Modifier.fillMaxWidth()
    ) {
        CliKeyValue(
            key = stringResource(R.string.cli_qr_key_protocol),
            value = cliQrProtocol(payload) ?: stringResource(R.string.cli_common_unknown),
            valueColor = colors.ok,
        )
        CliKeyValue(
            key = stringResource(R.string.cli_qr_key_server),
            value = cliQrServer(payload) ?: fallbackTitle,
            valueColor = colors.fg,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm), modifier = Modifier.fillMaxWidth()) {
            CliButton(label = stringResource(R.string.yes_label), onClick = onConfirm, modifier = Modifier.weight(1f))
            CliButton(label = stringResource(R.string.no_label), onClick = onReject, modifier = Modifier.weight(1f))
        }
    }
}

internal fun cliQrProtocol(raw: String): String? {
    val value = raw.trim()
    return when {
        value.startsWith("vless://", true) -> "VLESS"
        value.startsWith("vmess://", true) -> "VMESS"
        value.startsWith("trojan://", true) -> "TROJAN"
        value.startsWith("hysteria2://", true) || value.startsWith("hy2://", true) -> "HYSTERIA2"
        value.startsWith("ss://", true) || value.startsWith("outline://", true) -> "SHADOWSOCKS"
        value.startsWith("wg://", true) || value.contains("[Interface]") -> "WIREGUARD"
        value.startsWith("https://", true) -> "HTTPS"
        else -> null
    }
}

internal fun cliQrServer(raw: String): String? {
    val authority = raw.trim().substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
    val host = authority.substringAfterLast('@').substringBefore(':').trim().ifEmpty { return null }
    if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return host
    val labels = host.split('.').filter(String::isNotBlank)
    return if (labels.size <= 2) host else labels.takeLast(2).joinToString(".")
}
