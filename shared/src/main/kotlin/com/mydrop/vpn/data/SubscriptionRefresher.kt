package com.mydrop.vpn.data

import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.SubscriptionUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    private val strings: Strings,
) {

    private val _running = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Which subscriptions are being fetched right now, by id.
     *
     * Kept here rather than in the screen because the screen is not the only thing that starts a
     * refresh: the scheduler does it on its own clock and on opening the app, and a spinner that
     * only knew about button presses would leave the television looking idle while it was in fact
     * downloading three hundred servers.
     */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    /** A sentence describing what happened, suitable for a snackbar or the journal. */
    suspend fun refresh(subscription: Subscription): String {
        _running.update { it + subscription.id }
        return try {
            fetch(subscription)
        } finally {
            _running.update { it - subscription.id }
        }
    }

    private suspend fun fetch(subscription: Subscription): String =
        when (val result = service.fetch(subscription)) {
            is SubscriptionUpdate.Success -> {
                val (added, removed) = profiles.applySubscriptionUpdate(
                    subscriptionId = subscription.id,
                    fetchedNodes = result.nodes,
                    userInfo = result.subscription.userInfo,
                    remoteTitle = result.subscription.remoteTitle,
                    webPageUrl = result.subscription.webPageUrl,
                )
                val counted = strings.plural(R.plurals.servers, result.nodes.size)
                val detail = when {
                    added > 0 && removed > 0 ->
                        strings.get(R.string.subscription_added_removed, counted, added, removed)
                    added > 0 -> strings.get(R.string.subscription_added, counted, added)
                    removed > 0 -> strings.get(R.string.subscription_removed_only, counted, removed)
                    else -> counted
                }
                strings.get(R.string.log_subscription_message, subscription.name, detail)
            }

            is SubscriptionUpdate.Failure -> {
                profiles.recordSubscriptionError(subscription.id, result.message)
                logs.warn(R.string.log_subscription_message, subscription.name, result.message)
                strings.get(R.string.log_subscription_message, subscription.name, result.message)
            }
        }
}
