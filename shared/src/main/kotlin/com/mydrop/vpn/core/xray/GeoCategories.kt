package com.mydrop.vpn.core.xray

import java.io.EOFException
import java.io.InputStream

/**
 * The category names inside a `geosite.dat` or `geoip.dat`, read without loading the file.
 *
 * Needed because a reference to a category the file does not hold is not a rule that matches
 * nothing: Xray refuses the entire configuration over it (`common/geodata/geodat_loader.go`, "code
 * not found"). This app's own rules name categories every published database has. A provider's
 * rules name whatever its own client ships with, and a single category the v2fly list does not
 * carry would mean a tunnel that never comes up while the switch is on — with the fault in a file
 * nobody here wrote.
 *
 * Both files are the same protobuf shape: a list whose entries are field 1, each entry holding its
 * code as field 1 (`GeoSiteList.entry` / `GeoSite.country_code`, `GeoIPList.entry` /
 * `GeoIP.country_code`). Only the code of each entry is read; the rest — the domains and ranges,
 * nearly all of the twenty-odd megabytes — is skipped over.
 */
object GeoCategories {

    /** Every code in [input], upper-cased the way Xray compares them (`strings.EqualFold`). */
    fun read(input: InputStream): Set<String> {
        val reader = Reader(input)
        val codes = HashSet<String>()
        while (true) {
            val tag = reader.varintOrEnd() ?: break
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            if (field == ENTRY_FIELD && wire == WIRE_LENGTH) {
                reader.codeOfEntry(reader.varint())?.let { codes += it.uppercase() }
            } else {
                reader.skipValue(wire)
            }
        }
        return codes
    }

    /**
     * The category a `geosite:`/`geoip:` reference names, upper-cased, or null for anything else.
     *
     * `geoip:!ru` is the reversed match and `geosite:category-ru@cn` an attribute filter; both
     * still name the `ru` and `category-ru` entries.
     */
    fun codeOf(reference: String): String? {
        val trimmed = reference.trim()
        val prefix = PREFIXES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: return null
        return trimmed.substring(prefix.length)
            .removePrefix("!")
            .substringBefore('@')
            .trim()
            .uppercase()
            .takeIf { it.isNotEmpty() }
    }

    /** Which file a reference is looked up in: `geosite.dat` for sites, `geoip.dat` otherwise. */
    fun isSiteReference(reference: String): Boolean =
        reference.trim().startsWith(GEOSITE, ignoreCase = true)

    private const val GEOSITE = "geosite:"
    private const val GEOIP = "geoip:"
    private val PREFIXES = listOf(GEOSITE, GEOIP)

    private const val ENTRY_FIELD = 1
    private const val CODE_FIELD = 1
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_FIXED32 = 5

    /** A protobuf reader that knows how many bytes it has consumed, for walking inside an entry. */
    private class Reader(private val input: InputStream) {

        var position = 0L
            private set

        fun varintOrEnd(): Long? {
            val first = input.read()
            if (first < 0) return null
            position++
            return varintFrom(first)
        }

        fun varint(): Long = varintFrom(byte())

        private fun varintFrom(first: Int): Long {
            var result = (first and 0x7F).toLong()
            var current = first
            var shift = 7
            while (current and 0x80 != 0) {
                check(shift <= 63) { "varint too long" }
                current = byte()
                result = result or ((current and 0x7F).toLong() shl shift)
                shift += 7
            }
            return result
        }

        private fun byte(): Int {
            val value = input.read()
            if (value < 0) throw EOFException()
            position++
            return value
        }

        fun skip(count: Long) {
            check(count >= 0) { "negative length" }
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    position += skipped
                } else {
                    // `skip` may decline without being at the end; one read tells the two apart.
                    byte()
                    remaining--
                }
            }
        }

        fun skipValue(wire: Int) {
            when (wire) {
                WIRE_VARINT -> varint()
                WIRE_FIXED64 -> skip(8)
                WIRE_FIXED32 -> skip(4)
                WIRE_LENGTH -> skip(varint())
                else -> error("wire type $wire")
            }
        }

        /** The code of an entry [length] bytes long, leaving the reader just past the entry. */
        fun codeOfEntry(length: Long): String? {
            val end = position + length
            var code: String? = null
            while (position < end && code == null) {
                val tag = varint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                if (field == CODE_FIELD && wire == WIRE_LENGTH) {
                    code = String(bytes(varint()), Charsets.UTF_8)
                } else {
                    skipValue(wire)
                }
            }
            skip(end - position)
            return code
        }

        private fun bytes(size: Long): ByteArray {
            check(size in 0..MAX_CODE_BYTES) { "implausible code length $size" }
            val bytes = ByteArray(size.toInt())
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n < 0) throw EOFException()
                read += n
            }
            position += size
            return bytes
        }
    }

    /** A category name is a word or two; anything longer means the file is not what it claims. */
    private const val MAX_CODE_BYTES = 256L
}
