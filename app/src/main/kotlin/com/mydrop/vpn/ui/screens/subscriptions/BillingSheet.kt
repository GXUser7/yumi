package com.mydrop.vpn.ui.screens.subscriptions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.CurrencyBitcoin
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.format.daysUntil
import com.mydrop.vpn.core.model.BillingAddon
import com.mydrop.vpn.core.model.BillingManifest
import com.mydrop.vpn.core.model.BillingPaymentMethod
import com.mydrop.vpn.core.model.BillingPlan
import com.mydrop.vpn.core.model.RoutingMode
import com.mydrop.vpn.core.model.formatBillingMoney
import com.mydrop.vpn.core.net.billingDisplayHost
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.ui.BillingSheetUiState
import com.mydrop.vpn.ui.theme.MonoStyle

private enum class BillingTab { Plans, Addons }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingSheet(
    state: BillingSheetUiState,
    vpnActive: Boolean,
    routingMode: RoutingMode,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenFallbackUrl: (String, String?) -> Unit,
    onCheckout: (planId: String?, addonId: String?, paymentMethod: String, promoCode: String?) -> Unit,
    onClearError: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sub = state.subscription
    val manifest = state.manifest

    var tab by rememberSaveable(sub.id) { mutableStateOf(BillingTab.Plans) }
    var selectedPlanId by rememberSaveable(sub.id) { mutableStateOf<String?>(null) }
    var selectedAddonId by rememberSaveable(sub.id) { mutableStateOf<String?>(null) }
    var selectedPayId by rememberSaveable(sub.id) { mutableStateOf<String?>(null) }
    var promoExpanded by rememberSaveable(sub.id) { mutableStateOf(false) }
    var promoDraft by rememberSaveable(sub.id) { mutableStateOf("") }
    var appliedPromo by rememberSaveable(sub.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(manifest) {
        if (manifest == null) return@LaunchedEffect
        if (selectedPlanId == null || manifest.plans.none { it.id == selectedPlanId }) {
            selectedPlanId = manifest.plans.firstOrNull { !it.badge.isNullOrBlank() }?.id
                ?: manifest.plans.firstOrNull()?.id
        }
        if (selectedAddonId == null || manifest.addons.none { it.id == selectedAddonId }) {
            selectedAddonId = manifest.addons.firstOrNull()?.id
        }
        if (selectedPayId == null || manifest.paymentMethods.none { it.id == selectedPayId }) {
            selectedPayId = manifest.paymentMethods.firstOrNull()?.id ?: "default"
        }
        if (manifest.plans.isEmpty() && manifest.addons.isNotEmpty()) {
            tab = BillingTab.Addons
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            BillingSheetHeader(
                title = manifest?.provider?.name ?: (sub.remoteTitle ?: sub.name),
                subtitle = buildHeaderSubtitle(manifest, sub),
                onClose = onDismiss,
            )

            when {
                state.loading -> BillingLoadingBody()
                state.loadError != null -> BillingErrorBody(
                    message = state.loadError,
                    fallbackUrl = sub.paymentUrl ?: sub.webPageUrl,
                    onRetry = onRetry,
                    onOpenPortal = { url -> onOpenFallbackUrl(url, sub.id) },
                )
                manifest != null -> {
                    val activeTab = if (manifest.addons.isEmpty()) BillingTab.Plans else tab
                    val activePlan = manifest.plans.firstOrNull { it.id == selectedPlanId }
                    val activeAddon = manifest.addons.firstOrNull { it.id == selectedAddonId }

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (manifest.plans.isNotEmpty() && manifest.addons.isNotEmpty()) {
                            BillingTabSwitch(
                                current = activeTab,
                                plansCount = manifest.plans.size,
                                addonsCount = manifest.addons.size,
                                onSelect = {
                                    tab = it
                                    onClearError()
                                },
                            )
                        }

                        if (activeTab == BillingTab.Plans) {
                            if (manifest.plans.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.billing_sheet_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    manifest.plans.forEachIndexed { idx, plan ->
                                        PlanOptionTile(
                                            plan = plan,
                                            selected = plan.id == activePlan?.id,
                                            shape = segmentedTileShape(
                                                index = idx,
                                                count = manifest.plans.size,
                                                selected = plan.id == activePlan?.id,
                                            ),
                                            onClick = {
                                                selectedPlanId = plan.id
                                                onClearError()
                                            },
                                        )
                                    }
                                }
                            }

                            if (manifest.promoSupported && manifest.plans.isNotEmpty()) {
                                PromoCodeCard(
                                    expanded = promoExpanded,
                                    draft = promoDraft,
                                    applied = appliedPromo,
                                    onToggle = { promoExpanded = !promoExpanded },
                                    onDraftChange = {
                                        promoDraft = it
                                        onClearError()
                                    },
                                    onApply = {
                                        val clean = promoDraft.trim()
                                        appliedPromo = clean.takeIf { it.isNotEmpty() }
                                        onClearError()
                                    },
                                    onClear = {
                                        promoDraft = ""
                                        appliedPromo = null
                                        onClearError()
                                    },
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                manifest.addons.forEachIndexed { idx, addon ->
                                    AddonOptionTile(
                                        addon = addon,
                                        selected = addon.id == activeAddon?.id,
                                        shape = segmentedTileShape(
                                            index = idx,
                                            count = manifest.addons.size,
                                            selected = addon.id == activeAddon?.id,
                                        ),
                                        onClick = {
                                            selectedAddonId = addon.id
                                            onClearError()
                                        },
                                    )
                                }
                            }
                        }

                        if (vpnActive && routingMode == RoutingMode.Global &&
                            manifest.routingHint.equals("direct", ignoreCase = true)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.billing_sheet_direct_global_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }

                        AnimatedVisibility(visible = state.checkoutError != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(vertical = 2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = state.checkoutError.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                    }

                    BillingSheetFooter(
                        manifest = manifest,
                        selectedPlan = if (activeTab == BillingTab.Plans) activePlan else null,
                        selectedAddon = if (activeTab == BillingTab.Addons) activeAddon else null,
                        selectedPayId = selectedPayId ?: "default",
                        submitting = state.submitting,
                        fallbackUrl = manifest.fallbackWebUrl ?: sub.paymentUrl ?: sub.webPageUrl,
                        onSelectPayMethod = {
                            selectedPayId = it
                            onClearError()
                        },
                        onPay = {
                            onCheckout(
                                if (activeTab == BillingTab.Plans) activePlan?.id else null,
                                if (activeTab == BillingTab.Addons) activeAddon?.id else null,
                                selectedPayId ?: "default",
                                if (activeTab == BillingTab.Plans) appliedPromo else null,
                            )
                        },
                        onOpenFallback = { url -> onOpenFallbackUrl(url, sub.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BillingSheetHeader(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = title.firstOrNull()?.uppercase() ?: "Y",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.billing_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.action_close),
            )
        }
    }
}

@Composable
private fun BillingLoadingBody() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Text(
            text = stringResource(R.string.billing_sheet_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BillingErrorBody(
    message: String,
    fallbackUrl: String?,
    onRetry: () -> Unit,
    onOpenPortal: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.billing_sheet_retry))
            }
            if (fallbackUrl != null) {
                FilledTonalButton(
                    onClick = { onOpenPortal(fallbackUrl) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.billing_sheet_open_web))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BillingTabSwitch(
    current: BillingTab,
    plansCount: Int,
    addonsCount: Int,
    onSelect: (BillingTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BillingConnectedSegment(
            label = stringResource(R.string.billing_sheet_tab_plans, plansCount),
            selected = current == BillingTab.Plans,
            isStart = true,
            onClick = { onSelect(BillingTab.Plans) },
            modifier = Modifier.weight(1f),
        )
        BillingConnectedSegment(
            label = stringResource(R.string.billing_sheet_tab_addons, addonsCount),
            selected = current == BillingTab.Addons,
            isStart = false,
            onClick = { onSelect(BillingTab.Addons) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BillingConnectedSegment(
    label: String,
    selected: Boolean,
    isStart: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outer by animateDpAsState(if (selected) 24.dp else 18.dp, label = "tabOuter")
    val inner by animateDpAsState(if (selected) 24.dp else 8.dp, label = "tabInner")
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "tabBg",
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        label = "tabFg",
    )
    val shape = if (isStart) {
        RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner)
    } else {
        RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer)
    }

    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = shape,
        color = bg,
        contentColor = fg,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlanOptionTile(
    plan: BillingPlan,
    selected: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "planBg",
    )
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = bg,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelectionRadioCircle(selected = selected)

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = plan.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    plan.badge?.takeIf { it.isNotBlank() }?.let { badge ->
                        Surface(
                            shape = CircleShape,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.tertiaryContainer
                            },
                            contentColor = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            },
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                val summary = planSubtitle(plan)
                if (summary.isNotEmpty()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = subColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatBillingMoney(plan.price, plan.currency, plan.currencyDecimals),
                    style = MaterialTheme.typography.titleMedium.merge(MonoStyle),
                    fontWeight = FontWeight.Bold,
                )
                plan.oldPrice?.takeIf { it > plan.price }?.let { old ->
                    Text(
                        text = formatBillingMoney(old, plan.currency, plan.currencyDecimals),
                        style = MaterialTheme.typography.labelSmall.merge(MonoStyle),
                        color = subColor,
                        textDecoration = TextDecoration.LineThrough,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonOptionTile(
    addon: BillingAddon,
    selected: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "addonBg",
    )
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = bg,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelectionRadioCircle(selected = selected)

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = addon.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    addon.badge?.takeIf { it.isNotBlank() }?.let { badge ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                addon.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = subColor,
                        maxLines = 2,
                    )
                }
            }

            Text(
                text = formatBillingMoney(addon.price, addon.currency, addon.currencyDecimals),
                style = MaterialTheme.typography.titleMedium.merge(MonoStyle),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SelectionRadioCircle(selected: Boolean) {
    Surface(
        modifier = Modifier.size(22.dp),
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        if (selected) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun PromoCodeCard(
    expanded: Boolean,
    draft: String,
    applied: String?,
    onToggle: () -> Unit,
    onDraftChange: (String) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.LocalOffer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (applied != null) {
                        stringResource(R.string.billing_sheet_promo_applied, applied)
                    } else {
                        stringResource(R.string.billing_sheet_promo_toggle)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (applied != null) {
                    TextButton(
                        onClick = onClear,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.billing_sheet_promo_clear))
                    }
                } else {
                    IconButton(onClick = onToggle, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded && applied == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        label = { Text(stringResource(R.string.billing_sheet_promo_label)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = onApply,
                        enabled = draft.isNotBlank(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Text(stringResource(R.string.billing_sheet_promo_apply))
                    }
                }
            }
        }
    }
}

@Composable
private fun BillingSheetFooter(
    manifest: BillingManifest,
    selectedPlan: BillingPlan?,
    selectedAddon: BillingAddon?,
    selectedPayId: String,
    submitting: Boolean,
    fallbackUrl: String?,
    onSelectPayMethod: (String) -> Unit,
    onPay: () -> Unit,
    onOpenFallback: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (manifest.paymentMethods.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    manifest.paymentMethods.forEach { method ->
                        val active = method.id == selectedPayId
                        Surface(
                            onClick = { onSelectPayMethod(method.id) },
                            shape = if (active) CircleShape else RoundedCornerShape(10.dp),
                            color = if (active) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (active) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = paymentMethodIcon(method.type),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = method.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            val priceLabel = when {
                selectedPlan != null ->
                    formatBillingMoney(selectedPlan.price, selectedPlan.currency, selectedPlan.currencyDecimals)
                selectedAddon != null ->
                    formatBillingMoney(selectedAddon.price, selectedAddon.currency, selectedAddon.currencyDecimals)
                else -> null
            }
            val endpoint = manifest.checkout?.endpoint

            if (endpoint != null && priceLabel != null) {
                Button(
                    onClick = onPay,
                    enabled = !submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        val methodType = manifest.paymentMethods
                            .firstOrNull { it.id == selectedPayId }?.type ?: "other"
                        Icon(
                            imageVector = paymentMethodIcon(methodType),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.billing_sheet_pay_cta, priceLabel))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = Color(0xFF7EE0A8),
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(
                            R.string.billing_sheet_domain_hint,
                            billingDisplayHost(endpoint),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (fallbackUrl != null) {
                Button(
                    onClick = { onOpenFallback(fallbackUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.billing_sheet_pay_free_cta))
                }
            }
        }
    }
}

@Composable
private fun buildHeaderSubtitle(
    manifest: BillingManifest?,
    sub: com.mydrop.vpn.core.model.Subscription,
): String {
    val plan = manifest?.account?.currentPlan
    val days = daysUntil(sub.userInfo?.expiresAtEpochSeconds)
    val daysText = when {
        days == null -> null
        days > 0 -> stringResource(R.string.subscriptions_days_left, days)
        else -> stringResource(R.string.subscriptions_expired)
    }
    return listOfNotNull(plan, daysText)
        .joinToString(" · ")
        .ifEmpty { sub.remoteTitle ?: sub.name }
}

@Composable
private fun planSubtitle(plan: BillingPlan): String {
    val parts = buildList {
        plan.trafficGb?.let { gb ->
            add(
                if (gb <= 0) stringResource(R.string.billing_traffic_unlimited)
                else stringResource(R.string.billing_traffic_gb, gb),
            )
        }
        plan.devices?.takeIf { it > 0 }?.let { count ->
            add(stringResource(R.string.billing_devices_count, count))
        }
        if (isEmpty() && plan.features.isNotEmpty()) {
            add(plan.features.take(2).joinToString(" · "))
        }
    }
    return parts.joinToString(" · ")
}

private fun paymentMethodIcon(type: String): ImageVector = when (type.lowercase()) {
    "sbp" -> Icons.Rounded.Bolt
    "card" -> Icons.Rounded.CreditCard
    "crypto" -> Icons.Rounded.CurrencyBitcoin
    "tg_stars", "stars" -> Icons.Rounded.Star
    "wallet" -> Icons.Rounded.AccountBalanceWallet
    else -> Icons.Rounded.Devices
}

private fun segmentedTileShape(index: Int, count: Int, selected: Boolean): Shape {
    if (selected || count <= 1) return RoundedCornerShape(22.dp)
    return when (index) {
        0 -> RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
        count - 1 -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 28.dp, bottomEnd = 28.dp)
        else -> RoundedCornerShape(8.dp)
    }
}
