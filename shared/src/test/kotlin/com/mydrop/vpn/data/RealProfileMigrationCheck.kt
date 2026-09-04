package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.NodeIdMigration
import com.mydrop.vpn.core.model.ProxyNode
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the id migration over a real profile taken off a phone, with the app's own serializer and
 * the app's own hashing — not a re-implementation of either.
 *
 * A migration that rewrites the servers, the selection and two curated lists is worth checking
 * against real data before it is allowed near a phone. Skipped when the file is not there, which is
 * everywhere except the machine that pulled it.
 */
class RealProfileMigrationCheck {

    private val profile = File(System.getProperty("yumi.realProfile") ?: "")

    @Test
    fun `the migration over a real profile moves every server and merges none`() {
        assumeTrue("no real profile supplied", profile.isFile)

        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val state = json.decodeFromString(ProfileState.serializer(), profile.readText())
        val mapping = NodeIdMigration.remap(state.nodes)

        println("servers stored:      ${state.nodes.size}")
        println("ids that move:       ${mapping.size}")
        println("distinct new ids:    ${mapping.values.toSet().size}")

        // The whole point: no two servers may land on one id, or the migration would delete one.
        assertEquals(
            "the migration must not merge two servers into one id",
            mapping.size,
            mapping.values.toSet().size,
        )

        val moved = state.nodes.map { node -> mapping[node.id]?.let { node.copy(id = it) } ?: node }
        assertEquals(
            "every server must survive the move",
            state.nodes.size,
            moved.distinctBy { it.id }.size,
        )

        // And the new ids have to be what the code would produce for those servers from scratch.
        moved.forEach { node ->
            assertEquals("id disagrees with contents for ${node.name}", ProxyNode.stableId(node), node.id)
        }

        val selected = state.selectedNodeId
        if (selected != null) {
            val followed = NodeIdMigration.follow(selected, mapping)
            assertEquals(
                "the chosen server must still be in the list after the move",
                1,
                moved.count { it.id == followed },
            )
            println("selection survives:  yes")
        }
    }
}
