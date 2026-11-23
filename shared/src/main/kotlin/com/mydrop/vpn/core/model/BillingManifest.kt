package com.mydrop.vpn.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * External storefront manifest described by `docs/open-subscription-billing.md` (Schema v1).
 *
 * Monetary fields (`price`, `oldPrice`, `amount`) are integer minor units (`24900` = `249 RUB`)
 * so prices never suffer from floating-point rounding.
 */
@Serializable
data class BillingManifest(
    val version: Int = 1,
    val provider: BillingProvider,
    val account: BillingAccount? = null,
    @SerialName("routing_hint") val routingHint: String = "auto",
    @SerialName("promo_supported") val promoSupported: Boolean = false,
    @SerialName("fallback_web_url") val fallbackWebUrl: String? = null,
    @SerialName("payment_methods") val paymentMethods: List<BillingPaymentMethod> = emptyList(),
    val plans: List<BillingPlan> = emptyList(),
    val addons: List<BillingAddon> = emptyList(),
    val checkout: BillingCheckoutConfig? = null,
)

@Serializable
data class BillingProvider(
    val name: String,
    @SerialName("support_url") val supportUrl: String? = null,
)

@Serializable
data class BillingAccount(
    val status: String? = null,
    @SerialName("current_plan") val currentPlan: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("devices_used") val devicesUsed: Int? = null,
    @SerialName("device_limit") val deviceLimit: Int? = null,
)

@Serializable
data class BillingPaymentMethod(
    val id: String,
    val title: String,
    val type: String = "other",
)

@Serializable
data class BillingPlan(
    val id: String,
    val title: String,
    val badge: String? = null,
    val price: Long,
    @SerialName("old_price") val oldPrice: Long? = null,
    val currency: String,
    @SerialName("currency_decimals") val currencyDecimals: Int? = null,
    @SerialName("duration_days") val durationDays: Int? = null,
    @SerialName("traffic_gb") val trafficGb: Int? = null,
    val devices: Int? = null,
    val features: List<String> = emptyList(),
)

@Serializable
data class BillingAddon(
    val id: String,
    val title: String,
    val description: String? = null,
    val badge: String? = null,
    val price: Long,
    val currency: String,
    @SerialName("currency_decimals") val currencyDecimals: Int? = null,
)

@Serializable
data class BillingCheckoutConfig(
    val endpoint: String,
    val method: String = "POST",
)

@Serializable
data class BillingCheckoutRequest(
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("addon_id") val addonId: String? = null,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("promo_code") val promoCode: String? = null,
    @SerialName("return_url") val returnUrl: String? = null,
)

@Serializable
data class BillingCheckoutResponse(
    @SerialName("order_id") val orderId: String? = null,
    val amount: Long? = null,
    val currency: String? = null,
    @SerialName("payment_url") val paymentUrl: String,
    @SerialName("routing_hint") val routingHint: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class BillingErrorBody(
    val error: String? = null,
    val message: String? = null,
)

private val ZERO_DECIMAL_CURRENCIES = setOf("XTR", "JPY", "KRW")

/** Formats integer minor currency units into a compact label (e.g. `189000, "RUB"` -> `"1 890 ₽"`). */
fun formatBillingMoney(
    amountMinor: Long,
    currency: String,
    decimalsOverride: Int? = null,
): String {
    val cur = currency.trim().uppercase()
    val decimals = (decimalsOverride ?: if (cur in ZERO_DECIMAL_CURRENCIES) 0 else 2)
        .coerceIn(0, 6)

    var factor = 1L
    repeat(decimals) { factor *= 10L }

    val negative = amountMinor < 0
    val absMinor = kotlin.math.abs(amountMinor)
    val whole = absMinor / factor
    val frac = absMinor % factor

    val wholeGrouped = buildString {
        val raw = whole.toString()
        for (i in raw.indices) {
            if (i > 0 && (raw.length - i) % 3 == 0) append(' ')
            append(raw[i])
        }
    }
    val numeric = buildString {
        if (negative) append('-')
        append(wholeGrouped)
        if (decimals > 0 && frac != 0L) {
            append('.')
            append(frac.toString().padStart(decimals, '0').trimEnd('0'))
        }
    }

    return when (cur) {
        "RUB" -> "$numeric ₽"
        "USD" -> "$$numeric"
        "EUR" -> "€$numeric"
        "XTR" -> "$numeric Stars"
        else -> "$numeric $cur"
    }
}
