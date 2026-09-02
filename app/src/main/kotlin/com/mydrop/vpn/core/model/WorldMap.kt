package com.mydrop.vpn.core.model

/**
 * Where in the world a server is, and what the world looks like there.
 *
 * Two things live here because they answer the same question from opposite ends: the mask says
 * which parts of the globe are land, and [centroidOf] says which part of the globe a server is on.
 * Both are pure and both are offline — see [PixelPlanet][com.mydrop.vpn.ui.components.PixelPlanet]
 * for the drawing, and for the loading of `R.raw.world_land`, which is the only Android-shaped
 * part of any of it.
 *
 * The alternative was asking a geolocation service per server, which would mean a plaintext HTTP
 * request naming a VPN endpoint — free geolocation APIs do not offer TLS. Whatever else that is,
 * it is not something this app should be emitting.
 */
object WorldMap {

    /** Columns and rows of the land mask: two degrees a side, 16200 cells in 2025 bytes. */
    const val MASK_COLUMNS = 180
    const val MASK_ROWS = 90

    /** Bytes the mask must be, checked at load rather than trusted. */
    const val MASK_BYTES = MASK_COLUMNS * MASK_ROWS / 8

    /**
     * Whether the two-degree cell containing this point is land.
     *
     * Longitude wraps rather than clamps: the antimeridian is a seam in the array and not in the
     * world, and clamping there would smear the easternmost column across the Pacific.
     */
    fun isLand(mask: ByteArray, latitude: Double, longitude: Double): Boolean {
        if (mask.size < MASK_BYTES) return false
        var column = ((longitude + 180.0) / 2.0).toInt()
        column = ((column % MASK_COLUMNS) + MASK_COLUMNS) % MASK_COLUMNS
        val row = (((90.0 - latitude) / 2.0).toInt()).coerceIn(0, MASK_ROWS - 1)
        val index = row * MASK_COLUMNS + column
        return (mask[index / 8].toInt() and (0x80 ushr (index % 8))) != 0
    }

    /**
     * The ISO 3166-1 alpha-2 code carried by a flag emoji anywhere in [name], or null.
     *
     * A flag is two Regional Indicator Symbols, and those are the letters A–Z shifted up to
     * U+1F1E6. So `🇱🇻` is not a picture of Latvia that has to be recognised, it is the string
     * "LV" written in a different block — which means every server named the way this app's
     * subscriptions name them already carries its own country, and nothing has to be asked of the
     * network to find out where a server is.
     */
    fun countryCodeOf(name: String): String? {
        var index = 0
        while (index < name.length) {
            val first = name.codePointAt(index)
            val firstWidth = Character.charCount(first)
            if (first in REGIONAL_FIRST..REGIONAL_LAST && index + firstWidth < name.length) {
                val second = name.codePointAt(index + firstWidth)
                if (second in REGIONAL_FIRST..REGIONAL_LAST) {
                    val a = 'A' + (first - REGIONAL_FIRST)
                    val b = 'A' + (second - REGIONAL_FIRST)
                    return "$a$b"
                }
            }
            index += firstWidth
        }
        return null
    }

    /**
     * Latitude and longitude for an ISO country code, or null for one that is not in the table.
     *
     * These are Natural Earth's own label points rather than computed centroids, and the
     * difference matters: the centroid of Norway is in the sea, and the centroid of Indonesia is
     * in the sea twice. A label point is chosen to sit on the country.
     */
    fun centroidOf(code: String): DoubleArray? {
        if (code.length != 2) return null
        val at = CENTROIDS.indexOf(code)
        if (at < 0) return null
        // Codes are two letters and every entry starts one, so a match inside a number would need
        // a letter there; there are none. Still checked, because a table is edited by hand.
        if (at > 0 && CENTROIDS[at - 1] != ' ') return null
        val end = CENTROIDS.indexOf(' ', at).let { if (it < 0) CENTROIDS.length else it }
        val body = CENTROIDS.substring(at + 2, end)
        val comma = body.indexOf(',')
        if (comma < 0) return null
        val latitude = body.substring(0, comma).toDoubleOrNull() ?: return null
        val longitude = body.substring(comma + 1).toDoubleOrNull() ?: return null
        return doubleArrayOf(latitude, longitude)
    }

    private const val REGIONAL_FIRST = 0x1F1E6
    private const val REGIONAL_LAST = 0x1F1FF

    /**
     * Label points for 175 countries, from Natural Earth's `ne_110m_admin_0_countries`.
     *
     * Kept as one string rather than a map because it is read once per connection and never in a
     * loop, and 2 KB of constant beats 175 map entries built at class-load time on every start.
     */
    private const val CENTROIDS =
        "AE23.5,54.5 AF34.2,66.5 AL40.7,20.1 AM40.5,44.8 AO-12.2,18.0 AQ-79.8,35.9 AR-33.5,-64.2 " +
            "AT47.5,14.1 AU-24.1,134.0 AZ40.4,47.2 BA44.1,18.1 BD24.2,89.7 BE50.8,4.8 BF12.7,-1.4 " +
            "BG42.5,25.2 BI-3.3,29.9 BJ10.3,2.4 BN4.4,114.6 BO-16.7,-64.6 BR-12.1,-49.6 " +
            "BS26.4,-77.1 BT27.5,90.0 BW-22.1,24.2 BY53.8,28.4 BZ17.2,-88.7 CA60.3,-101.9 " +
            "CD-1.9,23.5 CF7.0,20.9 CG0.1,15.9 CH46.7,7.5 CI7.5,-5.6 CL-38.2,-72.3 CM4.6,12.5 " +
            "CN32.5,106.3 CO3.4,-73.2 CR10.1,-84.1 CU21.3,-78.0 CY34.9,33.1 CZ49.9,15.4 " +
            "DE51.0,9.7 DJ12.0,42.5 DK56.0,9.0 DO19.1,-70.7 DZ27.4,2.8 EC-1.3,-78.2 EE58.7,25.9 " +
            "EG26.2,29.4 EH24.0,-12.6 ER15.8,38.3 ES40.1,-3.5 ET8.0,39.1 FI63.3,27.3 FJ-17.8,178.0 " +
            "FK-51.6,-58.7 FR46.7,2.6 GA-0.4,11.8 GB54.4,-2.1 GE41.9,43.7 GH7.7,-1.0 GL74.3,-39.3 " +
            "GM13.6,-15.0 GN10.6,-10.0 GQ2.3,9.0 GR39.5,21.7 GT15.0,-90.5 GW12.2,-14.5 GY5.1,-58.9 " +
            "HN14.8,-86.9 HR45.8,16.4 HT19.3,-72.2 HU47.1,19.4 ID-1.0,101.9 IE53.1,-7.8 " +
            "IL30.9,34.8 IN22.7,79.4 IQ33.1,43.3 IR32.2,54.9 IS64.8,-18.7 IT44.7,11.1 JM18.1,-77.3 " +
            "JO30.8,36.4 JP36.1,138.4 KE0.5,37.9 KG41.7,74.5 KH12.6,104.5 KP39.9,126.4 " +
            "KR36.4,128.1 KW29.4,47.3 KZ49.1,68.7 LA19.4,102.5 LB34.1,36.0 LK7.6,80.7 LR6.4,-9.5 " +
            "LS-29.5,28.2 LT55.1,24.1 LU49.7,6.1 LV57.1,25.5 LY26.6,18.0 MA31.7,-7.2 MD47.4,28.5 " +
            "ME42.8,19.1 MG-18.6,46.7 MK41.6,21.6 ML18.7,-2.0 MM21.6,95.8 MN46.0,104.2 MR19.6,-9.7 " +
            "MW-13.4,33.6 MX23.9,-102.3 MY2.5,113.8 MZ-13.9,37.8 NA-20.6,17.1 NC-21.1,165.1 " +
            "NE17.4,9.5 NG9.4,7.5 NI12.7,-85.1 NL52.4,5.6 NO61.4,9.7 NP28.3,83.6 NZ-39.8,172.8 " +
            "OM22.1,57.3 PA8.7,-80.4 PE-13.0,-72.9 PG-5.7,143.9 PH11.2,122.5 PK29.3,68.5 " +
            "PL52.0,19.5 PR18.2,-66.5 PS32.0,35.3 PT39.6,-8.3 PY-21.7,-60.1 QA25.2,51.1 " +
            "RO45.7,25.0 RS44.2,20.8 RU58.2,44.7 RW-1.9,30.1 SA23.8,44.7 SB-8.0,159.2 SD16.3,29.3 " +
            "SE65.9,19.0 SI46.1,14.9 SK48.7,19.0 SL8.6,-11.8 SN15.1,-14.8 SO3.6,45.2 SR4.1,-55.9 " +
            "SS7.2,30.4 SV13.7,-88.9 SY35.0,38.3 SZ-26.5,31.5 TD15.1,18.6 TF-49.3,69.1 TG8.8,1.1 " +
            "TH15.5,101.1 TJ38.2,72.6 TL-8.8,125.9 TM39.9,58.7 TN33.7,9.0 TR39.3,34.5 TT11.0,-60.9 " +
            "TW23.7,120.9 TZ-6.1,35.0 UA49.7,32.1 UG2.0,32.9 US39.5,-97.5 UY-33.0,-56.0 " +
            "UZ41.7,64.0 VE7.2,-64.6 VN21.7,105.4 VU-15.4,166.9 XK42.6,20.9 YE15.3,45.9 " +
            "ZA-29.7,23.7 ZM-14.7,26.4 ZW-18.9,29.9"
}
