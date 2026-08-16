# Handoff: sing-box startup crash and dead tunnel — resolved

Date: 2026-08-13. Supersedes both earlier documents (the previous handoff and
`implementation_plan.md`, now deleted). The tunnel connects and passes TCP and UDP
traffic through a real VLESS server, verified on a Pixel 9 Pro XL.

## The two real causes

Both earlier analyses named suspects that turned out to be wrong. What actually
broke it:

### 1. Native SIGABRT on connect — an IPv6 zone in an interface address

```
panic: netip.ParsePrefix("fe80::38dd:baff:fe7a:5fc%dummy0/64"):
       IPv6 zones cannot be present in a prefix
  libbox.(*platformInterfaceWrapper).NetworkInterfaces  service.go:139
```

`getInterfaces()` built each address as `hostAddress + "/" + prefixLength`. For a
link-local IPv6 address `InetAddress.getHostAddress()` appends the scope
(`%dummy0`), and the core parses every address with Go's `netip.MustParsePrefix`,
which **panics** on a zone rather than returning an error. Every Android device has
a link-local address, so this aborted the process on the first interface
enumeration, every time.

Fixed in [`interfaceCidr`](app/src/main/kotlin/com/mydrop/vpn/core/net/InterfaceAddress.kt),
kept free of Android types and covered by tests including the exact string from the
panic.

Note this bug was present in **both** versions of `getInterfaces()` — the rewritten
one and the one before it. Reverting the network-monitor work could never have
fixed it, which is why the crash survived that revert.

### 2. No traffic — the wrong TUN stack, plus tun0 reported as the default interface

Two separate faults, in order:

**`defaultInterface -> tun0`.** Once the TUN is up it becomes the system's default
network, so `registerDefaultNetworkCallback` reported our own tunnel and the core
was told to dial out through its own inbound: `dial UDP connection: no available
network interface` on every request. The monitor now subscribes with
`NET_CAPABILITY_NOT_VPN` and additionally rejects `TRANSPORT_VPN` in the handler;
`onLost` only clears the interface it actually reported.

**`"stack": "mixed"`.** That means the system stack for TCP and gVisor for UDP, and
the system stack needs to manipulate real kernel routes, which an unrooted Android
app cannot. The symptom fell exactly along that boundary: DNS resolved over UDP
through the proxy while every TCP connection stopped at `router: pre-match[0] =>
sniff` and never produced an outbound. Now pinned to `gvisor`, with a test so it
cannot drift back to a schema default.

## How the panic was found, and why the earlier attempts could not find it

The tombstone's only frame is `runtime.raise` — where the Go runtime kills itself
after a fatal error, never where it broke. Symbolizing it is possible (the AAR does
ship `.gopclntab`; `go tool addr2line` reads it if the tombstone `pc` is used as a
virtual address rather than having the APK mapping offset subtracted) but yields
nothing useful, because that frame is the same for every abort.

The reason is printed to **stderr**, which Android discards. `captureNativeStderr()`
in the service now dups fd 2 into a pipe that is pumped into logcat (`YumiCore`) and
the in-app journal. That is what produced the panic text above, and it stays in the
code. Core logs are mirrored to logcat for the same reason: the in-app journal is an
in-memory ring buffer that dies with the process.

`getInterfaces ->` and `defaultInterface ->` are also logged. They are what exposed
`tun0` being handed over as the default; the core's error text never says what it
was offered.

## Kept from the earlier work

- Direct DNS emitted with no `detour` (rejected otherwise by current libbox).
- Blank DNS fields falling back to defaults, and settings that no longer persist an
  invalid value mid-edit.
- Geo rule-sets bundled as assets and referenced as `type: local`, so startup makes
  no network request. This was a genuine second failure mode, not the crash.
- `requestNetwork` reverted to observation, `CHANGE_NETWORK_STATE` removed.

## Working with libbox

- `PlatformInterface` methods declaring `throws Exception` (`openTun`,
  `getInterfaces`, `startDefaultInterfaceMonitor`, …) convert a Kotlin exception into
  a Go error and are safe to throw from. Those that do not (`useProcFS`,
  `includeAllNetworks`, `readWIFIState`, `clearDNSCache`, `registerMyInterface`,
  `tailscaleHostname`, `usePlatform*`, `underNetworkExtension`, `localDNSTransport`)
  abort the process instead. Verify with `javap` against `app/libs/libbox.aar`.
- libbox iterators are one-shot. `options.inet6Address.hasNext()` after iterating it
  always answers false — that silently suppressed the IPv6 default route.
- Anything handed across the bridge is parsed by Go with `Must*` helpers in places.
  Malformed input is a process abort, not an exception.

## Still open

- `applicationId` remains `com.mydrop.vpn` although the app is now Yumi. Changing it
  makes the next build a different app to Android: no in-place update, and users lose
  their servers and settings.
- The tunnel has only been exercised against one VLESS/REALITY server.
