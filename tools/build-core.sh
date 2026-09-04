#!/usr/bin/env bash
#
# Builds the Xray core into shared/libs/libyumi.aar, which both products use.
#
# Xray, unlike sing-box, ships no mobile binding of its own — there is no `build_libbox` to run and
# no published .aar to depend on. So `core-xray/` is our own gomobile module, and this script is the
# only supported way to turn it into something Gradle can consume.
#
#   tools/build-core.sh              # both ABIs, into shared/libs/libyumi.aar
#   tools/build-core.sh arm64        # just arm64-v8a, which is every modern phone and half the wait
#
# Four pins below are load-bearing. Each one cost an afternoon to find, so none of them are
# preferences and none should be "cleaned up":
#
#   1. Go 1.26. Xray's go.mod names it and will not build on 1.25. sing-box's go.mod names 1.25.5
#      and will not link on anything newer — its //go:linkname reach into x/net/http2 is rejected
#      and the .so dies with "recompile with -fPIC". Both toolchains therefore live side by side
#      under .tools/toolchain, and `GO_TOOLCHAIN` picks one. Neither can be "the" version.
#
#   2. GOTOOLCHAIN=local. Without it, `auto` is free to fetch and switch to another SDK behind the
#      pin, which quietly undoes point 1. `auto` also only ever upgrades, so a go.mod asking for
#      less than what is on PATH is considered satisfied and ignored.
#
#   3. The upstream gomobile, not the one on PATH. `.tools/toolchain/gopath/bin/gomobile` is
#      SagerNet's fork, which sing-box needs and which expects `github.com/sagernet/gomobile/bind`
#      to be a dependency of the module being bound. Point it at this module and it fails with
#      "no Go package in github.com/sagernet/gomobile/bind". The two cannot share a bin directory,
#      so the upstream pair lives in its own.
#
#   4. -ldflags=-checklinkname=0. github.com/wlynxg/anet — pulled in transitively, and the reason
#      Xray can enumerate interfaces at all on Android 11+ — reaches into net.zoneCache with
#      //go:linkname. Go 1.23 started refusing that. Everything compiles and the link fails with
#      "link: github.com/wlynxg/anet: invalid reference to net.zoneCache".
#
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$REPO/core-xray"
OUT="$REPO/shared/libs/libyumi.aar"

# Matches the app's minSdk. gomobile defaults to something older, and the mismatch surfaces at
# install time on a real phone rather than here.
ANDROID_API=26

# The Java package the generated classes land in. Changing it is a source change in the app, not a
# flag, so it is spelled out here rather than left to gomobile's guess from the module path.
JAVAPKG=com.mydrop.vpn.xray

case "${1:-both}" in
    arm64)  TARGETS="android/arm64" ;;
    arm)    TARGETS="android/arm" ;;
    both|"") TARGETS="android/arm64,android/arm" ;;
    *)      echo "usage: $(basename "$0") [arm64|arm|both]" >&2; exit 2 ;;
esac

GO_TOOLCHAIN=go1.26
export GO_TOOLCHAIN
# shellcheck source=/dev/null
source /e/Projects/.tools/env.sh
export GOTOOLCHAIN=local

GOMOBILE_BIN="$TOOLS/toolchain/gomobile-x/bin"
GOMOBILE_BIN_POSIX="/e/Projects/.tools/toolchain/gomobile-x/bin"

if [ ! -x "$GOMOBILE_BIN_POSIX/gomobile.exe" ]; then
    echo "==> installing upstream gomobile into its own bin (see note 3)"
    GOBIN="$GOMOBILE_BIN" go install \
        golang.org/x/mobile/cmd/gomobile@latest \
        golang.org/x/mobile/cmd/gobind@latest
fi

# Prepended, so the upstream pair wins over SagerNet's for this process only.
export PATH="$GOMOBILE_BIN_POSIX:$PATH"

echo "==> $(go version)"
echo "==> targets: $TARGETS"
echo "==> core:    $(cd "$MODULE" && go list -m -f '{{.Version}}' github.com/xtls/xray-core)"

mkdir -p "$(dirname "$OUT")"
cd "$MODULE"

# Roughly two minutes cold for one ABI, and about twice that for both. Almost all of it is cgo
# compiling gVisor; a second run with a warm build cache is well under a minute.
time gomobile bind \
    -target="$TARGETS" \
    -androidapi "$ANDROID_API" \
    -ldflags="-checklinkname=0" \
    -javapkg="$JAVAPKG" \
    -o "$OUT" \
    .

echo "==> $OUT"
ls -lh "$OUT" | awk '{print "    " $5}'
unzip -l "$OUT" | awk '$4 ~ /\.so$/ {printf "    %-34s %6.1f MB\n", $4, $1/1048576}'
