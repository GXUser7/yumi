package com.mydrop.vpn.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The first tests for anything under `data/`. This class is the one that decides whether a user
 * keeps their servers, so it is the right place to start.
 */
class JsonStoreTest {

    @Serializable
    data class Box(val value: Int = 0, val note: String = "")

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(
        file: File,
        scope: CoroutineScope,
        onWriteFailure: (Throwable) -> Unit = {},
    ) = JsonStore(
        file = file,
        serializer = Box.serializer(),
        defaultValue = Box(),
        scope = scope,
        onWriteFailure = onWriteFailure,
    )

    private fun <T> withScope(block: (CoroutineScope) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            return block(scope)
        } finally {
            scope.cancel()
        }
    }

    /** Waits for the writer coroutine to catch up without pinning a particular timing. */
    private fun awaitFile(file: File, predicate: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && predicate(file.readText())) return
            Thread.sleep(20)
        }
        throw AssertionError(
            "file never reached the expected state; last content: " +
                if (file.exists()) file.readText() else "(missing)",
        )
    }

    @Test
    fun `an update reaches the disk`() = withScope { scope ->
        val file = File(folder.root, "box.json")
        val store = store(file, scope)

        store.update { it.copy(value = 7) }

        awaitFile(file) { it.contains("\"value\": 7") }
        assertEquals(7, store.value.value)
    }

    /**
     * The regression this file exists for.
     *
     * Every update used to launch its own coroutine carrying its own snapshot, so a burst reached
     * the disk in whatever order the IO pool got to them and an older state could land last. The
     * writer now reads the state at write time, which makes the final file the final state no
     * matter how the writes interleave.
     */
    @Test
    fun `the last state wins however the writes interleave`() = withScope { scope ->
        val file = File(folder.root, "burst.json")
        val store = store(file, scope)

        repeat(200) { i -> store.update { it.copy(value = i) } }

        awaitFile(file) { it.contains("\"value\": 199") }
        assertEquals(199, store.value.value)
    }

    @Test
    fun `a corrupt file is set aside rather than taking the app down with it`() =
        withScope { scope ->
            val file = File(folder.root, "corrupt.json")
            file.writeText("{ this is not json")

            val store = store(file, scope)

            assertEquals(Box(), store.value)
            assertTrue(File(folder.root, "corrupt.json.corrupt").exists())
            assertFalse(file.exists())
        }

    @Test
    fun `a stored value is read back on the next start`() = withScope { scope ->
        val file = File(folder.root, "persist.json")
        store(file, scope).update { it.copy(value = 42, note = "kept") }
        awaitFile(file) { it.contains("kept") }

        val reopened = store(file, scope)

        assertEquals(42, reopened.value.value)
        assertEquals("kept", reopened.value.note)
    }

    /**
     * A write that cannot happen used to be swallowed whole; now somebody hears about it.
     *
     * The unwritable path is a file standing where the *parent directory* should be, so `mkdirs`
     * cannot create it and the temp file cannot be opened. Putting a directory at the target path
     * instead does not work: the constructor reads first, fails, and files the directory away as
     * `.corrupt` — after which the path is free and the write succeeds.
     */
    @Test
    fun `a failing write is reported`() = withScope { scope ->
        val blockingFile = File(folder.root, "not-a-directory").apply { writeText("in the way") }
        val path = File(blockingFile, "box.json")
        val failure = AtomicReference<Throwable?>(null)

        store(path, scope) { failure.set(it) }.update { it.copy(value = 1) }

        val deadline = System.currentTimeMillis() + 5_000
        while (failure.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertNotNull("the write failure never surfaced", failure.get())
    }
}
