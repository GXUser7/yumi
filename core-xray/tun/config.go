// Package tun implements a guarded TUN inbound for Xray-core backed by gVisor netstack.
//
// Every inbound TCP SYN and UDP 5-tuple flow (including DNS) is vetted by [TrafficGuard]
// before a connection or forwarding session is created, and ICMPv4/ICMPv6 echo requests are
// suppressed at the link dispatcher boundary when a guard is active.
package tun

import (
	"net/netip"
	"reflect"

	"github.com/xtls/xray-core/common/serial"
	xtun "github.com/xtls/xray-core/proxy/tun"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// TrafficGuard vets connections entering the TUN interface before they are forwarded.
//
// Implementations must be safe for concurrent use across goroutines.
type TrafficGuard interface {
	AllowConnection(proto int, srcIP []byte, srcPort int, dstIP []byte, dstPort int) bool
}

// NormalizeTrafficGuard converts typed-nil TrafficGuard values (for example, (*MyGuard)(nil))
// into untyped nil so `guard != nil` checks never invoke AllowConnection on a nil receiver.
func NormalizeTrafficGuard(g TrafficGuard) TrafficGuard {
	if g == nil {
		return nil
	}
	v := reflect.ValueOf(g)
	switch v.Kind() {
	case reflect.Ptr, reflect.Interface, reflect.Slice, reflect.Map, reflect.Chan, reflect.Func:
		if v.IsNil() {
			return nil
		}
	}
	return g
}

// GuardedConfig configures the guarded TUN inbound instantiated via core.CreateObject.
type GuardedConfig struct {
	Tag              string
	ReceiverSettings *serial.TypedMessage
	ProxySettings    *serial.TypedMessage
	TunConfig        *xtun.Config
	TunFD            int
	// Endpoint optionally injects a pre-built LinkEndpoint (e.g., gVisor channel.Endpoint in tests).
	// When nil, NewEndpointFromFD(TunFD, mtu) is used on Android/Linux.
	Endpoint stack.LinkEndpoint
	Guard    TrafficGuard
}

// normalizeAddr validates a gVisor tcpip.Address, unmaps IPv4-mapped IPv6 addresses
// (::ffff:a.b.c.d -> 4-byte IPv4), and returns a freshly allocated 4- or 16-byte slice
// that never aliases gVisor internal storage and is never nil or empty.
func normalizeAddr(addr tcpip.Address) ([]byte, netip.Addr, bool) {
	raw := addr.AsSlice()
	if len(raw) != 4 && len(raw) != 16 {
		return nil, netip.Addr{}, false
	}
	parsed, ok := netip.AddrFromSlice(raw)
	if !ok {
		return nil, netip.Addr{}, false
	}
	unmapped := parsed.Unmap()
	if !unmapped.IsValid() || unmapped.IsUnspecified() {
		// Unspecified (0.0.0.0 / ::) is rejected fail-closed.
		return nil, netip.Addr{}, false
	}
	b := unmapped.AsSlice()
	if len(b) != 4 && len(b) != 16 {
		return nil, netip.Addr{}, false
	}
	out := make([]byte, len(b))
	copy(out, b)
	return out, unmapped, true
}
