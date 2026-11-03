package com.foxhole.guard.router.ui

import com.foxhole.guard.ui.cli.components.CliBottomSheet
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import com.foxhole.guard.ui.cli.LocalCliColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.foxhole.guard.ui.cli.components.CliButton
import androidx.compose.ui.graphics.RectangleShape
import com.foxhole.guard.router.routerLanDeviceUsesVpn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.guard.R
import com.foxhole.guard.router.GuardRouterProfileSummary
import com.foxhole.guard.router.RouterInstallCatalog
import com.foxhole.guard.router.RouterInstallProtocol
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GuardRouterProtocolDropdown(
    catalog: RouterInstallCatalog?,
    selected: Set<RouterInstallProtocol>,
    enabled: Boolean,
    onSelect: (Set<RouterInstallProtocol>) -> Unit,
) {
    var expanded by remember(catalog?.sha256) { mutableStateOf(false) }
    val empty = stringResource(R.string.guard_router_vpn_router_choose_protocols)
    val summary = RouterInstallProtocol.entries.filter { it in selected }.joinToString(" · ") { it.label }
        .ifBlank { empty }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val menuWidth = maxWidth
        GuardRouterDropdownButton(summary, enabled && catalog != null) { expanded = !expanded }
        DropdownMenu(
            expanded = expanded && enabled && catalog != null,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(menuWidth).heightIn(max = 320.dp),
        ) {
            catalog?.availableProtocols?.forEach { protocol ->
                val checked = protocol in selected
                DropdownMenuItem(
                    text = { Text(protocol.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Checkbox(checked, onCheckedChange = null) },
                    onClick = { onSelect(if (checked) selected - protocol else selected + protocol) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
internal fun GuardRouterProfileDropdown(
    profiles: List<GuardRouterProfileSummary>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val none = stringResource(R.string.guard_router_vpn_router_no_profile)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val menuWidth = maxWidth
        GuardRouterDropdownButton(profiles.firstOrNull { it.id == selectedId }?.name ?: none, enabled) {
            expanded = !expanded
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(menuWidth).heightIn(max = 320.dp),
        ) {
            DropdownMenuItem(text = { Text(none) }, onClick = { expanded = false; onSelect(null) })
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; onSelect(profile.id) },
                )
            }
        }
    }
}

@Composable
internal fun GuardRouterCountryFlag(code: String, enabled: Boolean, onSelect: (String) -> Unit,
    autoSelected: Boolean = false, onAuto: (() -> Unit)? = null) {
    val locale = LocalConfiguration.current.locales[0]
    val countries = remember(locale) {
        Locale.getISOCountries().map { it to Locale("", it).getDisplayCountry(locale) }.sortedBy { it.second }
    }
    val normalizedCode = code.trim().uppercase(Locale.ROOT)
    val country = countries.firstOrNull { it.first == normalizedCode }
    val description = if (country == null) stringResource(R.string.guard_router_router_review_country_unknown_description)
        else stringResource(R.string.guard_router_router_review_country_description, country.second)
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
            RouterCountryAsset(normalizedCode, compact = true)
        }
        if (expanded && enabled) CliBottomSheet(onDismiss = { expanded = false },
            title = stringResource(R.string.guard_router_country_picker_title),
            icon = R.drawable.router_country_unknown, heightFraction = .5f,
            contentScrollEnabled = false, closeLabel = stringResource(R.string.cli_common_close_action)) {
            LazyColumn(Modifier.fillMaxSize().testTag("router-country-list")) {
                    if (onAuto != null) item {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.guard_router_router_review_country_auto), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { RouterCountryAsset(normalizedCode) },
                            trailingIcon = { Checkbox(autoSelected, onCheckedChange = null) },
                            onClick = { expanded = false; onAuto() },
                        )
                        HorizontalDivider(color = LocalCliColors.current.border)
                    }
                    items(countries, key = { it.first }) { (iso, name) ->
                        DropdownMenuItem(
                            text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { RouterCountryAsset(iso) },
                            onClick = { expanded = false; onSelect(iso) },
                        )
                        HorizontalDivider(color = LocalCliColors.current.border)
                    }
                }
        }
    }
}

@Composable
private fun RouterCountryAsset(country: String, compact: Boolean = false) {
    val code = country.trim().lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z]{2}")) }
    val context = LocalContext.current
    val loaded by produceState<Pair<String?, ImageBitmap?>>(null to null, code, context) {
        value = code to withContext(Dispatchers.IO) {
            code?.let { valid -> runCatching {
                context.assets.open("flags/$valid.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull() }
        }
    }
    val capHeight = if (compact) 16.dp else 22.dp
    Box(Modifier.size(width = capHeight * 1.5f, height = capHeight), contentAlignment = Alignment.Center) {
        loaded.second?.takeIf { loaded.first == code }?.let { Image(it, null, Modifier.fillMaxWidth()) }
            ?: Icon(painterResource(R.drawable.router_country_unknown), null, Modifier.size(capHeight), tint = LocalGuardRouterColors.current.muted)
    }
}

@Composable
private fun GuardRouterDropdownButton(label: String, enabled: Boolean, labelColor: Color? = null, onClick: () -> Unit) {
    CliButton(
        label = label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = enabled,
        color = labelColor ?: androidx.compose.ui.graphics.Color.Unspecified,
        icon = R.drawable.lin_arrow_down,
        filled = false,
    )
}

@Composable
internal fun GuardRouterDeviceRouteDropdown(route: String?, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val usesVpn = routerLanDeviceUsesVpn(route)
    val label = stringResource(if (usesVpn) R.string.guard_router_router_review_device_vpn
        else R.string.guard_router_router_review_direct)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val menuWidth = maxWidth
        GuardRouterDropdownButton(label, enabled, if (usesVpn) routerConnectColor() else null) { expanded = !expanded }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.width(menuWidth).heightIn(max = 240.dp)) {
            DropdownMenuItem(text = { Text(stringResource(R.string.guard_router_router_review_device_vpn), color = routerConnectColor()) },
                leadingIcon = { Icon(painterResource(R.drawable.lin_power), null, Modifier.size(20.dp), tint = routerConnectColor()) },
                onClick = { expanded = false; onSelect(true) })
            DropdownMenuItem(text = { Text(stringResource(R.string.guard_router_router_review_direct)) },
                leadingIcon = { Icon(painterResource(R.drawable.lin_globe), null, Modifier.size(20.dp)) },
                onClick = { expanded = false; onSelect(false) })
        }
    }
}
