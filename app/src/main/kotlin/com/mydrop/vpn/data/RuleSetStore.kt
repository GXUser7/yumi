package com.mydrop.vpn.data

import android.content.Context
import com.mydrop.vpn.core.singbox.SingBoxConfigFactory
import java.io.File

/**
 * Puts the bundled geo rule-sets somewhere the core can read them.
 *
 * sing-box loads every declared rule-set while `startOrReloadService` is running. As remote sets
 * that meant the first connection could not start until DNS resolved and GitHub answered — over a
 * network the tunnel had not established yet, through an interface the platform may not have
 * reported to the core yet. Losing that race killed startup outright with "no available network
 * interface"; it was not a degraded mode, it was a failed tunnel.
 *
 * Reading them from app storage removes the dependency completely. The three compiled sets come to
 * under 70 KB, so bundling costs nothing worth measuring.
 */
class RuleSetStore(private val context: Context) {

    private val directory: File get() = File(context.filesDir, DIRECTORY)

    /**
     * Copies any missing rule-set out of assets and returns the directory holding them.
     *
     * Files are compared by size rather than timestamp: assets have no useful mtime, and a
     * truncated copy from a killed process is the failure worth catching. A set that cannot be
     * extracted is not fatal here — [missing] is what decides whether the config may reference it.
     */
    fun ensureExtracted(): File {
        val target = directory.apply { mkdirs() }
        SingBoxConfigFactory.bundledRuleSets.forEach { name ->
            val file = File(target, name)
            val expected = runCatching {
                context.assets.open("$DIRECTORY/$name").use { it.available().toLong() }
            }.getOrDefault(-1L)

            if (file.isFile && expected > 0 && file.length() == expected) return@forEach

            runCatching {
                context.assets.open("$DIRECTORY/$name").use { input ->
                    file.outputStream().use(input::copyTo)
                }
            }
        }
        return target
    }

    /** Rule-sets the config must not reference, because the core would fail to start on them. */
    fun missing(): List<String> = SingBoxConfigFactory.bundledRuleSets.filter { name ->
        !File(directory, name).isFile
    }

    private companion object {
        const val DIRECTORY = "rule-sets"
    }
}
