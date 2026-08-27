# Yumi

*Read this in [Russian](README.md).*

A VPN client for Android: subscriptions, servers from a link or a QR code, DNS resolvers, a tunnel
on the [Xray](https://github.com/XTLS/Xray-core) core and a Material 3 Expressive interface.

**[Download the latest release](https://github.com/GXUser7/yumi/releases/latest)** ·
[Telegram channel](https://t.me/MaterialYouCloud)

Almost everybody wants the **arm64-v8a** file. The `armeabi-v7a` build is only for very old
phones; if you are not sure which one you have, take arm64. Android 8.0 or newer is required.

## What it can do

**You can add almost anything.** `vless://`, `vmess://`, `trojan://`, `ss://`, `hysteria2://`,
`hysteria://`, `tuic://`, `anytls://`, `wireguard://`, `ssh://`, `socks://` and `http://` links —
by pasting or by QR code; everything except `http://` is also caught when you tap such a link in
another app. Subscriptions are read in any of the formats panels hand them out in: a list of links
(base64 or plain text), **Clash YAML**, **sing-box JSON**, **Xray/v2ray JSON** and **SIP008**. When adding something you can say outright what you are
pasting — a subscription, a server or a DNS resolver — or leave the detection to the app.

Xray configurations travel **verbatim**: whatever the app's own model cannot express —
chains, multiplexing, ECH — reaches the core untouched.

**DNS resolvers** are objects just like servers: added by a link (`tls://`, `quic://`, a DoH
address or an `sdns://` stamp), stored as a list and switched with a single tap, including on a
live tunnel. The "Direct" mode together with a chosen resolver gives you "no VPN, but the DNS I
want" — traffic leaves from your ordinary address while names are resolved by the service you
picked.

**Latency and speed.** Latency is measured three ways: a TCP handshake, a full TLS handshake (for
REALITY that is the difference between "the port is open" and "the disguise works") and the median
of three probes; QUIC nodes are checked with a UDP probe. The speed test runs **through the
selected server** rather than past the tunnel, and reports download, upload, response time and
jitter with a dial gauge and a live chart.

**Routing.** Three modes: everything through the tunnel, everything direct, or by rules — Russian
sites and addresses go around the VPN. The geo rules ship inside the app, so connecting never waits
for lists to be downloaded. Separately: local network bypass, ad-domain blocking and per-app split
tunnelling.

**Changing servers on a live tunnel** does not break the connection: the core switches in place,
and the traffic counters and the session timer survive the switch. If a server stops answering, the
app moves itself to a live one from the same subscription.

**And also.** English and Russian — the system language by default, or pick one explicitly in
settings. A quick settings tile (with an add button, if your shell does not offer one), auto-start
on boot, support for "Always-on VPN", the core's journal right inside the app, a dark theme with
an AMOLED mode and a palette taken from the system wallpaper.

## Privacy

The app collects no statistics and sends no data about you anywhere. Servers, subscriptions and
keys live only on the phone: Android backup is turned off for them, so they reach neither Google's
cloud nor a new handset during a device-to-device transfer.

The connections it makes on its own: refreshing your subscriptions, measuring latency to your
servers and — if you start it — measuring speed through `speed.cloudflare.com`. All of them go
around the tunnel: otherwise the measurement would be measuring itself, and a subscription refresh
would travel through the very server it hands out.

Panels that bind to a device receive an `x-hwid` identifier. It is **random and generated once at
install time**: a real device identifier would become a marker by which every panel the user has
ever subscribed to could recognise them.

## Installation

1. Download `yumi-<version>-arm64-v8a.apk` from the [releases](https://github.com/GXUser7/yumi/releases/latest).
2. Open the file and allow installation from this source if Android asks.
3. On the first connection the system will ask for VPN permission — the tunnel cannot come up
   without it.

Updates install over the top; servers and settings are kept.

## Building from source

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

**The core is not in the repository.** `app/libs/libyumi.aar` weighs tens of megabytes per
architecture — more than GitHub's
100 MB per-file limit — so you have to build it yourself and drop it in by hand. The build command,
and everything else worth knowing about the internals, is in [DEVELOPMENT.md](DEVELOPMENT.md): why
the TUN stack is gvisor, how core failures are caught, where the geo rules come from, and how all
of it is checked without a device.

## Acknowledgements

The core is [Xray-core](https://github.com/XTLS/Xray-core) by XTLS. The geo databases come from
its own `sing-geosite` and `sing-geoip` repositories.
