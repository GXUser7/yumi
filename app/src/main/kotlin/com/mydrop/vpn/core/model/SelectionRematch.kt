package com.mydrop.vpn.core.model

/**
 * Follows a chosen server to its new id after the provider moved it.
 *
 * A node id is a hash of the endpoint and the credentials — see [ProxyNode.stableId] — and the
 * name is deliberately not part of it, so that renaming a server does not detach the latency
 * measured for it. The consequence runs the other way too: rotating an address hands the same
 * server back under a new id, and every list that names servers by id loses the entry.
 *
 * [StaleSelection] deletes those entries, which is correct when the server is really gone and
 * wrong when it has merely moved. Two journals a couple of days apart caught the case exactly:
 * «🇩🇪 🎮 ⭐️ LTE Авто - Германия #2» answered on one address on the thirty-first and another on
 * the second, under the same name. Under the old behaviour the user's failover and mobile lists
 * quietly shrank every time that happened, and refilling them was hand work.
 *
 * So the name is what carries identity across a rotation, and matching by it is string work rather
 * than anything cleverer. Three passes, each stricter about what it will accept than the last is
 * about what it will recognise:
 *
 *  1. the same name, exactly;
 *  2. the same name once decoration is stripped — flags, symbols, spacing, case;
 *  3. the same country, ordinal and protocol, when exactly one candidate has them.
 *
 * Uniqueness is the guard throughout. A pass that finds two equally good candidates declines to
 * choose between them, because putting the user on a server they did not pick is worse than
 * leaving them to pick one: a failover list is a statement about which exits are acceptable, and
 * a wrong guess silently moves their traffic to a country they refused.
 */
object SelectionRematch {

    /**
     * @param lost ids that vanished, mapped to the node each one was. The whole node rather than
     *   its name, because the third pass compares protocols and a name does not carry one — an
     *   earlier version read the protocol off the candidate instead, which made that half of the
     *   comparison always agree with itself.
     * @param candidates every node the profile now holds.
     * @param taken ids already spoken for — the surviving part of both lists, so a rematch cannot
     *   hand the same server to two entries or duplicate one that is already there.
     * @return the id each lost entry should become; ids with no confident answer are absent.
     */
    fun rematch(
        lost: Map<String, ProxyNode>,
        candidates: List<ProxyNode>,
        taken: Set<String>,
    ): Map<String, String> {
        if (lost.isEmpty() || candidates.isEmpty()) return emptyMap()

        val free = candidates.filterNot { it.id in taken }.toMutableList()
        if (free.isEmpty()) return emptyMap()

        val matched = LinkedHashMap<String, String>(lost.size)
        // Every pass runs over everything still unmatched before the next one starts, so a weaker
        // rule never claims a candidate that a stronger rule would have wanted. Doing it the other
        // way round — all three passes per entry — lets whichever entry happens to be first take a
        // server that belonged to another by exact name.
        for (pass in PASSES) {
            if (matched.size == lost.size) break
            for ((id, before) in lost) {
                if (id in matched) continue
                val hit = free.singleOrNull { pass(before, it) } ?: continue
                matched[id] = hit.id
                free.remove(hit)
            }
        }
        return matched
    }

    /**
     * The passes, weakest recognition last. Each answers "is this candidate the server that used
     * to be `before`" and nothing else; [rematch] decides what to do when more than one says yes.
     */
    private val PASSES: List<(ProxyNode, ProxyNode) -> Boolean> = listOf(
        { before, node -> node.name == before.name },
        { before, node ->
            val wanted = normalise(before.name)
            wanted.isNotEmpty() && normalise(node.name) == wanted
        },
        { before, node ->
            val wanted = signatureOf(before)
            wanted != null && signatureOf(node) == wanted
        },
    )

    /**
     * A name with everything that is not a letter, a digit or a gap thrown away.
     *
     * Providers decorate: `🇱🇻 🎮 ⚡️ ⭐️ Латвия #3` and `Латвия #3` are the same server announced
     * twice, and a flag added or a star dropped between refreshes should not read as a different
     * one. Emoji survive none of this — they are neither letters nor digits, and a surrogate half
     * is neither either, so they fall out without needing a table of ranges to keep current.
     */
    internal fun normalise(name: String): String = buildString(name.length) {
        var lastWasGap = true
        for (character in name) {
            when {
                character.isLetterOrDigit() -> {
                    append(character.lowercaseChar())
                    lastWasGap = false
                }
                // One gap for any run of spaces, punctuation or symbols, and none at the edges.
                !lastWasGap -> {
                    append(' ')
                    lastWasGap = true
                }
            }
        }
    }.trim()

    /**
     * Country, ordinal and protocol — the part of a name that survives being rewritten.
     *
     * Null when the country cannot be read, and that is a refusal rather than a gap: without it
     * this pass would happily agree that «Германия #2» and «Нидерланды #2» are the same server,
     * which is precisely the mistake that must never be made silently.
     */
    private fun signatureOf(node: ProxyNode): String? {
        val country = WorldMap.countryCodeOf(node.name) ?: return null
        return "$country|${ordinalOf(node.name) ?: "-"}|${node.settings.protocol.name}"
    }

    /**
     * The `3` in `Латвия #3`, or null.
     *
     * Read from a `#` when there is one and from the last standalone number otherwise, because
     * providers number both ways. Digits glued to a word — `Hysteria2`, `IPv6` — are not an
     * ordinal and are skipped, which is why this looks at whole runs rather than any digit.
     */
    internal fun ordinalOf(name: String): Int? {
        val hash = name.indexOf('#')
        if (hash >= 0) {
            val digits = name.drop(hash + 1).takeWhile(Char::isDigit)
            if (digits.isNotEmpty()) return digits.toIntOrNull()
        }
        var last: Int? = null
        var index = 0
        while (index < name.length) {
            if (!name[index].isDigit()) {
                index++
                continue
            }
            val start = index
            while (index < name.length && name[index].isDigit()) index++
            val precededByLetter = start > 0 && name[start - 1].isLetter()
            val followedByLetter = index < name.length && name[index].isLetter()
            if (!precededByLetter && !followedByLetter) {
                last = name.substring(start, index).toIntOrNull() ?: last
            }
        }
        return last
    }
}
