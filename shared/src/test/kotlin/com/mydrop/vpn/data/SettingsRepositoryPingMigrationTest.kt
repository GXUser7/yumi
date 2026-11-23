package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.PingMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryPingMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun awaitPersisted(file: File, predicate: (AppSettings) -> Boolean): AppSettings {
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile) {
                val parsed = runCatching {
                    json.decodeFromString(AppSettings.serializer(), file.readText())
                }.getOrNull()
                if (parsed != null && predicate(parsed)) return parsed
            }
            Thread.sleep(20L)
        }
        error("Timed out waiting for persisted settings.json to match condition; content=${file.takeIf { it.isFile }?.readText()}")
    }

    @Test
    fun `legacy settings without migration flag upgrade pingMode to UrlTest once and persist`() {
        val dir = tempFolder.newFolder("legacy-ping")
        val settingsFile = File(dir, "settings.json")
        settingsFile.writeText("""{"pingMode":"Tls"}""")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = SettingsRepository(dir, scope)
            assertEquals(PingMode.UrlTest, repo.value.pingMode)
            assertTrue(repo.value.pingModeUrlTestDefaultApplied)

            val persisted = awaitPersisted(settingsFile) {
                it.pingMode == PingMode.UrlTest && it.pingModeUrlTestDefaultApplied
            }
            assertEquals(PingMode.UrlTest, persisted.pingMode)
            assertTrue(persisted.pingModeUrlTestDefaultApplied)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `manual pingMode override after migration survives repository reload`() {
        val dir = tempFolder.newFolder("manual-override")
        val settingsFile = File(dir, "settings.json")
        settingsFile.writeText("""{"pingMode":"Tls"}""")

        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = SettingsRepository(dir, firstScope)
            assertEquals(PingMode.UrlTest, repo.value.pingMode)
            repo.update { it.copy(pingMode = PingMode.Tcp) }
            val persisted = awaitPersisted(settingsFile) {
                it.pingMode == PingMode.Tcp && it.pingModeUrlTestDefaultApplied
            }
            assertEquals(PingMode.Tcp, persisted.pingMode)
        } finally {
            firstScope.cancel()
        }

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = SettingsRepository(dir, secondScope)
            assertEquals(PingMode.Tcp, reopened.value.pingMode)
            assertTrue(reopened.value.pingModeUrlTestDefaultApplied)
        } finally {
            secondScope.cancel()
        }
    }

    @Test
    fun `fresh install initializes with UrlTest and migration marker already set`() {
        val dir = tempFolder.newFolder("fresh-install")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = SettingsRepository(dir, scope)
            assertEquals(PingMode.UrlTest, repo.value.pingMode)
            assertTrue(repo.value.pingModeUrlTestDefaultApplied)
        } finally {
            scope.cancel()
        }
    }
}
