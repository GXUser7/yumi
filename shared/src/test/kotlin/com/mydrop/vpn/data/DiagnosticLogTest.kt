package com.mydrop.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The journal on disk, now that it is written in release builds too.
 *
 * It used to run only on the phone of whoever was debugging, where a mistake in it costs one
 * person an afternoon. It now runs on everybody's, and the two promises it makes there — that it
 * stays inside its cap, and that clearing it clears it — are the ones worth pinning down.
 */
class DiagnosticLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun journal(maxBytes: Long = 1024L) = DiagnosticLog(
        directory = folder.root,
        enabled = true,
        name = "test.log",
        maxBytes = maxBytes,
    )

    @Test
    fun `a line written comes back out`() {
        journal().write('I', "Yumi", "tunnel up")

        assertTrue(File(folder.root, "test.log").readText().contains("I/Yumi tunnel up"))
    }

    @Test
    fun `a disabled journal writes nothing at all`() {
        DiagnosticLog(folder.root, enabled = false, name = "test.log").write('I', "Yumi", "x")

        assertFalse(File(folder.root, "test.log").exists())
    }

    /**
     * The cap is the reason this is affordable on somebody else's phone, so it is the cap that is
     * tested rather than the rotation: two halves of a hundred bytes each may never become three.
     */
    @Test
    fun `the journal stays within two halves of its cap`() {
        val log = journal(maxBytes = 100L)
        repeat(500) { log.write('I', "Yumi", "line $it, long enough to push the file over its cap") }

        val halves = folder.root.listFiles().orEmpty().filter { it.name.startsWith("test.log") }
        assertEquals(listOf("test.log", "test.log.1"), halves.map { it.name }.sorted())
        // A rotation happens on the write *after* the cap is passed, so one line may sit above it.
        assertTrue(halves.sumOf { it.length() } < 100L * 2 + 200L)
    }

    /** Both halves, because the half somebody wanted gone is usually the older one. */
    @Test
    fun `clear empties the rotated half as well as the live one`() {
        val log = journal(maxBytes = 100L)
        repeat(50) { log.write('I', "Yumi", "line $it, long enough to push the file over its cap") }
        assertTrue(File(folder.root, "test.log.1").isFile)

        log.clear()

        assertFalse(File(folder.root, "test.log").exists())
        assertFalse(File(folder.root, "test.log.1").exists())
    }

    /** Oldest first, which is the order the save button hands the two halves over in. */
    @Test
    fun `read returns the rotated half before the live one`() {
        val log = journal(maxBytes = 60L)
        log.write('I', "Yumi", "the older line, long enough on its own to fill the whole cap")
        log.write('I', "Yumi", "the newer line")

        val text = log.read()
        assertTrue(text.indexOf("older") < text.indexOf("newer"))
    }
}
