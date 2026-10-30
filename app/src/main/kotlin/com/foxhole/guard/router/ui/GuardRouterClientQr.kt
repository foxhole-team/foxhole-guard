package com.foxhole.guard.router.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** QR is generated locally from an explicitly requested authenticated export; never fetched. */
@Composable
internal fun GuardRouterClientQr(profile: String, name: String) {
    key(profile) { RouterClientQrImage(profile, name) }
}

private sealed interface ClientQrState {
    data object Loading : ClientQrState
    data object Unavailable : ClientQrState
    data class Ready(val bitmap: Bitmap) : ClientQrState
}

@Composable
private fun RouterClientQrImage(profile: String, name: String) {
    val state by produceState<ClientQrState>(ClientQrState.Loading, profile) {
        value = ClientQrState.Loading
        value = withContext(Dispatchers.Default) {
            runCatching {
                require(profile.toByteArray(Charsets.UTF_8).size in 1..8192)
                val code = QRCodeWriter().encode(
                    profile,
                    BarcodeFormat.QR_CODE,
                    600,
                    600,
                    mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2),
                )
                val pixels = IntArray(code.width * code.height) { position ->
                    if (code[position % code.width, position / code.width]) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
                }
                Bitmap.createBitmap(pixels, code.width, code.height, Bitmap.Config.ARGB_8888)
            }.fold(onSuccess = { ClientQrState.Ready(it) }, onFailure = { ClientQrState.Unavailable })
        }
    }
    when (val actual = state) {
        ClientQrState.Loading -> Text(stringResource(R.string.guard_router_router_control_qr_loading))
        ClientQrState.Unavailable -> Text(stringResource(R.string.guard_router_router_control_qr_unavailable))
        is ClientQrState.Ready -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Image(
            actual.bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.guard_router_router_control_qr_description, name),
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f),
        ) }
    }
}
