// Package yumi is the Android binding for Xray-core.
//
// It exists because Xray, unlike sing-box, ships no mobile binding of its own: there is no
// libbox equivalent to hand an .aar to Gradle. What crosses into Kotlin is deliberately narrow —
// gomobile can only carry a handful of types across the boundary, and a wide surface here would
// be a wide surface to keep working through every core upgrade.
package yumi

import (
	"github.com/xtls/xray-core/core"

	// Registers every protocol, transport and app with the core's factory tables. Without this
	// blank import the library links and starts, and then refuses every outbound in the config
	// with "unknown protocol" — the failure looks like a bad config rather than a missing import.
	_ "github.com/xtls/xray-core/main/distro/all"
)

// Version reports the core this .aar was built from, so a running phone can be matched to a
// revision without trusting the file name.
func Version() string {
	return core.Version()
}
