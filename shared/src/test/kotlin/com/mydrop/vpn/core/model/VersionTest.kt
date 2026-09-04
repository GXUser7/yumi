package com.mydrop.vpn.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the update check is allowed to conclude from two version strings.
 *
 * Both of the bugs guarded here would have shipped quietly. A debug build carries a suffix, so a
 * naive string comparison offers 0.3.5 as an update to somebody already running 0.3.5 — an
 * infinite, unfixable prompt. And a text comparison puts 0.10.0 before 0.9.0, which is wrong
 * exactly once, on the tenth release, long after anyone is watching this code.
 */
class VersionTest {

    @Test
    fun `a later release is newer`() {
        assertTrue(Version.isNewer("0.3.6", "0.3.5"))
        assertTrue(Version.isNewer("0.4.0", "0.3.9"))
        assertTrue(Version.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `the tag prefix is decoration`() {
        assertTrue(Version.isNewer("v0.3.6", "0.3.5"))
        assertFalse(Version.isNewer("v0.3.5", "0.3.5"))
    }

    /** The one that would have prompted forever on the phone this was developed on. */
    @Test
    fun `a debug build is not older than the release it was built from`() {
        assertFalse(Version.isNewer("0.3.5", "0.3.5-debug"))
        assertTrue(Version.isNewer("0.3.6", "0.3.5-debug"))
    }

    @Test
    fun `versions are compared as numbers, not as text`() {
        assertTrue(Version.isNewer("0.10.0", "0.9.0"))
        assertFalse(Version.isNewer("0.9.0", "0.10.0"))
    }

    /** A shorter version is not a smaller one: 1.0 and 1.0.0 are the same release. */
    @Test
    fun `missing components read as zero`() {
        assertFalse(Version.isNewer("1.0", "1.0.0"))
        assertFalse(Version.isNewer("1.0.0", "1.0"))
        assertTrue(Version.isNewer("1.0.1", "1.0"))
    }

    /** Garbage must not read as a new release; being unable to update beats updating to nothing. */
    @Test
    fun `unparseable versions do not claim to be newer`() {
        assertFalse(Version.isNewer("", "0.3.5"))
        assertFalse(Version.isNewer("latest", "0.3.5"))
    }
}
