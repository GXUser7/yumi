package com.mydrop.vpn.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a server is, and what the world looks like there.
 *
 * The mask itself is a binary asset and cannot be read from a JVM test, so what is checked here is
 * everything that decides *how* it is read — the bit arithmetic and the seam at the
 * antimeridian — against a mask built by hand, where the answers are known by construction.
 */
class WorldMapTest {

    /** A mask with exactly one land cell, at [column] and [row] of the 180×90 grid. */
    private fun maskWith(column: Int, row: Int) =
        ByteArray(WorldMap.MASK_BYTES).also {
            val index = row * WorldMap.MASK_COLUMNS + column
            it[index / 8] = (0x80 ushr (index % 8)).toByte()
        }

    @Test
    fun `a cell is found where it was put`() {
        // Column 90 starts at longitude 0, row 45 starts at latitude 0: the point where the
        // equator meets the prime meridian, which is the one corner of this grid worth naming.
        val mask = maskWith(column = 90, row = 45)

        assertTrue(WorldMap.isLand(mask, 0.0, 0.0))
        assertTrue("the cell is two degrees wide", WorldMap.isLand(mask, -1.5, 1.5))
        assertFalse("and only two", WorldMap.isLand(mask, 0.0, 2.5))
        assertFalse(WorldMap.isLand(mask, 2.5, 0.0))
    }

    /**
     * The antimeridian is a seam in the array and not in the world. Clamping there instead of
     * wrapping would smear the last column across the Pacific — and the strip scrolls across it
     * every few seconds, so this is read constantly rather than at some edge case.
     */
    @Test
    fun `longitude wraps at the antimeridian`() {
        val mask = maskWith(column = 0, row = 45)

        assertTrue(WorldMap.isLand(mask, 0.0, -180.0))
        assertTrue("+180 is the same meridian as -180", WorldMap.isLand(mask, 0.0, 180.0))
        assertTrue("and so is a full turn past it", WorldMap.isLand(mask, 0.0, -540.0))
    }

    /** A mask that failed to load must read as sea, not crash the figure that is drawing it. */
    @Test
    fun `an empty mask is all sea`() {
        assertFalse(WorldMap.isLand(ByteArray(0), 55.0, 37.0))
        assertFalse(WorldMap.isLand(ByteArray(10), 55.0, 37.0))
    }

    /**
     * The trick the whole offline path rests on: a flag emoji is two Regional Indicator Symbols,
     * which are the letters A–Z shifted up to U+1F1E6. So a server called «🇱🇻 Латвия #3» is
     * already carrying the string "LV" and nothing has to be asked of the network.
     */
    @Test
    fun `a flag in the name is a country code`() {
        assertEquals("LV", WorldMap.countryCodeOf("🇱🇻 🎮 ⚡️ ⭐️ Латвия #3"))
        assertEquals("NL", WorldMap.countryCodeOf("🇳🇱 ⚡️ ⭐️ Нидерланды | Torrent ✅"))
        assertEquals("DE", WorldMap.countryCodeOf("🇩🇪 🎮 ⭐️ LTE Авто - Германия #2"))
    }

    /** Other emoji are not flags, and one Regional Indicator on its own is not a country. */
    @Test
    fun `a name without a flag has no country`() {
        assertNull(WorldMap.countryCodeOf("Fast server #4"))
        assertNull(WorldMap.countryCodeOf("🎮 ⚡️ ⭐️ Германия"))
        assertNull(WorldMap.countryCodeOf(""))
    }

    @Test
    fun `label points land where the country is`() {
        val latvia = WorldMap.centroidOf("LV")
        assertNotNull(latvia)
        assertEquals(57.1, latvia!![0], 0.2)
        assertEquals(25.5, latvia[1], 0.2)

        // A southern and a western one, so a sign error in either coordinate cannot hide.
        val australia = WorldMap.centroidOf("AU")!!
        assertTrue("Australia is south of the equator", australia[0] < 0)
        val unitedStates = WorldMap.centroidOf("US")!!
        assertTrue("the United States is west of Greenwich", unitedStates[1] < 0)
    }

    @Test
    fun `a code that is not in the table has no point`() {
        assertNull(WorldMap.centroidOf("ZZ"))
        assertNull(WorldMap.centroidOf("L"))
        assertNull(WorldMap.centroidOf(""))
    }

    /**
     * Every entry has to parse. A table edited by hand is a table with a missing comma in it one
     * day, and the failure mode without this is a marker quietly sitting off the coast of Africa.
     */
    @Test
    fun `every entry in the table is a real place`() {
        var found = 0
        for (first in 'A'..'Z') {
            for (second in 'A'..'Z') {
                val point = WorldMap.centroidOf("$first$second") ?: continue
                found++
                assertTrue("$first$second latitude ${point[0]}", point[0] in -90.0..90.0)
                assertTrue("$first$second longitude ${point[1]}", point[1] in -180.0..180.0)
            }
        }
        assertEquals("the table is 175 countries", 175, found)
    }
}
