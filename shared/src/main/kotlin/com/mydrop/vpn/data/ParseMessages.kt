package com.mydrop.vpn.data

import androidx.annotation.StringRes
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.parse.DeepLinkPayload
import com.mydrop.vpn.core.parse.EmptyReason
import com.mydrop.vpn.core.parse.UnsupportedReason

/**
 * Turns the parsers' refusal codes into a sentence.
 *
 * The mapping lives here rather than in `core/parse` so that the parsers stay free of resources
 * and remain testable without a device: a test asserts that a link was refused *because* it was
 * unrecognised, which is a stronger statement than asserting a particular Russian sentence, and
 * it survives every retranslation.
 */
@get:StringRes
val EmptyReason.messageRes: Int
    get() = when (this) {
        EmptyReason.EmptyResponse -> R.string.reason_empty_response
        EmptyReason.LinksUnreadable -> R.string.reason_links_unreadable
        EmptyReason.NotBase64 -> R.string.reason_not_base64
        EmptyReason.NoSupportedServers -> R.string.reason_no_supported_servers
    }

@get:StringRes
val UnsupportedReason.messageRes: Int
    get() = when (this) {
        UnsupportedReason.EmptyLink -> R.string.reason_empty_link
        UnsupportedReason.BadDnsAddress -> R.string.reason_bad_dns
        UnsupportedReason.NoPayload -> R.string.reason_no_payload
        UnsupportedReason.UnknownFormat -> R.string.reason_unknown_link
        UnsupportedReason.UnsupportedAction -> R.string.reason_unsupported_action
        UnsupportedReason.NotRecognised -> R.string.reason_not_recognised
    }

/** The only reason that names something from the link itself, hence the special case. */
fun Strings.describe(payload: DeepLinkPayload.Unsupported): String =
    if (payload.reason == UnsupportedReason.UnsupportedAction) {
        get(payload.reason.messageRes, payload.action.orEmpty())
    } else {
        get(payload.reason.messageRes)
    }
