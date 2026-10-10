<div align="center">

<img src="docs/icon.svg" width="112" alt="Yumi">

# Yumi

**An Android VPN client on the Xray core that fixes itself.**

Subscriptions, servers from a link or a QR code, your own DNS resolvers, a television with the phone as its remote —<br>in Material 3 Expressive, with a tunnel that leaves a failed server while you are not looking.

<br>

[![Release](https://img.shields.io/github/v/release/GXUser7/yumi?style=flat-square&label=release&labelColor=1E1612&color=F2B8A0)](https://github.com/GXUser7/yumi/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/GXUser7/yumi/total?style=flat-square&label=downloads&labelColor=1E1612&color=F2B8A0)](https://github.com/GXUser7/yumi/releases)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-F2B8A0?style=flat-square&labelColor=1E1612)](#installation)
[![Xray-core](https://img.shields.io/badge/core-Xray-F2B8A0?style=flat-square&labelColor=1E1612)](https://github.com/XTLS/Xray-core)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203%20Expressive-F2B8A0?style=flat-square&labelColor=1E1612)](#building-from-source)
[![License](https://img.shields.io/github/license/GXUser7/yumi?style=flat-square&label=license&labelColor=1E1612&color=F2B8A0)](#license)
[![Telegram](https://img.shields.io/badge/Telegram-channel-F2B8A0?style=flat-square&labelColor=1E1612&logo=telegram&logoColor=white)](https://t.me/MaterialYouCloud)

<br>

### [Download the latest release](https://github.com/GXUser7/yumi/releases/latest)

[Features](#features) &nbsp;·&nbsp; [Television](#on-a-television) &nbsp;·&nbsp; [Privacy](#privacy) &nbsp;·&nbsp; [Installation](#installation) &nbsp;·&nbsp; [Building](#building-from-source) &nbsp;·&nbsp; [Русский](README.md)

<br>

<img src="docs/banner.png" width="100%" alt="Tunnel off, speed test, protected connection, session statistics and the TV remote">

</div>

<br>

## Why Yumi

<table>
  <tr>
    <td width="33%" valign="top">
      <h3>It fixes itself</h3>
      Every twenty seconds it checks that data really flows through the tunnel, and moves off a failed server onto a working one. Then it tells you it did.
    </td>
    <td width="33%" valign="top">
      <h3>Almost any subscription</h3>
      VLESS, VMess, Trojan, Shadowsocks, Hysteria2, WireGuard; REALITY and XHTTP; subscriptions as links, Clash YAML, Xray JSON and SIP008.
    </td>
    <td width="33%" valign="top">
      <h3>Nothing extra leaves the phone</h3>
      No statistics, no analytics. Servers and keys stay out of Google's backup, and the device identifier panels see is random.
    </td>
  </tr>
</table>

<br>

## Features

<table>
  <tr>
    <td width="62%" valign="middle">

### Connecting

- **One button** brings up a tunnel on the [Xray](https://github.com/XTLS/Xray-core) core.
- **Changing servers on a live tunnel does not break the connection:** the servers live in the configuration as a group, and moving between them is a pointer swap inside the core. The DNS cache and open connections stay put; the traffic counters and session timer do not reset.
- **Routing:** everything through the tunnel, everything direct, or by rules — Russian sites and addresses go around the VPN.
- **A quick settings tile**, auto-start on boot and support for "Always-on VPN".

</td>
    <td width="38%" align="center"><img src="docs/screenshots/tunnel-off.png" width="260" alt="Tunnel off"></td>
  </tr>
</table>

<table>
  <tr>
    <td width="38%" align="center"><img src="docs/screenshots/subscriptions.png" width="260" alt="Subscriptions"></td>
    <td width="62%" valign="middle">

### Servers and subscriptions

- **You can add almost anything:** `vless://`, `vmess://`, `trojan://`, `ss://`, `hysteria2://`, `wireguard://`, `socks://`, `http://` — by pasting or by QR code; everything except `http://` is also caught when you tap such a link in another app.
- **Subscriptions in any format panels use:** a list of links (base64 or plain text), **Clash YAML**, **Xray/v2ray JSON**, **SIP008**. Say what you are pasting, or leave the detection to the app.
- **Transports:** TCP/RAW, WebSocket, gRPC, HTTPUpgrade, **XHTTP** (splithttp), mKCP, QUIC — with TLS, REALITY, Vision and browser-fingerprint mimicry (uTLS).
- **Honest about what the core cannot do:** TUIC, AnyTLS, ShadowTLS, Hysteria v1, SSH and sing-box JSON subscriptions are filtered out when added and written to the journal, rather than sitting in the list quietly not working.

</td>
  </tr>
</table>

<table>
  <tr>
    <td width="62%" valign="middle">

### It fixes itself

- **A check through the tunnel:** every twenty seconds the app pulls a page through it — a server can answer on its port while carrying nothing at all. Two failures in a row, the second three seconds after the first, and the tunnel moves to a working server from a replacement list you pick by hand.
- **It does not blame a server for somebody else's fault:** with no internet (a lift, a tube station) the checks pause, a change of network closes the old connections at once, and when nothing answers at all the app stays put.
- **Your own servers for mobile data:** on a cellular network the tunnel moves onto these and stays within them; back on Wi-Fi it returns to the ordinary list.
- **A fallback resolver:** if your DNS stops answering, the app moves to `1.1.1.1` until the next reconnection, and says so.
- **Notices about what it repaired by itself** — a replaced server, a replaced resolver, an emptied list, a new version; four separate switches.

</td>
    <td width="38%" align="center"><img src="docs/screenshots/connected.png" width="260" alt="Connected"></td>
  </tr>
</table>

<details>
<summary><b>More: why it works this way</b></summary>

<br>

**Why the check pulls data instead of pinging a port.** A server can answer on its port while
carrying nothing at all. From the outside such a server looks alive, and only a request through it
shows that traffic is not getting anywhere.

**Why cellular rather than metered.** "Metered" describes a billing arrangement rather than a
network, so a temporarily unmetered 5G plan and a home Wi-Fi marked as limited both stay out of it.
The move waits for the network to settle — four seconds onto cellular, twelve on the way back —
because at the edge of coverage a phone flickers between networks for as long as somebody stands
there. With the screen off the move is deferred: some firmwares switch Wi-Fi off along with the
screen and fall back to cellular.

**Why a fallback resolver.** A dead resolver is the one failure changing servers cannot fix, and the
least visible: every site stops opening while the tunnel demonstrably carries traffic. So the health
check has a second half that resolves a name through your own DNS.

**Why notices.** The whole value of moving off a failed server is that nobody is watching when it
happens — which is exactly why nobody learns that it did.

</details>

<table>
  <tr>
    <td width="38%" align="center"><img src="docs/screenshots/speed.png" width="260" alt="Speed test"></td>
    <td width="62%" valign="middle">

### Latency and speed

- **Latency three ways:** a TCP handshake, a full TLS handshake (for REALITY that is the difference between "the port is open" and "the disguise works") and the median of three probes; QUIC nodes are checked with a UDP probe.
- **Speed through the selected server,** not past the tunnel: download, upload, response time and jitter with a dial gauge and a live chart.
- **Session statistics:** what came in and went out during the connection, with the routing mode at hand.

</td>
  </tr>
</table>

### DNS and routing

- **DNS resolvers are objects just like servers:** added by a link (a DoH address, `tcp://`, `udp://`, a bare address or an `sdns://` stamp), stored as a list and switched with a single tap, including on a live tunnel.
- **No quiet downgrades:** DNS-over-TLS and DNS-over-QUIC are not in this core, so `tls://` and `quic://` are refused when added rather than turned into plain UDP.
- **"Direct" plus your own DNS** — no VPN, but the resolver you want: traffic leaves from your ordinary address while names are resolved by the service you picked.
- **Routing databases** (`geoip.dat` and `geosite.dat`, some 24 MB) are fetched on their own after the first connection; settings show whether they are on disk, with a button to refresh. Until they are there, the country rules are left out of the configuration — and that is shown rather than hidden.
- **Also:** local network bypass, ad-domain blocking and per-app split tunnelling.

### And also

- **Updates from inside the app:** there is no store, so a check every twelve hours or on a button, a download, and a handover to the system installer.
- **English and Russian** — the system language by default, or pick one in settings.
- **A dark theme with an AMOLED mode** and a palette taken from the system wallpaper.
- **The core's journal** right inside the app.

<br>

## On a television

A separate Android TV app — the **yumi-tv** file in the same release. Driven from the TV remote,
the subscription travels over from the phone by QR code, seven built-in palettes, and subscriptions
refresh as soon as the app opens.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/tv-connect.png" alt="Connecting on a television"></td>
    <td width="50%"><img src="docs/screenshots/tv-servers.png" alt="Servers on a television"></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/tv-pairing.png" alt="Subscriptions on a television"></td>
    <td><img src="docs/screenshots/tv-settings.png" alt="Settings on a television"></td>
  </tr>
</table>

### The phone as a remote

Once, scan the code from Settings → Phone remote on the television through Subscriptions → Add →
Scan on the phone. From then on the phone finds the television on the network by itself, and a
remote button appears on the tunnel screen: switch the tunnel on and off and change the server
without getting up from the sofa.

<p align="center">
  <img src="docs/screenshots/tv-remote-pairing.png" width="420" alt="Pairing code on the television">
  &nbsp;
  <img src="docs/screenshots/remote-pairing.png" width="150" alt="The phone found the television">
  &nbsp;
  <img src="docs/screenshots/remote.png" width="150" alt="The remote on the phone">
</p>

<br>

## Privacy

**The app collects no statistics and sends no data about you anywhere.** Servers, subscriptions and
keys live only on the phone: Android backup is turned off for them, so they reach neither Google's
cloud nor a new handset during a device-to-device transfer.

The connections it makes on its own: refreshing your subscriptions, measuring latency to your
servers and — if you start it — measuring speed through `speed.cloudflare.com`. All of them go
around the tunnel: otherwise the measurement would be measuring itself, and a subscription refresh
would travel through the very server it hands out.

One exception, and it is needed where the internet is handed out by allow-list: when a subscription
will not open directly, the app repeats the request through its own tunnel. Without that it cannot
be refreshed on such a network at all — the app excludes itself from the tunnel it serves, so its
requests hit the same block as everything else.

Panels that bind to a device receive an `x-hwid` identifier. It is **random and generated once at
install time**: a real device identifier would become a marker by which every panel the user has
ever subscribed to could recognise them.

<br>

## Installation

1. Download `yumi-<version>-arm64-v8a.apk` from the [releases](https://github.com/GXUser7/yumi/releases/latest).
   Almost everybody wants **arm64-v8a**; `armeabi-v7a` is only for very old phones.
   For a television — `yumi-tv-<version>-…apk`.
2. Open the file and allow installation from this source if Android asks.
3. On the first connection the system will ask for VPN permission — the tunnel cannot come up
   without it.

Android 8.0 or newer is required. Updates install over the top; servers and settings are kept.

There is also a Windows version — [yumi-desktop](https://github.com/GXUser7/yumi-desktop) (beta).

<br>

## Building from source

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2
./gradlew :app:assembleDebug          # phone
./gradlew :tv:assembleDebug           # television
./gradlew :shared:testDebugUnitTest   # the tests live here; :app and :tv hold none
```

**The core is not in the repository.** Xray publishes no mobile binding, so this one lives here:
the `core-xray/` module, out of which `gomobile bind` produces `shared/libs/libyumi.aar`. That file is
over GitHub's 100 MB per-file limit, so you have to build it yourself and drop it in by hand —
nothing compiles without it, tests included. The build command, and everything else worth knowing
about the internals, is in [DEVELOPMENT.md](DEVELOPMENT.md): why the TUN stack is gvisor, how core
failures are caught, where the geo rules come from, and how all of it is checked without a device.

<br>

## Acknowledgements

The core is [Xray-core](https://github.com/XTLS/Xray-core) by XTLS. The routing databases come
from [v2fly/geoip](https://github.com/v2fly/geoip) and
[v2fly/domain-list-community](https://github.com/v2fly/domain-list-community).

## License

[GNU GPL v3 or later](LICENSE). Copyright © 2026 GXUser7.

<details>
<summary>Why GPL</summary>

<br>

Still not a free choice, though the reason changed along with the core. Xray-core itself is
MPL-2.0, and that alone would have allowed any licence at all. But Xray's Shadowsocks-2022
implementation is built on [`sagernet/sing`](https://github.com/SagerNet/sing), which is GPLv3 or
later, and `libyumi.aar` links it straight into the app along with everything else. So the app as a
whole has to be GPLv3 as well.

In practice that means anyone handing out a built APK owes the source alongside it, under the same
licence.

</details>

---

<sub>Yumi only connects to the servers in your own subscriptions and provides no internet access by itself.</sub>
