package com.mydrop.vpn.ui.screens.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GratitudeBoardTest {
    @Test
    fun `entries list every contributor including Sha Li and aror`() {
        assertEquals(
            listOf("Sha Li", "EternalBlueInfinity", "Lesak777", "Obsida", "thetimick", "aror"),
            GRATITUDE_ENTRIES.map { it.handle },
        )
        assertEquals(listOf(12, 11, 9, 2), GRATITUDE_ENTRIES.mapNotNull { it.issueNumber })
    }

    @Test
    fun `entries have unique handles initials and valid metadata without external links for chat testers`() {
        val handles = GRATITUDE_ENTRIES.map { it.handle }
        val initials = GRATITUDE_ENTRIES.map { it.initial }
        val issueNumbers = GRATITUDE_ENTRIES.mapNotNull { it.issueNumber }

        assertEquals(handles.size, handles.toSet().size)
        assertEquals(initials.size, initials.toSet().size)
        assertEquals(issueNumbers.size, issueNumbers.toSet().size)

        for (entry in GRATITUDE_ENTRIES) {
            assertEquals(1, entry.initial.length)
            assertEquals(entry.initial.uppercase(), entry.initial)
            val issue = entry.issueNumber
            if (issue != null) {
                assertTrue(entry.isHandle)
                assertEquals("@${entry.handle}", entry.displayName)
                assertEquals("#$issue", entry.issueTag)
                assertEquals("$GITHUB_ISSUES_URL/$issue", entry.issueUrl)
                assertTrue(entry.issueUrl!!.startsWith("https://github.com/GXUser7/yumi/issues/"))
            } else {
                assertFalse(entry.isHandle)
                assertEquals(entry.handle, entry.displayName)
                assertNull(entry.issueTag)
                assertNull(entry.issueUrl)
                assertNotNull(entry.sourceTagRes)
            }
        }
    }

    @Test
    fun `segmented stack uses outer corners on ends and tight inner corners inside`() {
        val single = RoundedCornerShape(24.dp)
        val first = RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomStart = 6.dp,
            bottomEnd = 6.dp,
        )
        val middle = RoundedCornerShape(6.dp)
        val last = RoundedCornerShape(
            topStart = 6.dp,
            topEnd = 6.dp,
            bottomStart = 24.dp,
            bottomEnd = 24.dp,
        )

        assertEquals(single, segmentedStackShape(index = 0, count = 1))
        assertEquals(first, segmentedStackShape(index = 0, count = 4))
        assertEquals(middle, segmentedStackShape(index = 1, count = 4))
        assertEquals(middle, segmentedStackShape(index = 2, count = 4))
        assertEquals(last, segmentedStackShape(index = 3, count = 4))
    }
}
