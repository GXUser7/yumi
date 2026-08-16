package com.mydrop.vpn.core.model

import kotlinx.serialization.Serializable

/**
 * Traffic and expiry reported by the provider through the `subscription-userinfo` response
 * header, which is the de-facto standard Happ/v2rayN/Clash clients all read:
 *
 * `subscription-userinfo: upload=0; download=1234; total=107374182400; expire=1735689600`
 *
 * Every field is optional because providers implement arbitrary subsets of it.
 */
@Serializable
data class SubscriptionUserInfo(
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtEpochSeconds: Long? = null,
) {
    val usedBytes: Long?
        get() = when {
            uploadBytes == null && downloadBytes == null -> null
            else -> (uploadBytes ?: 0L) + (downloadBytes ?: 0L)
        }

    /** Fraction of the plan consumed, or null when the provider does not publish a quota. */
    val usedFraction: Float?
        get() {
            val used = usedBytes ?: return null
            val total = totalBytes?.takeIf { it > 0 } ?: return null
            return (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }

    val remainingBytes: Long?
        get() {
            val used = usedBytes ?: return null
            val total = totalBytes?.takeIf { it > 0 } ?: return null
            return (total - used).coerceAtLeast(0L)
        }
}

@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val nodeIds: List<String> = emptyList(),
    val userInfo: SubscriptionUserInfo? = null,
    val lastUpdatedEpochMillis: Long? = null,
    val lastError: String? = null,
    /** Auto-refresh cadence in hours; 0 disables background refresh for this subscription. */
    val updateIntervalHours: Int = 12,
    val enabled: Boolean = true,
    /** Provider's own name for the bundle, from the `profile-title` header when present. */
    val remoteTitle: String? = null,
    /** Provider support/renewal page, from the `profile-web-page-url` header. */
    val webPageUrl: String? = null,

    /**
     * Client to claim to be when fetching this one, overriding the app's own agent.
     *
     * Panels choose the format of their answer from the User-Agent, and not all of them know
     * about this app: the same link can return share links to one client, Clash YAML to another
     * and a refusal to a third. When a provider only serves a client we are not, saying so is the
     * difference between a working subscription and an unexplained empty list.
     */
    val userAgentOverride: String? = null,

    /**
     * Extra request headers, `name: value` per entry. Panels behind an authenticating gateway ask
     * for a token this way, and there is no other place to put one.
     */
    val headers: Map<String, String> = emptyMap(),
)

/** Outcome of a refresh, so the UI can distinguish "no change" from "failed" from "updated". */
sealed interface SubscriptionUpdate {
    data class Success(
        val subscription: Subscription,
        val nodes: List<ProxyNode>,
        val addedCount: Int,
        val removedCount: Int,
    ) : SubscriptionUpdate

    data class Failure(val subscriptionId: String, val message: String) : SubscriptionUpdate
}
