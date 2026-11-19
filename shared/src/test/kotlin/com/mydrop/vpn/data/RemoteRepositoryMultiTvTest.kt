package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.Palette
import com.mydrop.vpn.core.model.Visualizer
import com.mydrop.vpn.remote.RemoteBond
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRepositoryMultiTvTest {

    private fun bond(hostId: String, name: String, host: String) = RemoteBond(
        hostId = hostId,
        hostName = name,
        hostPublicKey = "pub-$hostId",
        peerId = "peer-$hostId",
        secret = "sec-$hostId",
        lastHost = host,
    )

    @Test
    fun `stores multiple bonds, renames alias, remembers palette metadata, and forgets one cleanly`() {
        val dir = Files.createTempDirectory("yumi-remote-repo-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = RemoteRepository(
                directory = dir,
                scope = scope,
                deviceName = { "Pixel 9" },
            )
            repo.remember(bond("tv-1", "Yumi TV · Haier", "192.168.1.20"))
            repo.remember(bond("tv-2", "Yumi TV · Mi Box", "192.168.1.35"))
            assertEquals(2, repo.value.bonds.size)

            repo.renameBond("tv-1", "  Гостиная  ")
            assertEquals("Гостиная", repo.value.bonds.first { it.hostId == "tv-1" }.displayName)
            assertEquals("Yumi TV · Mi Box", repo.value.bonds.first { it.hostId == "tv-2" }.displayName)

            repo.rememberMeta(
                hostId = "tv-2",
                hostName = "Xiaomi Mi Box S",
                palette = Palette.Mint,
                visualizer = Visualizer.Planet,
            )
            val tv2 = repo.value.bonds.first { it.hostId == "tv-2" }
            assertEquals("Xiaomi Mi Box S", tv2.hostName)
            assertEquals(Palette.Mint, tv2.lastPalette)
            assertEquals(Visualizer.Planet, tv2.lastVisualizer)

            // Re-pairing the same hostId preserves custom alias, palette, and visualizer.
            repo.remember(bond("tv-1", "Yumi TV · Haier", "192.168.1.21"))
            val repairedTv1 = repo.value.bonds.first { it.hostId == "tv-1" }
            assertEquals("Гостиная", repairedTv1.alias)
            assertEquals("192.168.1.21", repairedTv1.lastHost)

            repo.forgetBond("tv-1")
            assertEquals(1, repo.value.bonds.size)
            assertTrue(repo.value.bonds.none { it.hostId == "tv-1" })
            assertEquals("tv-2", repo.value.bonds.single().hostId)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
