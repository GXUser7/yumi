package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Following a chosen server to its new id.
 *
 * The names here are the real ones from a subscription of 140 servers, decoration and all, because
 * the decoration is most of the problem: what has to be recognised as the same server is a string
 * carrying a flag, two or three symbols, a country and sometimes an ordinal.
 */
class SelectionRematchTest {

    private var counter = 0

    private fun node(
        name: String,
        protocol: Protocol = Protocol.VLESS,
        id: String = "id-${counter++}",
    ) = ProxyNode(
        id = id,
        name = name,
        server = "$id.example.com",
        port = 443,
        settings = when (protocol) {
            Protocol.HYSTERIA2 -> ProxySettings.Hysteria2(password = "p")
            else -> ProxySettings.Vless(uuid = "u")
        },
        subscriptionId = "sub",
    )

    /**
     * The case two journals caught two days apart: the same name, a different address, and
     * therefore a different id. Before this, the entry was simply dropped.
     */
    @Test
    fun `a server that only moved is followed`() {
        val moved = node("🇩🇪 🎮 ⭐️ LTE Авто - Германия #2")

        val result = SelectionRematch.rematch(
            lost = mapOf("old-de-2" to node("🇩🇪 🎮 ⭐️ LTE Авто - Германия #2", id = "old-de-2")),
            candidates = listOf(node("🇱🇻 🎮 ⚡️ ⭐️ Латвия #3"), moved),
            taken = emptySet(),
        )

        assertEquals(mapOf("old-de-2" to moved.id), result)
    }

    /** Decoration comes and goes between refreshes; the server underneath does not. */
    @Test
    fun `a name that lost its symbols is still the same name`() {
        val plain = node("Латвия #3")

        val result = SelectionRematch.rematch(
            lost = mapOf("old-lv" to node("🇱🇻 🎮 ⚡️ ⭐️ Латвия #3", id = "old-lv")),
            candidates = listOf(node("Эстония #1"), plain),
            taken = emptySet(),
        )

        assertEquals(mapOf("old-lv" to plain.id), result)
    }

    /**
     * The third pass: nothing about the words matches any more, but the country, the ordinal and
     * the protocol do, and only one candidate has all three.
     */
    @Test
    fun `a renamed server is found by country, ordinal and protocol`() {
        val renamed = node("🇩🇪 Frankfurt Premium #2")

        val result = SelectionRematch.rematch(
            lost = mapOf("old-de" to node("🇩🇪 🎮 ⭐️ Германия #2", id = "old-de")),
            candidates = listOf(node("🇳🇱 Amsterdam #2"), renamed, node("🇩🇪 Frankfurt Premium #5")),
            taken = emptySet(),
        )

        assertEquals(mapOf("old-de" to renamed.id), result)
    }

    /**
     * The guard the whole thing rests on. Two candidates fit equally well, so neither is chosen —
     * putting somebody on a server they did not pick is worse than leaving them to pick one.
     */
    @Test
    fun `an ambiguous match is declined`() {
        val result = SelectionRematch.rematch(
            lost = mapOf("old-de" to node("🇩🇪 🎮 ⭐️ Германия #2", id = "old-de")),
            candidates = listOf(node("🇩🇪 Berlin #2"), node("🇩🇪 Frankfurt #2")),
            taken = emptySet(),
        )

        assertTrue("two equally good candidates must not be guessed between", result.isEmpty())
    }

    /** A failover list is a statement about acceptable exits. Another country is not one. */
    @Test
    fun `a server is never followed into another country`() {
        val result = SelectionRematch.rematch(
            lost = mapOf("old-de" to node("🇩🇪 🎮 ⭐️ Германия #2", id = "old-de")),
            candidates = listOf(node("🇳🇱 ⚡️ ⭐️ Нидерланды #2")),
            taken = emptySet(),
        )

        assertTrue(result.isEmpty())
    }

    /** Same country and ordinal, different transport: not the same server. */
    @Test
    fun `protocol is part of what makes a server itself`() {
        val result = SelectionRematch.rematch(
            lost = mapOf("old" to node("🇩🇪 🎮 ⭐️ Германия #2", id = "old")),
            candidates = listOf(node("🇩🇪 Frankfurt #2", protocol = Protocol.HYSTERIA2)),
            taken = emptySet(),
        )

        assertTrue(result.isEmpty())
    }

    /** A server already in one of the lists cannot be handed out a second time. */
    @Test
    fun `a candidate already chosen is not offered again`() {
        val kept = node("🇱🇻 🎮 ⚡️ ⭐️ Латвия #3")

        val result = SelectionRematch.rematch(
            lost = mapOf("old-lv" to node("🇱🇻 🎮 ⚡️ ⭐️ Латвия #3", id = "old-lv")),
            candidates = listOf(kept),
            taken = setOf(kept.id),
        )

        assertTrue(result.isEmpty())
    }

    /**
     * Passes run in order over everything, not all three per entry. Were it the other way round,
     * whichever entry came first could take by signature a server that belonged to another by
     * name.
     */
    @Test
    fun `an exact name wins over somebody else's signature`() {
        val exact = node("🇩🇪 Германия #2")
        val other = node("🇩🇪 Германия #7")

        val result = SelectionRematch.rematch(
            lost = linkedMapOf(
                "signature-only" to node("🇩🇪 Was Called Something Else #7", id = "signature-only"),
                "by-name" to node("🇩🇪 Германия #2", id = "by-name"),
            ),
            candidates = listOf(exact, other),
            taken = emptySet(),
        )

        assertEquals(exact.id, result["by-name"])
        assertEquals(other.id, result["signature-only"])
    }

    /** Nothing to follow, nothing to follow it to, or no memory of the name: no guesses. */
    @Test
    fun `nothing to work with produces nothing`() {
        assertTrue(SelectionRematch.rematch(emptyMap(), listOf(node("a")), emptySet()).isEmpty())
        assertTrue(SelectionRematch.rematch(mapOf("x" to node("a", id = "x")), emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun `decoration is stripped down to words and numbers`() {
        assertEquals("латвия 3", SelectionRematch.normalise("🇱🇻 🎮 ⚡️ ⭐️ Латвия #3"))
        assertEquals("латвия 3", SelectionRematch.normalise("  Латвия   #3  "))
        assertEquals("lte авто германия 2", SelectionRematch.normalise("🇩🇪 🎮 ⭐️ LTE Авто - Германия #2"))
        assertEquals("", SelectionRematch.normalise("🇱🇻 🎮 ⚡️ ⭐️"))
    }

    /** Digits glued to a word are part of the word, not a number in a series. */
    @Test
    fun `an ordinal is a number on its own`() {
        assertEquals(3, SelectionRematch.ordinalOf("🇱🇻 Латвия #3"))
        assertEquals(2, SelectionRematch.ordinalOf("LTE Авто - Германия #2"))
        assertEquals(4, SelectionRematch.ordinalOf("Латвия 4"))
        assertNull("Hysteria2 is a protocol", SelectionRematch.ordinalOf("Германия | Hysteria2"))
        assertNull(SelectionRematch.ordinalOf("Нидерланды | Torrent ✅"))
    }
}
