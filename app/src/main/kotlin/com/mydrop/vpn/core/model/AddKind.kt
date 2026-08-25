package com.mydrop.vpn.core.model

import androidx.annotation.StringRes
import com.mydrop.vpn.R

/**
 * What the user says they are pasting.
 *
 * The app can usually tell — a `vless://` link is a server, an `sdns://` blob is a resolver — but
 * the interesting case is the one it cannot: `https://…` is a subscription for one provider and a
 * DoH resolver for the next, and the difference lives in a path convention rather than anywhere
 * authoritative. Detection stays the default because it is right most of the time; naming the kind
 * is there for when it is not, and it settles the question instead of arguing with a heuristic.
 */
enum class AddKind(@StringRes val labelRes: Int) {
    Auto(R.string.add_kind_auto),
    Subscription(R.string.add_kind_subscription),
    Server(R.string.add_kind_server),
    Dns(R.string.add_kind_dns),
}
