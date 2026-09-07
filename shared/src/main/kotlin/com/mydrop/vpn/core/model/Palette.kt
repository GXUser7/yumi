package com.mydrop.vpn.core.model

import androidx.annotation.StringRes
import com.mydrop.vpn.shared.R
import kotlinx.serialization.Serializable

/**
 * The accent the app is dressed in.
 *
 * Only the hue is stored, not a table of colours: every tone the scheme needs is derived from it at
 * the same saturations and lightnesses, so the seven palettes are the same palette seven times over
 * rather than seven sets of hand-picked values that drift apart the moment one of them is tuned.
 * The derivation lives in `:design`, where the colour types are — see `Palettes.kt`.
 *
 * [Glacier] carries no hue and means "leave the scheme alone". It is the app's own ice blue,
 * hand-tuned against the neutrals it sits on, and reproducing it from a formula would only make it
 * slightly worse.
 *
 * [partnerHue] is the second data colour — upload against download, the marker against the map.
 * Held roughly opposite the accent so the two channels stay apart at a glance.
 *
 * Carried in [AppSettings] for every build, and offered on the television, which has no wallpaper
 * to take colours from: dynamic colour is the phone's answer to "make it mine" and there is no
 * equivalent on a set that shows one launcher background to everybody. Where dynamic colour is on,
 * the wallpaper wins and this is ignored — two things repainting the same scheme would only fight.
 */
@Serializable
enum class Palette(
    @StringRes val labelRes: Int,
    val hue: Float? = null,
    val partnerHue: Float = 0f,
) {
    Glacier(R.string.palette_glacier),
    Rose(R.string.palette_rose, hue = 344f, partnerHue = 186f),
    Lilac(R.string.palette_lilac, hue = 268f, partnerHue = 160f),
    Mint(R.string.palette_mint, hue = 158f, partnerHue = 28f),
    Peach(R.string.palette_peach, hue = 22f, partnerHue = 194f),
    Sand(R.string.palette_sand, hue = 44f, partnerHue = 210f),
    Sea(R.string.palette_sea, hue = 196f, partnerHue = 32f),
}
