# Xray JSON schema — ordinary proxy outbounds

**Source of truth:** `E:\Projects\.tools\xray`, commit `aa3d658` (`git describe` = `v26.7.28-23-gaa3d658`).
Every claim below cites `path/file.go:LINE` inside that tree. Anything I could not read is marked
**UNVERIFIED**.

**Read this first (migration hazards, all verified below):**

| Hazard | Effect if you get it wrong |
|---|---|
| `allowInsecure` is **removed** and is now a hard config error | Config fails to load, not a warning — `infra/conf/transport_security.go:361` |
| VLESS `encryption` is **mandatory** on the client, must literally be `"none"` | Config fails to load — `infra/conf/vless.go:370` |
| VLESS/Trojan with **no** `security` to a **public** address is refused | Config fails to load — `infra/conf/xray.go:250-263` |
| Omitting `tlsSettings.fingerprint` now means **uTLS Chrome**, not Go stdlib TLS | Silent behavior change vs. sing-box's `utls.enabled:false` — `transport/internet/tls/tls.go:188` |
| Shadowsocks-2022 method strings are matched **case-sensitively**; classic AEAD methods are lowercased first | `"2022-Blake3-..."` silently falls through to the classic path and then errors — `infra/conf/shadowsocks.go:219` vs `:18` |
| REALITY client vs. server is selected by **presence of `dest`/`target`**, not by a flag | A stray `dest` in a client config makes Xray demand `privateKey` — `infra/conf/transport_security.go:62` / `:179` |
| REALITY client uses **`shortId`/`serverName`** (singular). The plural `shortIds`/`serverNames` are explicitly rejected | Config fails to load — `infra/conf/transport_security.go:187-189`, `:199-201` |

---

## 1. The outbound envelope

Struct: `OutboundDetourConfig`, `infra/conf/xray.go:213-222`.

```go
type OutboundDetourConfig struct {
	Protocol       string           `json:"protocol"`
	SendThrough    *string          `json:"sendThrough"`
	Tag            string           `json:"tag"`
	Settings       *json.RawMessage `json:"settings"`
	StreamSetting  *StreamConfig    `json:"streamSettings"`
	ProxySettings  *ProxyConfig     `json:"proxySettings"`
	MuxSettings    *MuxConfig       `json:"mux"`
	TargetStrategy string           `json:"targetStrategy"`
}
```

The array lives at the top level as `"outbounds"` (`infra/conf/xray.go:401`).

| Field | JSON type | Required | Default | Meaning |
|---|---|---|---|---|
| `protocol` | string | **yes** | — | Selects the `settings` parser. Lower-cased before lookup (`infra/conf/loader.go:46`), so `"VLESS"` works. Valid outbound ids: `block`, `blackhole`, `loopback`, `direct`, `freedom`, `http`, `shadowsocks`, `socks`, `vless`, `vmess`, `trojan`, `hysteria`, `dns`, `wireguard` (`infra/conf/xray.go:37-52`). |
| `tag` | string | no | `""` | Routing handle. Copied to `core.OutboundHandlerConfig.Tag` (`infra/conf/xray.go:371`). Not validated for uniqueness here; `Config.Override` matches on it (`infra/conf/xray.go:512`). |
| `settings` | object | no | `{}` | Protocol-specific body. If absent, `[]byte("{}")` is parsed instead (`infra/conf/xray.go:353-356`), which is why `freedom`/`blackhole` need no `settings` at all. |
| `streamSettings` | object | no | `nil` → transport `tcp`, no security | See §2. |
| `sendThrough` | string | no | unset | Bind source address. Accepts an IP literal, an `IP/CIDR` (the mask part goes to `ViaCidr`), or the two magic domains `origin` / `srcip`; anything else errors `unable to send through:` (`infra/conf/xray.go:301-315`). |
| `proxySettings` | object | no | `nil` | Outbound chaining: `{"tag": "...", "transportLayer": bool}` (`infra/conf/transport_internet.go:312-317`). `tag` must be non-empty (`:321-323`). When `transportLayer` is true the tag is moved into `streamSettings.sockopt.dialerProxy` instead (`infra/conf/xray.go:330-341`), and setting both that and `sockopt.dialerProxy` is an error (`infra/conf/xray.go:224-232`). |
| `mux` | object | no | `nil` | `{enabled, concurrency, xudpConcurrency, xudpProxyUDP443}` (`infra/conf/xray.go:102-107`). `xudpProxyUDP443` defaults to `"reject"`; only `reject`/`allow`/`skip` accepted (`:111-117`). |
| `targetStrategy` | string | no | `"asis"` | How the *destination* domain is resolved for this outbound. Accepted verbatim (lower-cased): `asis`, `""`, `useip`, `useipv4`, `useipv6`, `useipv4v6`, `useipv6v4`, `forceip`, `forceipv4`, `forceipv6`, `forceipv4v6`, `forceipv6v4` (`infra/conf/xray.go:271-296`). Anything else: `unsupported target domain strategy`. |

**sing-box mismatch:** sing-box outbounds are flat objects with `type` + protocol fields at the same level
(`{"type":"vless","server":"...","server_port":443,"uuid":"...","tls":{...}}`). Xray splits this into
`protocol` + a nested `settings` object + a *separate* `streamSettings` object. `server`/`server_port`
become `address`/`port` **inside** `settings`, and TLS moves out of `settings` entirely into
`streamSettings`. There is no `detour` field — chaining is `proxySettings.tag`.

### Minimal envelope

```json
{
  "outbounds": [
    { "tag": "proxy", "protocol": "vless", "settings": { }, "streamSettings": { } },
    { "tag": "direct", "protocol": "freedom" },
    { "tag": "block", "protocol": "blackhole" }
  ]
}
```

---

## 2. `streamSettings` and how `security` selects none / tls / reality

Struct: `StreamConfig`, `infra/conf/transport_internet.go:44-63`.

| Field | JSON type | Required | Default | Meaning |
|---|---|---|---|---|
| `network` | string | no | `"tcp"` | Transport. `method` is an accepted alias and wins if present (`:74-76`). |
| `security` | string | no | `""` (= none) | `""`/`"none"`, `"tls"`, `"reality"`. Lower-cased (`:85`). `"xtls"` is a removed-feature error (`:113-114`). Anything else: `Unknown security "<x>"`. |
| `tlsSettings` | object | no | `{}` when `security=="tls"` | §3. Note: when `security=="tls"` and `tlsSettings` is absent, an empty `TLSConfig{}` is built anyway (`:88-91`). |
| `realitySettings` | object | **yes when `security=="reality"`** | — | §4. Absent → `REALITY: Empty "realitySettings"` (`:103-105`). |
| `rawSettings` / `tcpSettings` | object | no | — | `rawSettings` is the new name; it overwrites `tcpSettings` if both present (`:119-121`). |
| `xhttpSettings` / `splithttpSettings` | object | no | — | `xhttpSettings` wins (`:132-134`). |
| `kcpSettings`, `grpcSettings`, `wsSettings`, `httpupgradeSettings`, `hysteriaSettings` | object | no | — | Per-transport. |
| `sockopt` | object | no | — | `SocketConfig`, `infra/conf/transport_sockopt.go:45-66`. Relevant here: `mark`, `interface`, `dialerProxy`, `tcpFastOpen` (bool **or** int, `:71-84`), `domainStrategy`, `happyEyeballs`, `tcpMptcp`. |
| `address`, `port`, `finalmask` | — | no | — | Out of scope for this report. |

`network` string → internal name (`infra/conf/transport_internet.go:16-42`), lower-cased:

| You write | Becomes | Note |
|---|---|---|
| `"raw"` or `"tcp"` | `tcp` | `raw` is the current name |
| `"xhttp"` or `"splithttp"` | `splithttp` | |
| `"kcp"` or `"mkcp"` | `mkcp` | |
| `"grpc"` | `grpc` | prints a deprecation warning (`:25`) |
| `"ws"` or `"websocket"` | `websocket` | prints a deprecation warning (`:28`) |
| `"httpupgrade"` | `httpupgrade` | prints a deprecation warning (`:31`) |
| `"hysteria"` | `hysteria` | |
| `"h2"`, `"h3"`, `"http"` | **error** | removed feature (`:33-34`) |
| `"quic"` | **error** | removed feature (`:35-36`) |

**REALITY is restricted by transport:** only `tcp` (raw), `splithttp` (xhttp) and `grpc` are allowed;
anything else errors `REALITY only supports RAW, XHTTP and gRPC for now.` (`infra/conf/transport_internet.go:100-102`).

**Hard rule — no bare VLESS/Trojan to public hosts.** `validateOutboundTransportSecurity`
(`infra/conf/xray.go:245-266`) refuses to build a VLESS outbound whose `streamSettings` has no security
type *and* whose `settings.encryption` is empty-or-`"none"`, unless the server address is a private IP or
private domain: `vless without TLS or other encryption is prohibited unless the server address is a
private IP or domain` (`:255`). The same applies to Trojan (`:259-262`). Note the check reads
`vlessCfg.Address` — the **flat** form's address (see §5); with the `vnext` form `Address` is nil so
`requiresTransportSecurity` returns false (`infra/conf/xray.go:235-237`) and the check does not fire.

**sing-box mismatch:** sing-box has no `streamSettings`. Its `transport: {type: "ws"|"grpc"|...}` and its
`tls: {enabled, server_name, alpn, insecure, utls, reality}` are both siblings of the protocol fields.
In Xray both live under `streamSettings`, and TLS/REALITY are mutually exclusive via the single `security`
string rather than by two independent booleans.

---

## 3. TLS — `streamSettings.tlsSettings`

Struct: `TLSConfig`, `infra/conf/transport_security.go:300-319`. Builder: `:322-407`.

| Field | JSON type | Required | Default | Meaning |
|---|---|---|---|---|
| `serverName` | string | no | `""` → the dial destination address | SNI. When empty, `WithDestination` fills it from the destination (`transport/internet/tls/config.go:492-498`). |
| `alpn` | string **or** array of string | no | unset | `StringList`: a JSON array, or one string split on `,` (`infra/conf/common.go:28-42`). Mapped to `NextProtocol` (`transport_security.go:336-338`). |
| `allowInsecure` | bool | no | `false` | **REMOVED.** `true` returns `The feature "allowInsecure" has been removed and migrated to "pinnedPeerCertSha256"(pcs) and "verifyPeerCertByName"(vcn)` (`transport_security.go:361-363`); `PrintRemovedFeatureError` returns a real error (`common/errors/feature_errors.go:25-31`), so the config does not load. |
| `fingerprint` | string | no | `""` → uTLS `HelloChrome_Auto` | uTLS ClientHello. Lower-cased (`:354`). Validated: must be `"unsafe"` or resolve via `GetFingerprint`, else `unknown "fingerprint"` (`:355-357`). See the value list in §4 — the same table serves TLS and REALITY. |
| `pinnedPeerCertSha256` | string | no | unset | Comma-separated hex SHA-256s (colons stripped), each exactly 32 bytes (`:364-380`). Setting it turns on `InsecureSkipVerify` internally and pins instead (`transport/internet/tls/config.go:400-403`). This is one of the two `allowInsecure` replacements. |
| `verifyPeerCertByName` | string | no | unset | Comma-separated names; verification switches to name-based (`:381-389`, `transport/internet/tls/config.go:395-398`). The other `allowInsecure` replacement. |
| `certificates` | array | no | `[]` | `TLSCertConfig` (`:248-257`): `certificateFile`, `certificate` (array of lines), `keyFile`, `key`, `usage` (`encipherment`\|`verify`\|`issue`, default `encipherment`, `:279-288`), `ocspStapling`, `oneTimeLoading`, `buildChain`. |
| `enableSessionResumption` | bool | no | `false` | `:349`. |
| `disableSystemRoot` | bool | no | `false` | `:350`. |
| `minVersion` / `maxVersion` | string | no | `""` | `:351-352`. |
| `cipherSuites` | string | no | `""` | `:353`. |
| `curvePreferences` | string or array | no | unset | `StringList` (`:346-348`). |
| `rejectUnknownSni` | bool | no | `false` | Server-side (`:358`). |
| `masterKeyLog` | string | no | `""` | SSLKEYLOG path (`:359`). |
| `echConfigList`, `echServerKeys`, `echSockopt` | string/string/object | no | unset | ECH (`:391-405`). |

### Fingerprint dispatch at dial time — read this carefully

`transport/internet/tcp/dialer.go:76-85` (the same pattern is in
`websocket/dialer.go:79`, `httpupgrade/dialer.go:69`, `splithttp/dialer.go:140`, `grpc/dial.go:140`):

```go
if fingerprint := tls.GetFingerprint(config.Fingerprint); fingerprint != nil {
    conn = tls.UClient(conn, tlsConfig, fingerprint)   // uTLS
    ...
} else {
    conn = tls.Client(conn, tlsConfig)                 // Go crypto/tls
    ...
}
```

and `GetFingerprint("")` returns `&utls.HelloChrome_Auto` (`transport/internet/tls/tls.go:188-190`).

Consequences:

* **Omitting `fingerprint` gives you uTLS with a Chrome ClientHello**, not Go's standard TLS stack.
* The **only** way to get Go's standard `crypto/tls` is `"fingerprint": "unsafe"` —
  `PresetFingerprints["unsafe"]` is `nil` (`tls.go:216`) and the name is in none of the other two maps,
  so `GetFingerprint` returns nil and the `else` branch runs. `"unsafe"` is special-cased past the
  validator at `transport_security.go:355`.

**sing-box mismatch:** sing-box models this as `tls.utls.enabled` (bool) + `tls.utls.fingerprint` (string),
default **off**. Xray has no boolean: uTLS is on unless you explicitly say `"unsafe"`. Do not translate
sing-box's `utls.enabled:false` to "omit fingerprint" — that produces the opposite behavior.

---

## 4. REALITY — `streamSettings.realitySettings` (client side)

Struct: `REALITYConfig`, `infra/conf/transport_security.go:27-52`. Builder: `:54-246`.

**One struct, two modes.** The builder branches on whether `dest`/`target` is present:

* `c.Target != nil` → copied into `c.Dest` (`:59-61`); `c.Dest != nil` → **server** branch (`:62-178`),
  which requires `serverNames`, `privateKey`, `shortIds`.
* `c.Dest == nil` → **client** branch (`:179-244`).

So a client config must contain **no** `dest` and **no** `target`.

### Client fields

| Field | JSON type | Required | Default | Meaning |
|---|---|---|---|---|
| `publicKey` | string | **yes** | — | Server's X25519 public key, base64 **RawURL** (no padding, `-`/`_` alphabet), decoding to exactly **32 bytes** (`:196-198`). `password` is an accepted alias and overwrites `publicKey` when non-empty (`:190-192`). Missing → error text `empty "password"` (`:193-195`) — note the message names `password` even if you meant `publicKey`. |
| `shortId` | string | no | `""` → 8 zero bytes | Hex, **max 16 characters** (`:202-204`). Decoded with `hex.Decode` into a fixed 8-byte buffer (`:205-208`), so a shorter value is right-padded with zeros and an empty value yields all zeros. Odd-length or non-hex → `invalid "shortId"`. |
| `spiderX` | string | no | `"/"` | Must start with `/` (`:214-219`). Parsed as a URL; query params `p`,`c`,`t`,`i`,`r` (each `N` or `MIN-MAX`) are stripped into `SpiderY[0..9]` = padding/concurrency/times/interval/return (`:220-242`). |
| `serverName` | string | no | `""` → the dial destination address | SNI sent in the forged ClientHello. `reality.UClient` fills it from `dest.Address.String()` when empty (`transport/internet/reality/reality.go:129-131`). |
| `fingerprint` | string | no | `""` → `HelloChrome_Auto` | Lower-cased (`:180`). **`"unsafe"` and `"hellogolang"` are rejected outright** (`:181-183`); an unresolvable name gives `unknown "fingerprint"` (`:184-186`). At dial time a nil fingerprint is fatal: `REALITY: failed to get fingerprint` (`transport/internet/reality/reality.go:133-136`). The chosen ClientHello **must support TLS 1.3** or the handshake aborts with `Current fingerprint ... does not support TLS 1.3, REALITY handshake cannot establish.` (`reality.go:161`). |
| `mldsa65Verify` | string | no | unset | base64 RawURL, exactly **1952 bytes** decoded (`:209-213`). Post-quantum server-identity verification. |
| `show` | bool | no | `false` | Prints handshake internals to stdout (`:57`, `reality.go:150,172,181`). |
| `masterKeyLog` | string | no | `""` | `:56`. |
| `serverNames` (plural) | array | **must be absent** | — | `non-empty "serverNames", please use "serverName" instead` (`:187-189`). |
| `shortIds` (plural) | array | **must be absent** | — | `non-empty "shortIds", please use "shortId" instead` (`:199-201`). |

Server-only fields, listed so you know **not** to emit them from a client: `dest`/`target`, `type`, `xver`,
`serverNames`, `privateKey`, `minClientVer`, `maxClientVer`, `maxTimeDiff`, `shortIds`, `mldsa65Seed`,
`limitFallbackUpload`, `limitFallbackDownload` (`:30-43`).

### Accepted `fingerprint` values — verbatim from `transport/internet/tls/tls.go`

Lookup order (`tls.go:187-201`): empty string → `HelloChrome_Auto`; then `PresetFingerprints`, then
`ModernFingerprints`, then `OtherFingerprints`; miss → `nil`.

`PresetFingerprints` (`tls.go:203-217`) — "Recommended preset options in GUI clients":

```
chrome            firefox           safari            ios
android           edge              360               qq
random            randomized        randomizednoalpn  unsafe
```

`random`, `randomized`, `randomizednoalpn` are stored as `nil` in the literal but are populated in `init()`
(`tls.go:163-185`): `random` is one entry of `ModernFingerprints` picked at process start;
`randomized`/`randomizednoalpn` are uTLS randomized hellos with `TLSVersMax_Set_VersionTLS13 = 1` and
`FirstKeyShare_Set_CurveP256 = 0`. **`unsafe` stays `nil`** and therefore means "use Go's `crypto/tls`" for
TLS, and is a hard error for REALITY.

`ModernFingerprints` (`tls.go:219-232`) — the pool `random` draws from:

```
hellofirefox_120   hellofirefox_148   hellochrome_120   hellochrome_131
hellochrome_133    helloios_13        helloios_14       helloedge_106
hellosafari_26_3   hello360_11_0      helloqq_11_1
```

`OtherFingerprints` (`tls.go:234-278`) — "Golang, randomized, auto, and fingerprints that are too old":

```
hellogolang               hellorandomized           hellorandomizedalpn      hellorandomizednoalpn
hellofirefox_auto         hellofirefox_55           hellofirefox_56          hellofirefox_63
hellofirefox_65           hellofirefox_99           hellofirefox_102         hellofirefox_105
hellochrome_auto          hellochrome_58            hellochrome_62           hellochrome_70
hellochrome_72            hellochrome_83            hellochrome_87           hellochrome_96
hellochrome_100           hellochrome_102           hellochrome_106_shuffle  helloios_auto
helloios_11_1             helloios_12_1             helloandroid_11_okhttp   helloedge_85
helloedge_auto            hellosafari_16_0          hellosafari_auto         hello360_auto
hello360_7_5              helloqq_auto
hellochrome_100_psk       hellochrome_112_psk_shuf  hellochrome_114_padding_psk_shuf
hellochrome_115_pq        hellochrome_115_pq_psk    hellochrome_120_pq
```

Matching is exact on the **lower-cased** string (`transport_security.go:180` and `:354`), so `"Chrome"` is
fine but `"chrome_133"` is not a key.

**What happens when `fingerprint` is omitted:** it is `""`, which passes validation
(`GetFingerprint("")` is non-nil) and resolves to `utls.HelloChrome_Auto` both for TLS
(`tls.go:188-190` → `tcp/dialer.go:76`) and for REALITY (`reality.go:133`). It is *not* an error, and it is
*not* "no fingerprinting".

---

## 5. VLESS — `infra/conf/vless.go`

Struct: `VLessOutboundConfig`, `vless.go:245-258`; nested `VLessOutboundVnext`, `vless.go:239-243`.
Builder: `vless.go:261-386`.

```go
type VLessOutboundVnext struct {
	Address *Address          `json:"address"`
	Port    uint16            `json:"port"`
	Users   []json.RawMessage `json:"users"`
}

type VLessOutboundConfig struct {
	Address    *Address              `json:"address"`
	Port       uint16                `json:"port"`
	Level      uint32                `json:"level"`
	Email      string                `json:"email"`
	Id         string                `json:"id"`
	Flow       string                `json:"flow"`
	Seed       string                `json:"seed"`
	Encryption string                `json:"encryption"`
	Reverse    *VLessReverseConfig   `json:"reverse"`
	Testpre    uint32                `json:"testpre"`
	Testseed   []uint32              `json:"testseed"`
	Vnext      []*VLessOutboundVnext `json:"vnext"`
}
```

### Two accepted shapes

**(a) `vnext` (classic).** `settings.vnext` must have **exactly one** member
(`vless.go:272-274`: `"vnext" should have one and only one member`), and that member's `users` must have
**exactly one** member (`vless.go:279-281`). Multiple servers → multiple outbounds + a routing balancer.

**(b) flat / "simplified" (newer).** If `settings.address` is non-nil, the builder *synthesizes* the
`vnext` entry from the top-level `address`/`port` and takes `id`/`flow`/`encryption`/`level`/`email`/
`reverse` from the top level, ignoring any `users` you wrote (`vless.go:263-271`, `:288-310`).
Both shapes are exercised by `infra/conf/vless_test.go:22-84`.

Setting `settings.address` **and** `settings.vnext` at once silently discards `vnext` — the assignment at
`vless.go:264` overwrites it.

### Where `flow` lives

* **`vnext` form:** `outbounds[i].settings.vnext[0].users[0].flow`.
  The raw user object is unmarshalled twice — once into `protocol.User` for `level`/`email`
  (`vless.go:292`) and once into `vless.Account` for `id`/`flow`/`encryption` (`vless.go:312`).
  The JSON keys of `vless.Account` come from the generated protobuf tags
  (`proxy/vless/account.pb.go:80-89`, schema `proxy/vless/account.proto:16-31`):
  `id`, `flow`, `encryption`, `xorMode`, `seconds`, `padding`, `reverse`, `testpre`, `testseed`.
* **flat form:** `outbounds[i].settings.flow` (`vless.go:251`, consumed at `vless.go:299`).

It is **never** at `outbounds[i].settings.flow` when you use `vnext`, and never at the outbound top level.

### Accepted `flow` values (outbound)

`vless.go:326-331`:

```go
switch account.Flow {
case "":
case vless.XRV, vless.XRV + "-udp443":
default:
    return nil, errors.New(`VLESS users: "flow" doesn't support "` + account.Flow + `" in this version`)
}
```

with `XRV = "xtls-rprx-vision"` (`proxy/vless/vless.go:10`). So exactly three values:

| Value | Meaning |
|---|---|
| `""` (or key omitted) | No Vision. |
| `"xtls-rprx-vision"` | Vision. UDP to port 443 is **rejected** at runtime: `XTLS rejected UDP/443 traffic` (`proxy/vless/outbound/outbound.go:260-262`). |
| `"xtls-rprx-vision-udp443"` | Vision, but UDP/443 allowed; the suffix is trimmed before the flow is put on the wire (`proxy/vless/outbound/outbound.go:252-255`). |

The **inbound** side accepts only `""` and `"xtls-rprx-vision"` — not the `-udp443` variant
(`vless.go:50-54`, `:72-78`). Do not mirror the client value into a server config.

Vision requires a **TLS 1.3** outer layer: if the outer conn is `tls.Conn`/`tls.UConn` and the negotiated
version is not TLS 1.3, the connection fails with `failed to use xtls-rprx-vision, found outer tls version`
(`proxy/vless/outbound/outbound.go:358-368`). In practice: pair Vision with `security: "tls"` or
`security: "reality"`.

### `encryption` is mandatory

`vless.go:333-375`. The value must be either the literal `"none"` or a `mlkem768x25519plus.…` string.
Empty gives `VLESS users: please add/set "encryption":"none" for every user` (`:371-373`); anything else
gives `VLESS settings: unsupported "encryption"`. The post-quantum form is
`mlkem768x25519plus.<native|xorpub|random>.<1rtt|0rtt>[.<padding>...].<base64 key>` where each dotted
segment of ≥20 chars must base64-RawURL-decode to 32 or 1184 bytes (`:334-369`). `xorpub` sets XorMode 1,
`random` sets 2 (`:338-346`); `0rtt` sets `Seconds = 1` (`:347-353`).

`seed` is present in the struct (`vless.go:252`) but the builder line that would use it is
**commented out** (`vless.go:300`: `//account.Seed = c.Seed`), so it is currently a no-op on the client.

| Field (flat form) | JSON type | Required | Default | Meaning |
|---|---|---|---|---|
| `address` | string | yes (for the flat form) | — | Server host. `Address` unmarshals from a string only; a `env:NAME` prefix is expanded (`infra/conf/common.go:54-65`). |
| `port` | number (uint16) | yes | `0` | Server port. |
| `id` | string | **yes** | — | UUID. Parsed by `uuid.ParseString` and re-serialized (`vless.go:320-324`). **A non-UUID string of length 1–30 is silently mapped to a UUID** via SHA-1 over the text (`common/uuid/uuid.go:71-83`), so a typo'd id does not fail the config — it produces a valid-looking but wrong user. Length 0, or 31, or >36 → `invalid UUID` (`:71-74`). |
| `flow` | string | no | `""` | See above. |
| `encryption` | string | **yes** | — | `"none"` or `mlkem768x25519plus.…`. |
| `level` | number | no | `0` | User level (policy). |
| `email` | string | no | `""` | Stats/log label. |
| `reverse` | object | no | — | `{"tag": "...", "sniffing": {...}}` (`vless.go:217-220`); **only** usable in the flat form (`vless.go:315-317`). |
| `testpre`, `testseed`, `seed` | number/array/string | no | — | Experimental; `seed` is inert. |

### Minimal VLESS + REALITY + Vision client (copy-pasteable)

```json
{
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "example.com",
            "port": 443,
            "users": [
              {
                "id": "27848739-7e62-4138-9fd3-098a63964b6b",
                "flow": "xtls-rprx-vision",
                "encryption": "none"
              }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "raw",
        "security": "reality",
        "realitySettings": {
          "serverName": "www.cloudflare.com",
          "fingerprint": "chrome",
          "publicKey": "xbnPHkPHaP-Q5oT_1RmTHKMLwjRUcbnJoRnJdvQ3rV8",
          "shortId": "0123abcd",
          "spiderX": "/"
        }
      }
    }
  ]
}
```

### Minimal VLESS + TLS + Vision over WebSocket

```json
{
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "vless",
      "settings": {
        "address": "example.com",
        "port": 443,
        "id": "27848739-7e62-4138-9fd3-098a63964b6b",
        "flow": "",
        "encryption": "none"
      },
      "streamSettings": {
        "network": "ws",
        "security": "tls",
        "tlsSettings": {
          "serverName": "example.com",
          "alpn": ["h2", "http/1.1"],
          "fingerprint": "chrome"
        },
        "wsSettings": { "path": "/ws" }
      }
    }
  ]
}
```

(Vision over WebSocket is pointless — the TLS-1.3 check at `outbound.go:358` only inspects the outer conn —
so `flow` is empty here. Use the flat form or `vnext`; both are accepted.)

**sing-box mismatch, VLESS:** sing-box uses `uuid`; Xray uses `id`. sing-box puts `flow` at the outbound
top level next to `uuid`; Xray puts it inside `settings.vnext[0].users[0]` (or `settings` in the flat form).
sing-box has no `encryption` field at all; Xray **requires** `"encryption": "none"`. sing-box's
`server`/`server_port` are Xray's `address`/`port` **inside settings**.

---

## 6. Shadowsocks — `infra/conf/shadowsocks.go`

Client struct: `ShadowsocksClientConfig`, `shadowsocks.go:188-196`; per-server
`ShadowsocksServerTarget`, `:179-186`. Builder: `:198-276`.

```go
type ShadowsocksServerTarget struct {
	Address  *Address `json:"address"`
	Port     uint16   `json:"port"`
	Level    byte     `json:"level"`
	Email    string   `json:"email"`
	Cipher   string   `json:"method"`
	Password string   `json:"password"`
}
```

Like VLESS, both a flat form (`settings.address` present → a one-element `servers` is synthesized,
`:201-212`) and a `servers` array are accepted, and `servers` must have **exactly one** member
(`:213-215`). Building a Shadowsocks outbound always prints a deprecation warning
(`shadowsocks.go:199`).

| Field | JSON type | Required | Default | Meaning |
|---|---|---|---|---|
| `address` | string | **yes** | — | `Shadowsocks server address is not set.` (`:245-247`) |
| `port` | number | **yes**, non-zero | — | `Invalid Shadowsocks port.` (`:248-250`) |
| `method` | string | **yes** | — | See the two tables below. |
| `password` | string | **yes** | — | `Shadowsocks password is not specified.` (`:251-253`). For 2022 methods this is the **base64 PSK**, stored as `key` (`:234`). |
| `level` | number | no | `0` | `:264`. Ignored on the 2022 path. |
| `email` | string | no | `""` | `:265`. Ignored on the 2022 path. |

### Classic AEAD method names — verbatim from `cipherFromString`, `shadowsocks.go:17-30`

Input is **lower-cased** first (`:18`), so case does not matter here.

| Accepted spellings | Cipher |
|---|---|
| `aes-128-gcm`, `aead_aes_128_gcm` | AES-128-GCM |
| `aes-256-gcm`, `aead_aes_256_gcm` | AES-256-GCM |
| `chacha20-poly1305`, `aead_chacha20_poly1305`, `chacha20-ietf-poly1305` | ChaCha20-Poly1305 |
| `xchacha20-poly1305`, `aead_xchacha20_poly1305`, `xchacha20-ietf-poly1305` | XChaCha20-Poly1305 |

Anything else → `CipherType_UNKNOWN` → `unknown cipher method: <x>` (`:257-259`).
There is **no** `none`/`plain` and no stream cipher (`aes-256-cfb`, `rc4-md5`, …) on this list.

### 2022-series methods

Dispatch is `C.Contains(shadowaead_2022.List, server.Cipher)` (`shadowsocks.go:219`), i.e. an
**exact, case-sensitive** membership test against a list owned by
`github.com/sagernet/sing-shadowsocks v0.2.7` (`go.mod:21`). A match routes to
`shadowsocks_2022.ClientConfig{Address, Port, Method, Key}` (`shadowsocks.go:230-235`, proto at
`proxy/shadowsocks_2022/config.proto:47-52`), and at runtime the key is handed to
`shadowaead_2022.NewWithPassword(config.Method, config.Key, nil)`
(`proxy/shadowsocks_2022/outbound.go:45-53`), erroring with `missing psk` if `key` is empty (`:46-48`).

**UNVERIFIED — the literal 2022 method strings.** The `shadowaead_2022.List` slice is in an external
module and there is no module cache or vendor directory on this machine (`go` is not installed;
`grep -rn "blake3"` over the repo returns only `lukechampine.com/blake3` and the error string at
`infra/conf/shadowsocks.go:127`). To confirm the exact strings, read `List` in
`<GOMODCACHE>/github.com/sagernet/sing-shadowsocks@v0.2.7/shadowaead_2022/`. What the Xray source *does*
prove about them:

* The 2022 method string is **not** lower-cased before the membership test (`shadowsocks.go:219`), unlike
  classic methods. Emit whatever casing the upstream list uses, exactly.
* At least one member contains the substring `aes` and at least one does not: the multi-user *server*
  path rejects non-`aes` methods with `shadowsocks 2022 (multi-user): only blake3-aes-*-gcm methods are
  supported` (`shadowsocks.go:126-128`), which also tells you the names contain `blake3` and `-gcm`.
* A 2022 method may not be combined with more than one server: `Shadowsocks 2022 accept no multi servers`
  (`shadowsocks.go:241-243`).

**UNVERIFIED — multi-user 2022 PSK format on the client.** `password` is passed verbatim as the PSK
(`shadowsocks.go:234` → `outbound.go:49`). Whether a `serverPSK:userPSK` colon form is split is decided
inside sing's `NewWithPassword`, which I could not read. Check the same module path above.

### Minimal Shadowsocks outbound

```json
{
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "shadowsocks",
      "settings": {
        "servers": [
          {
            "address": "example.com",
            "port": 8388,
            "method": "aes-256-gcm",
            "password": "your-password"
          }
        ]
      }
    }
  ]
}
```

The flat equivalent (`"settings": {"address": "...", "port": 8388, "method": "...", "password": "..."}`)
is identical after `shadowsocks.go:201-212`.

**sing-box mismatch:** sing-box spells these `{"type":"shadowsocks","server":...,"server_port":...,
"method":...,"password":...}` at the outbound top level. In Xray they must be inside `settings`, and
Xray does not accept sing-box's `plugin`/`plugin_opts`, `network`, or `multiplex` keys on a Shadowsocks
outbound — unknown keys are silently dropped by `encoding/json`, so a `plugin` you forget to remove
becomes a tunnel that connects and carries nothing.

---

## 7. Trojan — `infra/conf/trojan.go`

Client struct: `TrojanClientConfig`, `trojan.go:31-39`; per-server `TrojanServerTarget`, `:21-28`.
Builder: `:42-93`. Building one prints a deprecation warning (`trojan.go:43`).

```go
type TrojanServerTarget struct {
	Address  *Address `json:"address"`
	Port     uint16   `json:"port"`
	Level    byte     `json:"level"`
	Email    string   `json:"email"`
	Password string   `json:"password"`
	Flow     string   `json:"flow"`
}
```

Same dual shape: `settings.address` present → a one-element `servers` is synthesized (`:45-56`);
`servers` must have exactly one member (`:57-59`).

| Field | JSON type | Required | Default | Meaning |
|---|---|---|---|---|
| `address` | string | **yes** | — | `Trojan server address is not set.` (`:64-66`) |
| `port` | number | **yes**, non-zero | — | `Invalid Trojan port.` (`:67-69`) |
| `password` | string | **yes** | — | `Trojan password is not specified.` (`:70-72`) |
| `level` | number | no | `0` | `:81` |
| `email` | string | no | `""` | `:82` |
| `flow` | string | **must be empty** | `""` | Any non-empty value is a hard error: `The feature Flow for Trojan has been removed.` (`:73-75`). |

Trojan carries no security of its own — the `validateOutboundTransportSecurity` rule at
`infra/conf/xray.go:259-262` refuses a Trojan outbound to a public address with no `security` set.

### Minimal Trojan outbound

```json
{
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "trojan",
      "settings": {
        "servers": [
          { "address": "example.com", "port": 443, "password": "your-password" }
        ]
      },
      "streamSettings": {
        "network": "raw",
        "security": "tls",
        "tlsSettings": { "serverName": "example.com", "fingerprint": "chrome" }
      }
    }
  ]
}
```

**sing-box mismatch:** sing-box's Trojan outbound has `password` at the top level and `tls` as a sibling.
Xray needs `settings.servers[0].password` plus `streamSettings.security = "tls"`. sing-box permits
`flow: "xtls-rprx-vision"` on Trojan in some builds; Xray **rejects any non-empty `flow`**.

---

## 8. freedom and blackhole

### freedom (aliases: `freedom`, `direct`)

Registered under both ids at `infra/conf/xray.go:41-42`. Struct: `FreedomConfig`,
`infra/conf/freedom.go:19-30`. Builder: `:55-194`.

| Field | JSON type | Required | Default | Meaning |
|---|---|---|---|---|
| `domainStrategy` | string | no | `"asis"` | Resolution strategy. **`targetStrategy` is the new name and takes precedence** — `domainStrategy` is only consulted when `targetStrategy` is empty (`freedom.go:62-65`). |
| `targetStrategy` | string | no | `""` | Same value set; see below. |
| `redirect` | string | no | `""` | `host:port`; empty host allowed, port required (`:161-179`). |
| `userLevel` | number | no | `0` | `:159`. |
| `fragment` | object | no | — | `{packets, length, interval, maxSplit}` (`:32-37`). `packets` is `"tlshello"`, `""`, or a `from-to` range (`:96-116`); `length` and `interval` are **required** when `fragment` is present (`:119-135`). |
| `noises` | array of object | no | — | `[{type, packet, delay, applyTo}]` (`:39-44`). `type` ∈ `rand`\|`str`\|`hex`\|`base64` (`:201-233`); `applyTo` ∈ `""`\|`ip`\|`all`\|`ipv4`\|`ipv6`, normalized to `ip` (`:239-248`). |
| `noise` (singular) | object | **must be absent** | — | Removed: `noise = { ... }` → use `noises = [ { ... } ]` (`:145-147`). |
| `proxyProtocol` | number | no | `0` | Only 1 or 2 are applied (`:181-183`). |
| `finalRules` | array | no | — | `[{action, network, port, ip, blockDelay}]` (`:46-52`); `action` ∈ `allow`\|`block` (`:255-262`). |
| `ipsBlocked` | array | **removed** | — | Only logs a warning and is ignored (`:56-59`) — it does **not** error, so a stale field here silently stops blocking. |

**`domainStrategy` / `targetStrategy` accepted values, verbatim** (`freedom.go:66-91`, compared after
`strings.ToLower`):

```
asis  ""  useip  useipv4  useipv6  useipv4v6  useipv6v4
forceip  forceipv4  forceipv6  forceipv4v6  forceipv6v4
```

Anything else → `unsupported domain strategy: <x>` (`:89-90`). The identical list appears on the outbound
envelope's `targetStrategy` (`infra/conf/xray.go:271-296`).

### blackhole (aliases: `blackhole`, `block`)

Registered under both ids at `infra/conf/xray.go:38-39`. Struct: `BlackholeConfig`,
`infra/conf/blackhole.go:24-26`.

| Field | JSON type | Required | Default | Meaning |
|---|---|---|---|---|
| `response` | object | no | absent → `NoneResponse` | `{"type": "none"}` or `{"type": "http"}`; the discriminator key is `type` (`blackhole.go:45-52`). Unknown type → `unknown config id`. |

`none` writes nothing (`proxy/blackhole/config.go:25`); `http` writes a fixed `HTTP/1.1 403 Forbidden`
(`proxy/blackhole/config.go:9-15`, `:28-34`) and then sleeps 1s before closing
(`proxy/blackhole/blackhole.go:41-44`). A nil `response` becomes `NoneResponse`
(`proxy/blackhole/config.go:37-40`).

### Minimal direct + reject pair

```json
{
  "outbounds": [
    {
      "tag": "direct",
      "protocol": "freedom",
      "settings": { "domainStrategy": "UseIP" }
    },
    {
      "tag": "block",
      "protocol": "blackhole",
      "settings": { "response": { "type": "none" } }
    }
  ]
}
```

Both `settings` blocks may be omitted entirely — an absent `settings` is parsed as `{}`
(`infra/conf/xray.go:353-356`), giving `domainStrategy: "asis"` and a silent blackhole.

**sing-box mismatch:** sing-box's equivalents are `{"type":"direct"}` and `{"type":"block"}` and its
`direct` outbound has `domain_strategy` with values `prefer_ipv4`/`prefer_ipv6`/`ipv4_only`/`ipv6_only`.
**None of those strings are valid in Xray** — `prefer_ipv4` → `unsupported domain strategy`. The nearest
mappings are `useipv4v6` (prefer v4), `useipv6v4` (prefer v6), `useipv4`, `useipv6`; the `force*` family
has no sing-box counterpart (it makes resolution failure fatal rather than falling back to AsIs).

---

## 9. Full worked client config

```json
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "tag": "socks-in",
      "protocol": "socks",
      "listen": "127.0.0.1",
      "port": 10808,
      "settings": { "udp": true },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls"] }
    }
  ],
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "example.com",
            "port": 443,
            "users": [
              {
                "id": "27848739-7e62-4138-9fd3-098a63964b6b",
                "flow": "xtls-rprx-vision",
                "encryption": "none",
                "level": 0,
                "email": "phone@local"
              }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "raw",
        "security": "reality",
        "realitySettings": {
          "serverName": "www.cloudflare.com",
          "fingerprint": "chrome",
          "publicKey": "xbnPHkPHaP-Q5oT_1RmTHKMLwjRUcbnJoRnJdvQ3rV8",
          "shortId": "0123abcd",
          "spiderX": "/"
        },
        "sockopt": { "mark": 255 }
      },
      "mux": { "enabled": false }
    },
    { "tag": "direct", "protocol": "freedom", "settings": { "domainStrategy": "UseIP" } },
    { "tag": "block", "protocol": "blackhole" }
  ]
}
```

The inbound half is included only so the file is runnable; it was not part of this review. Its fields:
`InboundDetourConfig` (`infra/conf/xray.go:126-134`), `SniffingConfig` (`infra/conf/xray.go:55-62`, with
`destOverride` values `http`/`tls`\|`https`\|`ssl`/`quic`/`fakedns`\|`fakedns+others` at `:68-79`),
socks `udp` (`infra/conf/socks.go:34`), and `log.loglevel` (`infra/conf/log.go:21`).

---

## 10. UNVERIFIED / open items

1. **The literal Shadowsocks-2022 method strings.** Owned by `sing-shadowsocks v0.2.7`; not present in
   this repo and no Go module cache exists on this machine. Read `shadowaead_2022.List` in
   `<GOMODCACHE>/github.com/sagernet/sing-shadowsocks@v0.2.7/shadowaead_2022/`.
2. **Whether a client `password` for a multi-user 2022 server must be `serverPSK:userPSK`.** Decided
   inside `shadowaead_2022.NewWithPassword`; same module.
3. **Per-transport settings bodies** (`wsSettings`, `xhttpSettings`, `grpcSettings`, `kcpSettings`,
   `rawSettings`, `hysteriaSettings`) were out of scope; only their JSON keys and dispatch are verified
   here (`infra/conf/transport_internet.go:44-63`, `:119-194`). Their structs are all in
   `infra/conf/transport_method.go`: `TCPConfig:232`, `SplitHTTPConfig:257`, `XmuxConfig:290`,
   `KCPConfig:523`, `GRPCConfig:578`, `WebSocketConfig:613`, `HttpUpgradeConfig:655`,
   `HysteriaConfig:761`. (`wsSettings.path` used in the §5 example *is* verified:
   `infra/conf/transport_method.go:615`; the sibling keys are `host`, `headers`, `acceptProxyProtocol`,
   `heartbeatPeriod`, and an `?ed=N` query in `path` is extracted as early-data (`:623-630`).)
4. **Runtime UDP behavior of the classic Shadowsocks outbound** (whether `network` must be declared
   anywhere on the client) — `shadowsocks.ClientConfig` has no `network` field
   (`infra/conf/shadowsocks.go:239-273`), but I did not trace `proxy/shadowsocks/client.go`.
