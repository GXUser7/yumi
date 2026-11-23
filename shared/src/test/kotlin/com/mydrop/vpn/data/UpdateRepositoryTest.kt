package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.Release
import com.mydrop.vpn.core.model.UpdateState
import com.mydrop.vpn.shared.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class TestStrings : Strings() {
        override fun get(id: Int, vararg formatArgs: Any): String = when (id) {
            R.string.log_update_available -> "LOG_AVAILABLE:${formatArgs.firstOrNull()}"
            R.string.log_update_downloaded -> "LOG_DOWNLOADED:${formatArgs.firstOrNull()}"
            R.string.log_update_failed -> "LOG_FAILED:${formatArgs.firstOrNull()}"
            R.string.log_update_none -> "LOG_NONE:${formatArgs.firstOrNull()}"
            R.string.error_update_unreadable -> "ERR_UNREADABLE"
            else -> "res#$id(${formatArgs.joinToString(",")})"
        }

        override fun plural(id: Int, count: Int): String = "$count"
    }

    private class FakeUpdateClient : UpdateClient {
        val latestAbis = mutableListOf<String>()
        val downloaded = mutableListOf<Pair<Release, File>>()
        var latestBehavior: (String) -> Release = { error("latestBehavior not configured") }
        var downloadBehavior: (Release, File, (Long, Long) -> Unit) -> File = { release, target, onProgress ->
            target.parentFile?.mkdirs()
            target.writeText("apk:${release.version}")
            onProgress(512L, release.sizeBytes)
            target
        }

        override fun latest(abi: String): Release {
            latestAbis += abi
            return latestBehavior(abi)
        }

        override fun download(release: Release, target: File, onProgress: (Long, Long) -> Unit): File {
            downloaded += release to target
            return downloadBehavior(release, target, onProgress)
        }
    }

    private data class Harness(
        val dir: File,
        val client: FakeUpdateClient,
        val settings: SettingsRepository,
        val logs: LogRepository,
        val alerts: MutableList<String>,
        val updates: UpdateRepository,
    )

    private fun withHarness(
        currentVersion: String = "2.3.1",
        block: suspend TestScope.(Harness) -> Unit,
    ) = runTest {
        val dir = folder.newFolder()
        val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repoScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val strings = TestStrings()
            val client = FakeUpdateClient()
            val settings = SettingsRepository(dir, settingsScope)
            val logs = LogRepository(strings)
            val alerts = mutableListOf<String>()
            val updates = UpdateRepository(
                filesDir = dir,
                service = client,
                settings = settings,
                logs = logs,
                strings = strings,
                currentVersion = currentVersion,
                scope = repoScope,
                onUpdateAlert = { alerts += it },
                abiProvider = { "arm64-v8a" },
                ioDispatcher = dispatcher,
            )
            block(Harness(dir, client, settings, logs, alerts, updates))
        } finally {
            repoScope.cancel()
            settingsScope.cancel()
        }
    }

    private fun release(
        version: String,
        notes: String = "",
        sizeBytes: Long = 10_000L,
    ) = Release(
        version = version,
        notes = notes,
        apkUrl = "https://example.invalid/yumi-$version-arm64-v8a.apk",
        sizeBytes = sizeBytes,
    )

    @Test
    fun `download silently switches to newer release when published after check`() = withHarness { h ->
        val r240 = release("2.4.0", notes = "first", sizeBytes = 10_000L)
        val r241 = release("2.4.1", notes = "hotfix", sizeBytes = 12_345L)
        h.client.latestBehavior = { r240 }

        h.updates.check(manual = true)
        advanceUntilIdle()
        assertEquals(UpdateState.Available(r240), h.updates.state.value)

        h.settings.update { it.copy(lastUpdateCheckEpochMillis = 123L) }
        val stale = File(File(h.dir, "updates"), "yumi-2.3.9-arm64-v8a.apk").apply {
            parentFile?.mkdirs()
            writeText("stale")
        }

        val states = mutableListOf<UpdateState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.updates.state.toList(states)
        }
        try {
            h.client.latestBehavior = { r241 }
            h.updates.download()
            advanceUntilIdle()
        } finally {
            collector.cancel()
        }

        assertFalse(states.any { it is UpdateState.Checking })
        assertTrue(states.any { (it as? UpdateState.Downloading)?.release == r240 })
        assertTrue(states.any { (it as? UpdateState.Downloading)?.release == r241 })
        assertTrue(h.alerts.isEmpty())
        assertEquals(listOf("arm64-v8a", "arm64-v8a"), h.client.latestAbis)
        assertEquals(1, h.client.downloaded.size)
        assertEquals(r241, h.client.downloaded.single().first)
        val target = h.client.downloaded.single().second
        assertEquals("yumi-2.4.1-arm64-v8a.apk", target.name)
        assertFalse(stale.exists())
        assertEquals(UpdateState.Ready(r241, target), h.updates.state.value)
        assertTrue(h.settings.value.lastUpdateCheckEpochMillis > 123L)

        val messages = h.logs.entries.value.map { it.message }
        assertTrue(messages.contains("LOG_AVAILABLE:2.4.1"))
        assertTrue(messages.contains("LOG_DOWNLOADED:2.4.1"))
    }

    @Test
    fun `download keeps known release when silent check returns same version`() = withHarness { h ->
        val known240 = release("2.4.0", notes = "changelog", sizeBytes = 12_000_000L)
        val redirect240 = release("2.4.0", notes = "", sizeBytes = 0L)
        h.client.latestBehavior = { known240 }

        h.updates.check(manual = true)
        advanceUntilIdle()

        h.client.latestBehavior = { redirect240 }
        h.updates.download()
        advanceUntilIdle()

        assertEquals(1, h.client.downloaded.size)
        assertEquals(known240, h.client.downloaded.single().first)
        val target = h.client.downloaded.single().second
        assertEquals(UpdateState.Ready(known240, target), h.updates.state.value)

        val availLogs = h.logs.entries.value.count { it.message == "LOG_AVAILABLE:2.4.0" }
        assertEquals(1, availLogs)
    }

    @Test
    fun `download falls back to known release when silent check fails`() = withHarness { h ->
        val known240 = release("2.4.0", sizeBytes = 9_000L)
        h.client.latestBehavior = { known240 }

        h.updates.check(manual = true)
        advanceUntilIdle()

        h.client.latestBehavior = { throw IllegalStateException("API rate limit exceeded") }
        h.updates.download()
        advanceUntilIdle()

        assertEquals(1, h.client.downloaded.size)
        assertEquals(known240, h.client.downloaded.single().first)
        val target = h.client.downloaded.single().second
        assertEquals(UpdateState.Ready(known240, target), h.updates.state.value)
        assertFalse(h.logs.entries.value.any { it.message.startsWith("LOG_FAILED:") })
    }

    @Test
    fun `download does not downgrade when silent check returns older version`() = withHarness { h ->
        val known240 = release("2.4.0", sizeBytes = 9_000L)
        val older239 = release("2.3.9", sizeBytes = 8_000L)
        h.client.latestBehavior = { known240 }

        h.updates.check(manual = true)
        advanceUntilIdle()

        h.client.latestBehavior = { older239 }
        h.updates.download()
        advanceUntilIdle()

        assertEquals(1, h.client.downloaded.size)
        assertEquals(known240, h.client.downloaded.single().first)
        val target = h.client.downloaded.single().second
        assertEquals(UpdateState.Ready(known240, target), h.updates.state.value)
    }

    @Test
    fun `download reports failure if fetching the newer release fails`() = withHarness { h ->
        val r240 = release("2.4.0")
        val r241 = release("2.4.1")
        h.client.latestBehavior = { r240 }

        h.updates.check(manual = true)
        advanceUntilIdle()

        h.client.latestBehavior = { r241 }
        h.client.downloadBehavior = { _, _, _ -> throw IllegalStateException("HTTP 502") }
        h.updates.download()
        advanceUntilIdle()

        assertEquals(r241, h.client.downloaded.single().first)
        assertEquals(UpdateState.Failed("HTTP 502"), h.updates.state.value)
        assertTrue(h.logs.entries.value.any { it.message == "LOG_FAILED:HTTP 502" })
    }
}
