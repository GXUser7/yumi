package com.mydrop.vpn.core.xray

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoCategoriesTest {

    private fun ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (v and 0x7F.inv().toLong() != 0L) {
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    private fun ByteArrayOutputStream.lengthDelimited(field: Int, bytes: ByteArray) {
        varint(((field shl 3) or 2).toLong())
        varint(bytes.size.toLong())
        write(bytes)
    }

    /** An entry the way both databases write one: the code first, then a pile of data. */
    private fun entry(code: String, payload: Int, codeFirst: Boolean = true): ByteArray =
        ByteArrayOutputStream().apply {
            val data = ByteArray(payload) { it.toByte() }
            if (!codeFirst) lengthDelimited(2, data)
            lengthDelimited(1, code.toByteArray())
            if (codeFirst) lengthDelimited(2, data)
            // A varint field after it, which a reader must step over rather than misread.
            varint(((3 shl 3) or 0).toLong())
            varint(1)
        }.toByteArray()

    private fun database(vararg entries: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        entries.forEach { lengthDelimited(1, it) }
    }.toByteArray()

    @Test
    fun `reads every code and skips the data`() {
        val bytes = database(
            entry("RU", payload = 5_000),
            entry("category-ru", payload = 200),
            entry("PRIVATE", payload = 0),
            entry("google", payload = 300, codeFirst = false),
        )
        val codes = GeoCategories.read(ByteArrayInputStream(bytes))
        assertEquals(setOf("RU", "CATEGORY-RU", "PRIVATE", "GOOGLE"), codes)
    }

    @Test
    fun `an empty file holds no codes`() {
        assertTrue(GeoCategories.read(ByteArrayInputStream(ByteArray(0))).isEmpty())
    }

    @Test
    fun `references name their code whatever the decoration`() {
        assertEquals("CATEGORY-RU", GeoCategories.codeOf("geosite:category-ru"))
        assertEquals("CATEGORY-RU", GeoCategories.codeOf("geosite:category-ru@cn"))
        assertEquals("RU", GeoCategories.codeOf("geoip:!ru"))
        assertEquals("RU", GeoCategories.codeOf(" GEOIP:ru "))
        assertNull(GeoCategories.codeOf("domain:example.com"))
        assertNull(GeoCategories.codeOf("geosite:"))
        assertTrue(GeoCategories.isSiteReference("geosite:google"))
        assertFalse(GeoCategories.isSiteReference("geoip:ru"))
    }
}
