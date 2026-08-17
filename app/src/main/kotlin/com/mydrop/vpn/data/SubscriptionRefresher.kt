package com.mydrop.vpn.data

import com.mydrop.vpn.core.format.pluralServers
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUpdate

/**
 * Fetching a subscription and writing the result down, in one place.
 *
 * Two callers need exactly this and differ only in what they do afterwards: the screen shows the
 * outcome and spins while it waits, the scheduler writes it to the journal. Leaving the fetch,
 * the merge and the error recording inline in the view model meant the scheduler would have had
 * to repeat all three, and a background refresh that merged servers slightly differently from a
 * manual one is the kind of difference nobody notices until the lists disagree.
 */
class SubscriptionRefresher(
    private val profiles: ProfileRepository,
    private val service: SubscriptionService,
    private val logs: LogRepository,
) {

    /** A sentence describing what happened, suitable for a snackbar or the journal. */
    suspend fun refresh(subscription: Subscription): String =
        when (val result = service.fetch(subscription)) {
            is SubscriptionUpdate.Success -> {
                val (added, removed) = profiles.applySubscriptionUpdate(
                    subscriptionId = subscription.id,
                    fetchedNodes = result.nodes,
                    userInfo = result.subscription.userInfo,
                    remoteTitle = result.subscription.remoteTitle,
                    webPageUrl = result.subscription.webPageUrl,
                )
                buildString {
                    append("«${subscription.name}»: ${pluralServers(result.nodes.size)}")
                    if (added > 0) append(", +$added новых")
                    if (removed > 0) append(", -$removed удалено")
                }
            }

            is SubscriptionUpdate.Failure -> {
                profiles.recordSubscriptionError(subscription.id, result.message)
                logs.warn("Подписка «${subscription.name}»: ${result.message}")
                "«${subscription.name}»: ${result.message}"
            }
        }
}
