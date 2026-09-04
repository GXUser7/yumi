package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.identified
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a routine subscription refresh may and may not do to the server the user is on.
 *
 * Written from a field journal. A working tunnel on Belgium survived an auto-refresh by being
 * moved to France, which was dead — twenty seconds of no internet, from a background task the
 * user never asked for and could not see. The cause was the fallback: a node id is a hash of the
 * endpoint and the credentials, so a provider rotating an address, a port or a key hands the same
 * server back under a different id, the old one looks deleted, and the selection fell to whatever
 * happened to be first in the list.
 */
class SubscriptionRefreshSelectionTest {

    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun repository() = ProfileRepository(folder.newFolder(), scope)

    private fun node(name: String, server: String, uuid: String, subscription: String) = ProxyNode(
        id = "",
        name = name,
        server = server,
        port = 443,
        settings = ProxySettings.Vless(uuid),
        subscriptionId = subscription,
    ).identified()

    private val sub = "sub-1"

    private fun ProfileRepository.seed(vararg nodes: ProxyNode) {
        addSubscription(Subscription(id = sub, name = "мой", url = "https://example.com/s"))
        applySubscriptionUpdate(sub, nodes.toList(), null, null, null)
    }

    /**
     * The case that caused the outage. The provider rotates the credential, so every id moves, and
     * the server the user chose is still there under a new one.
     */
    @Test
    fun `a rotated credential does not move the user to another country`() {
        val repo = repository()
        val belgium = node("Бельгия", "be.example.com", "uuid-1", sub)
        val france = node("Франция", "fr.example.com", "uuid-2", sub)
        // France first in the list, exactly as the fallback would have picked it.
        repo.seed(france, belgium)
        repo.selectNode(belgium.id)

        val rotated = listOf(
            node("Франция", "fr.example.com", "uuid-9", sub),
            node("Бельгия", "be.example.com", "uuid-8", sub),
        )
        repo.applySubscriptionUpdate(sub, rotated, null, null, null)

        val selected = repo.state.value.nodes.first { it.id == repo.state.value.selectedNodeId }
        assertEquals("Бельгия", selected.name)
    }

    /** The ordinary refresh, where nothing moved: the selection must not so much as flicker. */
    @Test
    fun `an unchanged refresh leaves the selection alone`() {
        val repo = repository()
        val belgium = node("Бельгия", "be.example.com", "uuid-1", sub)
        val france = node("Франция", "fr.example.com", "uuid-2", sub)
        repo.seed(france, belgium)
        repo.selectNode(belgium.id)

        repo.applySubscriptionUpdate(sub, listOf(france, belgium), null, null, null)

        assertEquals(belgium.id, repo.state.value.selectedNodeId)
    }

    /**
     * A server that really is gone still has to release the selection, or the app would sit
     * pointing at nothing.
     */
    @Test
    fun `a server that disappears for good gives the selection up`() {
        val repo = repository()
        val belgium = node("Бельгия", "be.example.com", "uuid-1", sub)
        val france = node("Франция", "fr.example.com", "uuid-2", sub)
        repo.seed(belgium, france)
        repo.selectNode(belgium.id)

        repo.applySubscriptionUpdate(sub, listOf(france), null, null, null)

        assertEquals(france.id, repo.state.value.selectedNodeId)
    }

    /**
     * The name is a weaker key than the id, so it is scoped to one subscription — and the scoping
     * has to be the thing that decides, not a coincidence of ordering.
     *
     * Arranged so the two candidates differ. Another provider sells a server under the same label
     * and, being outside the subscription being refreshed, sits first in the combined list — which
     * is exactly what the fallback would take. The user's own server comes back under a new id, and
     * that is what must win.
     */
    @Test
    fun `the name match prefers our own subscription over an identical label elsewhere`() {
        val repo = repository()
        val theirs = node("Бельгия", "be.other.com", "uuid-9", "sub-2")
        repo.addSubscription(Subscription(id = "sub-2", name = "чужой", url = "https://other.example/s"))
        repo.applySubscriptionUpdate("sub-2", listOf(theirs), null, null, null)

        val mine = node("Бельгия", "be.example.com", "uuid-1", sub)
        repo.seed(mine)
        repo.selectNode(mine.id)

        val rotated = node("Бельгия", "be.example.com", "uuid-8", sub)
        repo.applySubscriptionUpdate(sub, listOf(rotated), null, null, null)

        assertEquals(
            "the choice must stay with the user's own provider",
            rotated.id,
            repo.state.value.selectedNodeId,
        )
    }
}
