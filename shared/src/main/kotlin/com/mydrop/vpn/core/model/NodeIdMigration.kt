package com.mydrop.vpn.core.model

/**
 * Moves stored servers onto the ids their contents now imply.
 *
 * [ProxyNode.stableId] gained two fields — the transport and the TLS identity — because without
 * them a provider offering one endpoint over several transports produced several nodes with one
 * id, and all but the first were dropped on the way in. Twelve servers out of a hundred and
 * twenty-one, in a real subscription, silently.
 *
 * Widening the seed changes the id of every stored server, and an id is not an internal detail
 * here: it keys the selection, the latency map, the membership of each subscription, and the two
 * lists the user curated by hand. Left to sort itself out, the app would treat every server as
 * deleted and every list as emptied — recoverable, because [SelectionRematch] follows servers to
 * new ids by name, but recoverable is not the same as unaffected, and the user would watch a
 * notification tell them their mobile list had gone.
 *
 * So the change is applied once, deliberately, with the old ids still in hand. Nothing here needs
 * the network or the provider: every stored node already carries the fields the new id is computed
 * from, so old and new can both be derived from what is on disk.
 */
object NodeIdMigration {

    /**
     * Old id to new id, for the stored nodes whose id no longer matches their contents.
     *
     * Empty when there is nothing to do, which is the case on every launch after the first — the
     * ids are then already what the seed produces, so this costs one hash per node and no writes.
     */
    fun remap(nodes: List<ProxyNode>): Map<String, String> = buildMap {
        nodes.forEach { node ->
            val current = ProxyNode.stableId(node)
            if (current != node.id) put(node.id, current)
        }
    }

    /** The id [mapping] gives this one, or the id itself when it is not being moved. */
    fun follow(id: String, mapping: Map<String, String>): String = mapping[id] ?: id

    fun follow(ids: Set<String>, mapping: Map<String, String>): Set<String> =
        if (mapping.isEmpty()) ids else ids.mapTo(mutableSetOf()) { follow(it, mapping) }

    fun follow(ids: List<String>, mapping: Map<String, String>): List<String> =
        if (mapping.isEmpty()) ids else ids.map { follow(it, mapping) }
}
