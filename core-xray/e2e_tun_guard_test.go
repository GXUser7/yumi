package yumi_test

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	yumi "github.com/GXUser7/yumi/core-xray"
	"github.com/GXUser7/yumi/core-xray/tun"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/inbound"
	"github.com/xtls/xray-core/features/routing"
	confserial "github.com/xtls/xray-core/infra/conf/serial"
	xtun "github.com/xtls/xray-core/proxy/tun"
	"github.com/xtls/xray-core/transport/internet"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/checksum"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// -----------------------------------------------------------------------------
// TrafficGuard & Protector Test Doubles (PROJECT.md § Interface Contract #1)
// -----------------------------------------------------------------------------

type guardCall struct {
	Proto   int
	SrcIP   []byte
	SrcPort int
	DstIP   []byte
	DstPort int
}

type recordingTrafficGuard struct {
	mu    sync.Mutex
	calls []guardCall
	allow func(proto int, srcIP []byte, srcPort int, dstIP []byte, dstPort int) bool
}

func newRecordingGuard(allow func(proto int, srcIP []byte, srcPort int, dstIP []byte, dstPort int) bool) *recordingTrafficGuard {
	return &recordingTrafficGuard{allow: allow}
}

func (g *recordingTrafficGuard) AllowConnection(proto int, srcIP []byte, srcPort int, dstIP []byte, dstPort int) bool {
	srcCopy := append([]byte(nil), srcIP...)
	dstCopy := append([]byte(nil), dstIP...)
	g.mu.Lock()
	g.calls = append(g.calls, guardCall{
		Proto:   proto,
		SrcIP:   srcCopy,
		SrcPort: srcPort,
		DstIP:   dstCopy,
		DstPort: dstPort,
	})
	fn := g.allow
	g.mu.Unlock()
	if fn == nil {
		return true
	}
	return fn(proto, srcCopy, srcPort, dstCopy, dstPort)
}

func (g *recordingTrafficGuard) Calls() []guardCall {
	g.mu.Lock()
	defer g.mu.Unlock()
	out := make([]guardCall, len(g.calls))
	copy(out, g.calls)
	return out
}

func (g *recordingTrafficGuard) CountProto(proto int) int {
	g.mu.Lock()
	defer g.mu.Unlock()
	n := 0
	for _, c := range g.calls {
		if c.Proto == proto {
			n++
		}
	}
	return n
}

type recordingProtector struct {
	count atomic.Int64
}

func (p *recordingProtector) Protect(fd int) bool {
	if fd >= 0 {
		p.count.Add(1)
	}
	return true
}

// -----------------------------------------------------------------------------
// Guarded TUN Test Harness (channel.Endpoint + yumi.StartWithGuard)
// -----------------------------------------------------------------------------

type tunHarness struct {
	t         *testing.T
	ep        *channel.Endpoint
	inst      *core.Instance
	guard     *recordingTrafficGuard
	protector *recordingProtector
}

// Compile-time check that recordingTrafficGuard satisfies yumi.TrafficGuard.
var _ yumi.TrafficGuard = (*recordingTrafficGuard)(nil)

// startGuardedTunHarness starts a real xray-core instance with core-xray/tun's GuardedConfig
// wired to a cross-platform gVisor channel.Endpoint and TrafficGuard.
func startGuardedTunHarness(
	t *testing.T,
	configJSON string,
	pinnedTag string,
	guard *recordingTrafficGuard,
	useNilGuard bool,
) *tunHarness {
	t.Helper()
	ep := channel.New(512, 9000, "")

	cfg, err := confserial.LoadJSONConfig(strings.NewReader(configJSON))
	if err != nil {
		ep.Close()
		t.Fatalf("LoadJSONConfig failed: %v", err)
	}

	var g tun.TrafficGuard
	if !useNilGuard && guard != nil {
		g = guard
	}

	// Remove "tun-in" before core.New (which would construct upstream xray-core tun)
	// and attach core-xray/tun GuardedConfig with our cross-platform channel.Endpoint.
	var tunInbound *core.InboundHandlerConfig
	remaining := make([]*core.InboundHandlerConfig, 0, len(cfg.Inbound))
	for _, ib := range cfg.Inbound {
		if ib != nil && ib.Tag == "tun-in" {
			tunInbound = ib
		} else if ib != nil {
			remaining = append(remaining, ib)
		}
	}
	if tunInbound == nil {
		ep.Close()
		t.Fatal("missing tun-in inbound in config")
	}
	cfg.Inbound = remaining

	inst, err := core.New(cfg)
	if err != nil {
		ep.Close()
		t.Fatalf("core.New failed: %v", err)
	}

	rawTun, err := core.CreateObject(inst, &tun.GuardedConfig{
		Tag:              tunInbound.GetTag(),
		ReceiverSettings: tunInbound.GetReceiverSettings(),
		ProxySettings:    tunInbound.GetProxySettings(),
		TunConfig:        &xtun.Config{Name: "tun0", MTU: 9000},
		Endpoint:         ep,
		Guard:            g,
	})
	if err != nil {
		_ = inst.Close()
		ep.Close()
		t.Fatalf("core.CreateObject(tun.GuardedConfig) failed: %v", err)
	}
	tunHandler, ok := rawTun.(inbound.Handler)
	if !ok {
		_ = inst.Close()
		ep.Close()
		t.Fatal("guarded tun object does not implement inbound.Handler")
	}

	if pinnedTag != "" {
		if r, ok := inst.GetFeature(routing.RouterType()).(routing.Router); ok && r != nil {
			if ov, ok := r.(routing.BalancerOverrider); ok {
				if err := ov.SetOverrideTarget(yumi.BalancerTag, pinnedTag); err != nil {
					_ = inst.Close()
					ep.Close()
					t.Fatalf("SetOverrideTarget(%s) failed: %v", pinnedTag, err)
				}
			}
		}
	}

	if err := inst.Start(); err != nil {
		_ = inst.Close()
		ep.Close()
		t.Fatalf("inst.Start failed: %v", err)
	}
	ibm, ok := inst.GetFeature(inbound.ManagerType()).(inbound.Manager)
	if !ok || ibm == nil {
		_ = inst.Close()
		ep.Close()
		t.Fatal("missing inbound.Manager")
	}
	if err := ibm.AddHandler(context.Background(), tunHandler); err != nil {
		_ = inst.Close()
		ep.Close()
		t.Fatalf("AddHandler(tunHandler) failed: %v", err)
	}

	t.Cleanup(func() {
		_ = inst.Close()
		ep.Close()
	})

	return &tunHarness{
		t:         t,
		ep:        ep,
		inst:      inst,
		guard:     guard,
		protector: &recordingProtector{},
	}
}

func (h *tunHarness) selectOutbound(tag string) error {
	r, ok := h.inst.GetFeature(routing.RouterType()).(routing.Router)
	if !ok || r == nil {
		return fmt.Errorf("missing router")
	}
	ov, ok := r.(routing.BalancerOverrider)
	if !ok {
		return fmt.Errorf("router is not BalancerOverrider")
	}
	return ov.SetOverrideTarget(yumi.BalancerTag, tag)
}

func buildTunConfigJSON(t *testing.T, dnsAddr string, hijackDns bool, outbounds []map[string]any) string {
	t.Helper()
	var dnsServer any = "1.1.1.1"
	if dnsAddr != "" {
		host, portStr, err := net.SplitHostPort(dnsAddr)
		if err != nil {
			dnsServer = dnsAddr
		} else {
			p, err := strconv.Atoi(portStr)
			if err != nil {
				t.Fatalf("invalid dns port %q: %v", portStr, err)
			}
			dnsServer = map[string]any{
				"address": host,
				"port":    p,
			}
		}
	}
	if len(outbounds) == 0 {
		outbounds = []map[string]any{
			{
				"tag":      yumi.NodeTagPrefix + "direct",
				"protocol": "freedom",
				"settings": map[string]any{"redirect": "127.0.0.1:0"},
			},
		}
	}
	allOutbounds := append([]any{}, toAnySlice(outbounds)...)
	allOutbounds = append(allOutbounds,
		map[string]any{"tag": "direct", "protocol": "freedom"},
		map[string]any{
			"tag":      "direct-v6",
			"protocol": "freedom",
			"settings": map[string]any{"redirect": "[::1]:0"},
		},
		map[string]any{"tag": "block", "protocol": "blackhole"},
		map[string]any{"tag": "dns-out", "protocol": "dns"},
	)

	rules := []any{
		map[string]any{
			"type":        "field",
			"inboundTag":  []string{"tun-in"},
			"ip":          []string{"172.19.0.2/32"},
			"port":        "53",
			"outboundTag": "dns-out",
		},
		map[string]any{
			"type":        "field",
			"inboundTag":  []string{"tun-in"},
			"ip":          []string{"127.0.0.0/8", "::1/128"},
			"outboundTag": "block",
		},
		map[string]any{
			"type":        "field",
			"inboundTag":  []string{"tun-in"},
			"ip":          []string{"2001:db8::/32"},
			"outboundTag": "direct-v6",
		},
	}
	if hijackDns {
		rules = append(rules, map[string]any{
			"type":        "field",
			"inboundTag":  []string{"tun-in"},
			"port":        "53",
			"outboundTag": "dns-out",
		})
	}
	rules = append(rules, map[string]any{
		"type":        "field",
		"network":     "tcp,udp",
		"balancerTag": yumi.BalancerTag,
	})

	doc := map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"dns": map[string]any{
			"servers":       []any{dnsServer},
			"queryStrategy": "UseIP",
		},
		"inbounds": []any{
			map[string]any{
				"tag":      "tun-in",
				"protocol": "tun",
				"settings": map[string]any{"name": "tun0", "mtu": 9000},
			},
		},
		"outbounds": allOutbounds,
		"routing": map[string]any{
			"domainStrategy": "IPIfNonMatch",
			"balancers": []any{
				map[string]any{
					"tag":      yumi.BalancerTag,
					"selector": []string{yumi.NodeTagPrefix},
				},
			},
			"rules": rules,
		},
	}
	b, err := json.Marshal(doc)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}
	return string(b)
}

func toAnySlice(in []map[string]any) []any {
	out := make([]any, len(in))
	for i, v := range in {
		out[i] = v
	}
	return out
}

// -----------------------------------------------------------------------------
// Raw IPv4 / IPv6 Packet Builders & Parsers (gVisor)
// -----------------------------------------------------------------------------

func (h *tunHarness) injectIPv4(raw []byte) {
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
		Payload: buffer.MakeWithData(raw),
	})
	defer pkt.DecRef()
	h.ep.InjectInbound(header.IPv4ProtocolNumber, pkt)
}

func (h *tunHarness) injectIPv6(raw []byte) {
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
		Payload: buffer.MakeWithData(raw),
	})
	defer pkt.DecRef()
	h.ep.InjectInbound(header.IPv6ProtocolNumber, pkt)
}

func (h *tunHarness) readPacket(timeout time.Duration) ([]byte, tcpip.NetworkProtocolNumber, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	pkt := h.ep.ReadContext(ctx)
	if pkt == nil {
		return nil, 0, false
	}
	defer pkt.DecRef()
	v := pkt.ToView()
	defer v.Release()
	return append([]byte(nil), v.AsSlice()...), pkt.NetworkProtocolNumber, true
}

func buildIPv4TCPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, seq, ack uint32, flags header.TCPFlags, payload []byte) []byte {
	ip4Src := srcIP.To4()
	ip4Dst := dstIP.To4()
	totalLen := header.IPv4MinimumSize + header.TCPMinimumSize + len(payload)
	buf := make([]byte, totalLen)

	ipHdr := header.IPv4(buf[:header.IPv4MinimumSize])
	ipHdr.Encode(&header.IPv4Fields{
		TotalLength: uint16(totalLen),
		TTL:         64,
		Protocol:    uint8(header.TCPProtocolNumber),
		SrcAddr:     tcpip.AddrFrom4Slice(ip4Src),
		DstAddr:     tcpip.AddrFrom4Slice(ip4Dst),
	})
	ipHdr.SetChecksum(^ipHdr.CalculateChecksum())

	tcpHdr := header.TCP(buf[header.IPv4MinimumSize : header.IPv4MinimumSize+header.TCPMinimumSize])
	copy(buf[header.IPv4MinimumSize+header.TCPMinimumSize:], payload)
	tcpHdr.Encode(&header.TCPFields{
		SrcPort:    srcPort,
		DstPort:    dstPort,
		SeqNum:     seq,
		AckNum:     ack,
		DataOffset: header.TCPMinimumSize,
		Flags:      flags,
		WindowSize: 65535,
	})
	xsum := header.PseudoHeaderChecksum(header.TCPProtocolNumber, tcpip.AddrFrom4Slice(ip4Src), tcpip.AddrFrom4Slice(ip4Dst), uint16(header.TCPMinimumSize+len(payload)))
	xsum = checksum.Checksum(payload, xsum)
	tcpHdr.SetChecksum(^tcpHdr.CalculateChecksum(xsum))
	return buf
}

func buildIPv6TCPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, seq, ack uint32, flags header.TCPFlags, payload []byte) []byte {
	src16 := srcIP.To16()
	dst16 := dstIP.To16()
	payloadLen := header.TCPMinimumSize + len(payload)
	buf := make([]byte, header.IPv6MinimumSize+payloadLen)

	ipHdr := header.IPv6(buf[:header.IPv6MinimumSize])
	ipHdr.Encode(&header.IPv6Fields{
		PayloadLength:     uint16(payloadLen),
		TransportProtocol: header.TCPProtocolNumber,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16Slice(src16),
		DstAddr:           tcpip.AddrFrom16Slice(dst16),
	})

	tcpHdr := header.TCP(buf[header.IPv6MinimumSize : header.IPv6MinimumSize+header.TCPMinimumSize])
	copy(buf[header.IPv6MinimumSize+header.TCPMinimumSize:], payload)
	tcpHdr.Encode(&header.TCPFields{
		SrcPort:    srcPort,
		DstPort:    dstPort,
		SeqNum:     seq,
		AckNum:     ack,
		DataOffset: header.TCPMinimumSize,
		Flags:      flags,
		WindowSize: 65535,
	})
	xsum := header.PseudoHeaderChecksum(header.TCPProtocolNumber, tcpip.AddrFrom16Slice(src16), tcpip.AddrFrom16Slice(dst16), uint16(payloadLen))
	xsum = checksum.Checksum(payload, xsum)
	tcpHdr.SetChecksum(^tcpHdr.CalculateChecksum(xsum))
	return buf
}

func buildIPv4UDPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, payload []byte) []byte {
	ip4Src := srcIP.To4()
	ip4Dst := dstIP.To4()
	udpLen := header.UDPMinimumSize + len(payload)
	totalLen := header.IPv4MinimumSize + udpLen
	buf := make([]byte, totalLen)

	ipHdr := header.IPv4(buf[:header.IPv4MinimumSize])
	ipHdr.Encode(&header.IPv4Fields{
		TotalLength: uint16(totalLen),
		TTL:         64,
		Protocol:    uint8(header.UDPProtocolNumber),
		SrcAddr:     tcpip.AddrFrom4Slice(ip4Src),
		DstAddr:     tcpip.AddrFrom4Slice(ip4Dst),
	})
	ipHdr.SetChecksum(^ipHdr.CalculateChecksum())

	udpHdr := header.UDP(buf[header.IPv4MinimumSize : header.IPv4MinimumSize+header.UDPMinimumSize])
	copy(buf[header.IPv4MinimumSize+header.UDPMinimumSize:], payload)
	udpHdr.Encode(&header.UDPFields{
		SrcPort: srcPort,
		DstPort: dstPort,
		Length:  uint16(udpLen),
	})
	xsum := header.PseudoHeaderChecksum(header.UDPProtocolNumber, tcpip.AddrFrom4Slice(ip4Src), tcpip.AddrFrom4Slice(ip4Dst), uint16(udpLen))
	udpHdr.SetChecksum(^udpHdr.CalculateChecksum(checksum.Checksum(payload, xsum)))
	return buf
}

func buildIPv6UDPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, payload []byte) []byte {
	src16 := srcIP.To16()
	dst16 := dstIP.To16()
	udpLen := header.UDPMinimumSize + len(payload)
	buf := make([]byte, header.IPv6MinimumSize+udpLen)

	ipHdr := header.IPv6(buf[:header.IPv6MinimumSize])
	ipHdr.Encode(&header.IPv6Fields{
		PayloadLength:     uint16(udpLen),
		TransportProtocol: header.UDPProtocolNumber,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16Slice(src16),
		DstAddr:           tcpip.AddrFrom16Slice(dst16),
	})

	udpHdr := header.UDP(buf[header.IPv6MinimumSize : header.IPv6MinimumSize+header.UDPMinimumSize])
	copy(buf[header.IPv6MinimumSize+header.UDPMinimumSize:], payload)
	udpHdr.Encode(&header.UDPFields{
		SrcPort: srcPort,
		DstPort: dstPort,
		Length:  uint16(udpLen),
	})
	xsum := header.PseudoHeaderChecksum(header.UDPProtocolNumber, tcpip.AddrFrom16Slice(src16), tcpip.AddrFrom16Slice(dst16), uint16(udpLen))
	udpHdr.SetChecksum(^udpHdr.CalculateChecksum(checksum.Checksum(payload, xsum)))
	return buf
}

func buildIPv4ICMPPacket(srcIP, dstIP net.IP, icmpType header.ICMPv4Type, code header.ICMPv4Code, id, seq uint16, payload []byte) []byte {
	ip4Src := srcIP.To4()
	ip4Dst := dstIP.To4()
	totalLen := header.IPv4MinimumSize + header.ICMPv4MinimumSize + len(payload)
	buf := make([]byte, totalLen)

	ipHdr := header.IPv4(buf[:header.IPv4MinimumSize])
	ipHdr.Encode(&header.IPv4Fields{
		TotalLength: uint16(totalLen),
		TTL:         64,
		Protocol:    uint8(header.ICMPv4ProtocolNumber),
		SrcAddr:     tcpip.AddrFrom4Slice(ip4Src),
		DstAddr:     tcpip.AddrFrom4Slice(ip4Dst),
	})
	ipHdr.SetChecksum(^ipHdr.CalculateChecksum())

	icmpHdr := header.ICMPv4(buf[header.IPv4MinimumSize : header.IPv4MinimumSize+header.ICMPv4MinimumSize])
	icmpHdr.SetType(icmpType)
	icmpHdr.SetCode(code)
	icmpHdr.SetIdent(id)
	icmpHdr.SetSequence(seq)
	copy(buf[header.IPv4MinimumSize+header.ICMPv4MinimumSize:], payload)
	icmpHdr.SetChecksum(header.ICMPv4Checksum(icmpHdr, checksum.Checksum(payload, 0)))
	return buf
}

func buildIPv6ICMPPacket(srcIP, dstIP net.IP, icmpType header.ICMPv6Type, code header.ICMPv6Code, id, seq uint16, payload []byte) []byte {
	src16 := srcIP.To16()
	dst16 := dstIP.To16()
	icmpLen := header.ICMPv6MinimumSize + len(payload)
	buf := make([]byte, header.IPv6MinimumSize+icmpLen)

	ipHdr := header.IPv6(buf[:header.IPv6MinimumSize])
	ipHdr.Encode(&header.IPv6Fields{
		PayloadLength:     uint16(icmpLen),
		TransportProtocol: header.ICMPv6ProtocolNumber,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16Slice(src16),
		DstAddr:           tcpip.AddrFrom16Slice(dst16),
	})

	icmpHdr := header.ICMPv6(buf[header.IPv6MinimumSize : header.IPv6MinimumSize+header.ICMPv6MinimumSize])
	icmpHdr.SetType(icmpType)
	icmpHdr.SetCode(code)
	icmpHdr.SetIdent(id)
	icmpHdr.SetSequence(seq)
	copy(buf[header.IPv6MinimumSize+header.ICMPv6MinimumSize:], payload)
	icmpHdr.SetChecksum(header.ICMPv6Checksum(header.ICMPv6ChecksumParams{
		Header:      icmpHdr,
		Src:         tcpip.AddrFrom16Slice(src16),
		Dst:         tcpip.AddrFrom16Slice(dst16),
		PayloadCsum: checksum.Checksum(payload, 0),
		PayloadLen:  len(payload),
	}))
	return buf
}

// buildDNSQueryWire encodes a minimal RFC 1035 DNS query for qname and qtype (1=A, 16=TXT).
func buildDNSQueryWire(txID uint16, qname string, qtype uint16) []byte {
	var b bytes.Buffer
	var hdr [12]byte
	binary.BigEndian.PutUint16(hdr[0:2], txID)
	binary.BigEndian.PutUint16(hdr[2:4], 0x0100) // RD=1
	binary.BigEndian.PutUint16(hdr[4:6], 1)      // QDCOUNT=1
	b.Write(hdr[:])

	for _, label := range strings.Split(strings.TrimSuffix(qname, "."), ".") {
		b.WriteByte(byte(len(label)))
		b.WriteString(label)
	}
	b.WriteByte(0)
	var qtail [4]byte
	binary.BigEndian.PutUint16(qtail[0:2], qtype)
	binary.BigEndian.PutUint16(qtail[2:4], 1) // IN
	b.Write(qtail[:])
	return b.Bytes()
}

func buildDNSAResponseWire(req []byte, answerIP net.IP) []byte {
	if len(req) < 12 {
		return nil
	}
	ip4 := answerIP.To4()
	if ip4 == nil {
		return nil
	}
	var b bytes.Buffer
	b.Write(req)
	out := b.Bytes()
	binary.BigEndian.PutUint16(out[2:4], 0x8180) // QR=1, RD=1, RA=1, RCODE=0
	binary.BigEndian.PutUint16(out[6:8], 1)      // ANCOUNT=1
	// Answer pointer 0xC00C, Type A (1), Class IN (1), TTL 60, RDLEN 4, IPv4
	answer := []byte{
		0xc0, 0x0c,
		0x00, 0x01,
		0x00, 0x01,
		0x00, 0x00, 0x00, 0x3c,
		0x00, 0x04,
		ip4[0], ip4[1], ip4[2], ip4[3],
	}
	return append(out, answer...)
}

// -----------------------------------------------------------------------------
// Local Loopback Echo & DNS Test Servers
// -----------------------------------------------------------------------------

func startTunTCPEchoServer(t *testing.T) (net.IP, uint16, *atomic.Int64) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen tcp echo: %v", err)
	}
	var conns atomic.Int64
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			conns.Add(1)
			go func(conn net.Conn) {
				defer conn.Close()
				buf := make([]byte, 4096)
				for {
					n, err := conn.Read(buf)
					if n > 0 {
						_, _ = conn.Write(buf[:n])
					}
					if err != nil {
						return
					}
				}
			}(c)
		}
	}()
	addr := ln.Addr().(*net.TCPAddr)
	// Return a non-loopback RFC 5737 TEST-NET-3 IP for TUN packet injection so gVisor
	// does not drop dst=127.0.0.1 as a Martian packet; freedom redirect maps it to 127.0.0.1.
	return net.IPv4(203, 0, 113, 10).To4(), uint16(addr.Port), &conns
}

func startTunUDPEchoServer(t *testing.T, network string, listenAddr string) (net.IP, uint16, *atomic.Int64) {
	t.Helper()
	pc, err := net.ListenPacket(network, listenAddr)
	if err != nil {
		t.Fatalf("listen udp echo (%s %s): %v", network, listenAddr, err)
	}
	var pkts atomic.Int64
	t.Cleanup(func() { _ = pc.Close() })
	go func() {
		buf := make([]byte, 4096)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			pkts.Add(1)
			_, _ = pc.WriteTo(buf[:n], addr)
		}
	}()
	uaddr := pc.LocalAddr().(*net.UDPAddr)
	if network == "udp6" {
		return net.ParseIP("2001:db8::10"), uint16(uaddr.Port), &pkts
	}
	return net.IPv4(203, 0, 113, 11).To4(), uint16(uaddr.Port), &pkts
}

func startLocalUDPUpstreamDNS(t *testing.T, answerIP net.IP) (string, *atomic.Int64) {
	t.Helper()
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen upstream dns: %v", err)
	}
	var queries atomic.Int64
	t.Cleanup(func() { _ = pc.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			queries.Add(1)
			if resp := buildDNSAResponseWire(buf[:n], answerIP); resp != nil {
				_, _ = pc.WriteTo(resp, addr)
			}
		}
	}()
	return pc.LocalAddr().String(), &queries
}

// -----------------------------------------------------------------------------
// Packet Inspection Helpers
// -----------------------------------------------------------------------------

func expectTCPFlags(t *testing.T, h *tunHarness, dstPort uint16, wantFlags header.TCPFlags, timeout time.Duration) (header.TCP, bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		rem := time.Until(deadline)
		raw, proto, ok := h.readPacket(rem)
		if !ok {
			return nil, false
		}
		var transport []byte
		switch proto {
		case header.IPv4ProtocolNumber:
			if len(raw) < header.IPv4MinimumSize {
				continue
			}
			ip4 := header.IPv4(raw)
			if ip4.TransportProtocol() != header.TCPProtocolNumber {
				continue
			}
			transport = raw[ip4.HeaderLength():]
		case header.IPv6ProtocolNumber:
			if len(raw) < header.IPv6MinimumSize {
				continue
			}
			ip6 := header.IPv6(raw)
			if ip6.TransportProtocol() != header.TCPProtocolNumber {
				continue
			}
			transport = raw[header.IPv6MinimumSize:]
		default:
			continue
		}
		if len(transport) < header.TCPMinimumSize {
			continue
		}
		tcpHdr := header.TCP(transport)
		if tcpHdr.SourcePort() == dstPort && tcpHdr.Flags().Contains(wantFlags) {
			return tcpHdr, true
		}
	}
	return nil, false
}

func expectUDPPayload(t *testing.T, h *tunHarness, srcPort, dstPort uint16, timeout time.Duration) ([]byte, bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		raw, proto, ok := h.readPacket(time.Until(deadline))
		if !ok {
			return nil, false
		}
		var transport []byte
		switch proto {
		case header.IPv4ProtocolNumber:
			if len(raw) < header.IPv4MinimumSize {
				continue
			}
			ip4 := header.IPv4(raw)
			if ip4.TransportProtocol() != header.UDPProtocolNumber {
				continue
			}
			transport = raw[ip4.HeaderLength():]
		case header.IPv6ProtocolNumber:
			if len(raw) < header.IPv6MinimumSize {
				continue
			}
			ip6 := header.IPv6(raw)
			if ip6.TransportProtocol() != header.UDPProtocolNumber {
				continue
			}
			transport = raw[header.IPv6MinimumSize:]
		default:
			continue
		}
		if len(transport) < header.UDPMinimumSize {
			continue
		}
		udpHdr := header.UDP(transport)
		if udpHdr.SourcePort() == srcPort && udpHdr.DestinationPort() == dstPort {
			return append([]byte(nil), udpHdr.Payload()...), true
		}
	}
	return nil, false
}

func assertNoICMPPackets(t *testing.T, h *tunHarness, wait time.Duration) {
	t.Helper()
	deadline := time.Now().Add(wait)
	for time.Now().Before(deadline) {
		raw, proto, ok := h.readPacket(time.Until(deadline))
		if !ok {
			return
		}
		switch proto {
		case header.IPv4ProtocolNumber:
			if len(raw) >= header.IPv4MinimumSize && header.IPv4(raw).TransportProtocol() == header.ICMPv4ProtocolNumber {
				icmpHdr := header.ICMPv4(raw[header.IPv4(raw).HeaderLength():])
				t.Fatalf("unexpected ICMPv4 packet emitted on tun0: type=%d code=%d", icmpHdr.Type(), icmpHdr.Code())
			}
		case header.IPv6ProtocolNumber:
			if len(raw) >= header.IPv6MinimumSize && header.IPv6(raw).TransportProtocol() == header.ICMPv6ProtocolNumber {
				icmpHdr := header.ICMPv6(raw[header.IPv6MinimumSize:])
				if icmpHdr.Type() == header.ICMPv6EchoReply {
					t.Fatalf("unexpected ICMPv6 Echo Reply emitted on tun0")
				}
			}
		}
	}
}

// -----------------------------------------------------------------------------
// Tier 1 Tests: A2, A3, A4, A5, A6, A8
// -----------------------------------------------------------------------------

// T1-A2-04: yumi.StartWithGuard invokes TrafficGuard with exact 5-tuple before forwarding.
func TestE2E_T1_A2_04_TrafficGuardExactFiveTuple(t *testing.T) {
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return false })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	dstIP := net.IPv4(1, 1, 1, 1).To4()
	h.injectIPv4(buildIPv4TCPPacket(srcIP, dstIP, 50001, 80, 1000, 0, header.TCPFlagSyn, nil))

	if _, ok := expectTCPFlags(t, h, 80, header.TCPFlagRst, time.Second); !ok {
		t.Fatal("expected RST for disallowed TCP SYN")
	}

	calls := guard.Calls()
	if len(calls) == 0 {
		t.Fatal("expected TrafficGuard.AllowConnection to be called")
	}
	c := calls[0]
	if c.Proto != 6 || !bytes.Equal(c.SrcIP, srcIP) || c.SrcPort != 50001 || !bytes.Equal(c.DstIP, dstIP) || c.DstPort != 80 {
		t.Fatalf("unexpected 5-tuple passed to TrafficGuard: %+v", c)
	}
}

// T1-A2-05: Nil TrafficGuard defaults cleanly without panic.
func TestE2E_T1_A2_05_NilTrafficGuardDefaultsCleanly(t *testing.T) {
	echoIP, echoPort, conns := startTunTCPEchoServer(t)
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", nil, true)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	const srcPort = 50002
	const clientSeq = 100
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, srcPort, echoPort, clientSeq, 0, header.TCPFlagSyn, nil))

	synAck, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagSyn|header.TCPFlagAck, 2*time.Second)
	if !ok || synAck == nil {
		t.Fatal("expected SYN-ACK when TrafficGuard is nil")
	}
	ackNum := synAck.SequenceNumber() + 1
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, srcPort, echoPort, clientSeq+1, ackNum, header.TCPFlagAck, nil))
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, srcPort, echoPort, clientSeq+1, ackNum, header.TCPFlagAck|header.TCPFlagPsh, []byte("nil-guard-ok")))

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && conns.Load() == 0 {
		time.Sleep(15 * time.Millisecond)
	}
	if conns.Load() == 0 {
		t.Fatal("expected connection to reach echo server when guard is nil")
	}
}

// T1-A3-01: Allowed TCP SYN completes 3-way handshake and echoes payload.
func TestE2E_T1_A3_01_AllowedTCPSYNHandshakeAndEcho(t *testing.T) {
	echoIP, echoPort, conns := startTunTCPEchoServer(t)
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	const srcPort = 51001
	const clientSeq = 1000
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, srcPort, echoPort, clientSeq, 0, header.TCPFlagSyn, nil))

	synAck, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagSyn|header.TCPFlagAck, 2*time.Second)
	if !ok {
		t.Fatal("expected SYN-ACK for allowed TCP SYN")
	}
	ackNum := synAck.SequenceNumber() + 1
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, srcPort, echoPort, clientSeq+1, ackNum, header.TCPFlagAck, nil))

	payload := []byte("yumi-e2e-tcp-echo")
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, srcPort, echoPort, clientSeq+1, ackNum, header.TCPFlagAck|header.TCPFlagPsh, payload))

	deadline := time.Now().Add(2 * time.Second)
	var gotEcho bool
	for time.Now().Before(deadline) {
		raw, proto, ok := h.readPacket(time.Until(deadline))
		if !ok {
			break
		}
		if proto != header.IPv4ProtocolNumber || len(raw) < header.IPv4MinimumSize {
			continue
		}
		ip4 := header.IPv4(raw)
		if ip4.TransportProtocol() != header.TCPProtocolNumber {
			continue
		}
		tcpHdr := header.TCP(raw[ip4.HeaderLength():])
		if tcpHdr.SourcePort() == echoPort && bytes.Contains(tcpHdr.Payload(), payload) {
			gotEcho = true
			break
		}
	}
	if !gotEcho || conns.Load() == 0 {
		t.Fatalf("expected echoed TCP payload back on tun0 (gotEcho=%v conns=%d)", gotEcho, conns.Load())
	}
}

// T1-A3-02: Disallowed TCP SYN rejected pre-SYN-ACK with RST and 0 echo server connections.
func TestE2E_T1_A3_02_DisallowedTCPPreSYNACKRST(t *testing.T) {
	echoIP, echoPort, conns := startTunTCPEchoServer(t)
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return false })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, 51002, echoPort, 1, 0, header.TCPFlagSyn, nil))

	if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagRst, time.Second); !ok {
		t.Fatal("expected immediate TCP RST for disallowed SYN")
	}
	if conns.Load() != 0 {
		t.Fatalf("expected 0 connections to echo server, got %d", conns.Load())
	}
}

// T1-A3-03: Allowed UDP 5-tuple datagram reaches echo server and returns reply on tun0.
func TestE2E_T1_A3_03_AllowedUDP5TupleEcho(t *testing.T) {
	udpIP, udpPort, pkts := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	msg := []byte("yumi-udp-hello")
	h.injectIPv4(buildIPv4UDPPacket(srcIP, udpIP.To4(), 52001, udpPort, msg))

	reply, ok := expectUDPPayload(t, h, udpPort, 52001, 2*time.Second)
	if !ok || !bytes.Equal(reply, msg) {
		t.Fatalf("expected UDP echo %q, got ok=%v payload=%q (server pkts=%d)", msg, ok, reply, pkts.Load())
	}
}

// T1-A3-04: Disallowed UDP 5-tuple datagram is silently dropped with 0 server packets.
func TestE2E_T1_A3_04_DisallowedUDPSilentDrop(t *testing.T) {
	udpIP, udpPort, pkts := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return false })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	h.injectIPv4(buildIPv4UDPPacket(srcIP, udpIP.To4(), 52002, udpPort, []byte("blocked-udp")))

	if reply, ok := expectUDPPayload(t, h, udpPort, 52002, 250*time.Millisecond); ok {
		t.Fatalf("expected silent drop for disallowed UDP, got %q", reply)
	}
	if pkts.Load() != 0 {
		t.Fatalf("expected 0 packets at UDP server, got %d", pkts.Load())
	}
}

// T1-A3-05: UDP flow-scoped cache invokes TrafficGuard once per active 5-tuple flow.
func TestE2E_T1_A3_05_UDPFlowCacheSingleLookupPerActiveFlow(t *testing.T) {
	udpIP, udpPort, pkts := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	for i := 0; i < 10; i++ {
		msg := []byte(fmt.Sprintf("pkt-%02d", i))
		h.injectIPv4(buildIPv4UDPPacket(srcIP, udpIP.To4(), 52003, udpPort, msg))
		reply, ok := expectUDPPayload(t, h, udpPort, 52003, time.Second)
		if !ok || !bytes.Equal(reply, msg) {
			t.Fatalf("packet %d echo mismatch: ok=%v reply=%q", i, ok, reply)
		}
	}
	if pkts.Load() != 10 {
		t.Fatalf("expected 10 UDP packets echoed, got %d", pkts.Load())
	}
	if got := guard.CountProto(17); got != 1 {
		t.Fatalf("expected 1 cached TrafficGuard lookup for active UDP 5-tuple, got %d", got)
	}
}

// T1-A4-01: Allowed UDP DNS query to 172.19.0.2:53 resolves via dns-out.
func TestE2E_T1_A4_01_AllowedUDPDNSResolves(t *testing.T) {
	dnsAddr, queries := startLocalUDPUpstreamDNS(t, net.IPv4(93, 184, 216, 34))
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, dnsAddr, true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	dnsIP := net.IPv4(172, 19, 0, 2).To4()
	q := buildDNSQueryWire(0x1234, "example.com", 1)
	h.injectIPv4(buildIPv4UDPPacket(srcIP, dnsIP, 53001, 53, q))

	resp, ok := expectUDPPayload(t, h, 53, 53001, 2*time.Second)
	if !ok || len(resp) < 12 || binary.BigEndian.Uint16(resp[0:2]) != 0x1234 {
		t.Fatalf("expected DNS response for txID 0x1234, got ok=%v len=%d", ok, len(resp))
	}
	if rcode := resp[3] & 0x0f; rcode != 0 {
		t.Fatalf("expected DNS RCodeSuccess (0), got %d", rcode)
	}
	if queries.Load() == 0 {
		t.Fatal("expected upstream DNS server to receive query")
	}
}

// T1-A4-02: Excluded app UDP DNS query to 172.19.0.2:53 is dropped before dns-out.
func TestE2E_T1_A4_02_DisallowedUDPDNSDropped(t *testing.T) {
	dnsAddr, queries := startLocalUDPUpstreamDNS(t, net.IPv4(93, 184, 216, 34))
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return false })
	cfg := buildTunConfigJSON(t, dnsAddr, true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	dnsIP := net.IPv4(172, 19, 0, 2).To4()
	q := buildDNSQueryWire(0x1235, "example.com", 1)
	h.injectIPv4(buildIPv4UDPPacket(srcIP, dnsIP, 53002, 53, q))

	if resp, ok := expectUDPPayload(t, h, 53, 53002, 250*time.Millisecond); ok {
		t.Fatalf("expected disallowed UDP DNS query to drop, got %x", resp)
	}
	if queries.Load() != 0 {
		t.Fatalf("expected 0 upstream DNS queries, got %d", queries.Load())
	}
}

// T1-A4-03: Excluded app TCP DNS query to 172.19.0.2:53 gets pre-SYN-ACK RST.
func TestE2E_T1_A4_03_DisallowedTCPDNSPreSYNACKRST(t *testing.T) {
	dnsAddr, queries := startLocalUDPUpstreamDNS(t, net.IPv4(93, 184, 216, 34))
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return false })
	cfg := buildTunConfigJSON(t, dnsAddr, true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	dnsIP := net.IPv4(172, 19, 0, 2).To4()
	h.injectIPv4(buildIPv4TCPPacket(srcIP, dnsIP, 53003, 53, 10, 0, header.TCPFlagSyn, nil))

	if _, ok := expectTCPFlags(t, h, 53, header.TCPFlagRst, time.Second); !ok {
		t.Fatal("expected RST for disallowed TCP DNS query to 172.19.0.2:53")
	}
	if queries.Load() != 0 {
		t.Fatalf("expected 0 upstream DNS queries, got %d", queries.Load())
	}
}

// T1-A5-01: Active guard suppresses ICMPv4 Echo Request to 172.19.0.1 / 172.19.0.2.
func TestE2E_T1_A5_01_ICMPv4EchoToTunGatewaySuppressed(t *testing.T) {
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	h.injectIPv4(buildIPv4ICMPPacket(srcIP, net.IPv4(172, 19, 0, 2).To4(), header.ICMPv4Echo, 0, 1, 1, []byte("ping")))
	h.injectIPv4(buildIPv4ICMPPacket(srcIP, srcIP, header.ICMPv4Echo, 0, 1, 2, []byte("ping")))

	assertNoICMPPackets(t, h, 250*time.Millisecond)
	if got := guard.CountProto(1); got != 0 {
		t.Fatalf("expected 0 TrafficGuard calls with proto=1, got %d", got)
	}
}

// T1-A5-02: Active guard suppresses ICMPv4 Echo Request to external IP 8.8.8.8.
func TestE2E_T1_A5_02_ICMPv4EchoToExternalSuppressed(t *testing.T) {
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	h.injectIPv4(buildIPv4ICMPPacket(net.IPv4(172, 19, 0, 1), net.IPv4(8, 8, 8, 8), header.ICMPv4Echo, 0, 2, 1, []byte("ping")))
	assertNoICMPPackets(t, h, 250*time.Millisecond)
}

// T1-A5-03: Active guard suppresses ICMPv6 Echo Request to fdfe:dcba:9876::1 / 2001:4860:4860::8888.
func TestE2E_T1_A5_03_ICMPv6EchoSuppressed(t *testing.T) {
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcV6 := net.ParseIP("fdfe:dcba:9876::1")
	h.injectIPv6(buildIPv6ICMPPacket(srcV6, srcV6, header.ICMPv6EchoRequest, 0, 3, 1, []byte("v6")))
	h.injectIPv6(buildIPv6ICMPPacket(srcV6, net.ParseIP("2001:4860:4860::8888"), header.ICMPv6EchoRequest, 0, 3, 2, []byte("v6")))
	assertNoICMPPackets(t, h, 250*time.Millisecond)
}

// T1-A5-04: Guard never passes ICMP protocol numbers (1, 58) to TrafficGuard.
func TestE2E_T1_A5_04_ICMPNeverPassedToTrafficGuard(t *testing.T) {
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	h.injectIPv4(buildIPv4ICMPPacket(net.IPv4(172, 19, 0, 1), net.IPv4(172, 19, 0, 2), header.ICMPv4Echo, 0, 4, 1, []byte("v4")))
	h.injectIPv6(buildIPv6ICMPPacket(net.ParseIP("fdfe:dcba:9876::1"), net.ParseIP("fdfe:dcba:9876::2"), header.ICMPv6EchoRequest, 0, 4, 2, []byte("v6")))
	assertNoICMPPackets(t, h, 200*time.Millisecond)

	if guard.CountProto(1) != 0 || guard.CountProto(58) != 0 {
		t.Fatalf("TrafficGuard must never receive proto=1 (%d) or proto=58 (%d)", guard.CountProto(1), guard.CountProto(58))
	}
}

// T1-A5-05: Guarded TUN remains healthy for TCP/UDP immediately after ICMP echo probes.
func TestE2E_T1_A5_05_TUNHealthyForTCPUDPAfterICMP(t *testing.T) {
	echoIP, echoPort, _ := startTunTCPEchoServer(t)
	udpIP, udpPort, _ := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	for i := uint16(0); i < 10; i++ {
		h.injectIPv4(buildIPv4ICMPPacket(srcIP, net.IPv4(172, 19, 0, 2), header.ICMPv4Echo, 0, 5, i, []byte("probe")))
	}
	assertNoICMPPackets(t, h, 150*time.Millisecond)

	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, 51500, echoPort, 1, 0, header.TCPFlagSyn, nil))
	if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagSyn|header.TCPFlagAck, 2*time.Second); !ok {
		t.Fatal("TCP failed after ICMP probes")
	}
	h.injectIPv4(buildIPv4UDPPacket(srcIP, udpIP.To4(), 51501, udpPort, []byte("after-icmp")))
	if reply, ok := expectUDPPayload(t, h, udpPort, 51501, 2*time.Second); !ok || string(reply) != "after-icmp" {
		t.Fatalf("UDP failed after ICMP probes: ok=%v reply=%q", ok, reply)
	}
}

// T1-A6-01: IPv6 TCP SYN disallowed by TrafficGuard receives TCPv6 RST.
func TestE2E_T1_A6_01_IPv6DisallowedTCPRST(t *testing.T) {
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return false })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcV6 := net.ParseIP("fdfe:dcba:9876::1")
	dstV6 := net.ParseIP("2001:db8::1")
	h.injectIPv6(buildIPv6TCPPacket(srcV6, dstV6, 54001, 443, 1, 0, header.TCPFlagSyn, nil))

	if _, ok := expectTCPFlags(t, h, 443, header.TCPFlagRst, time.Second); !ok {
		t.Fatal("expected IPv6 TCP RST for disallowed IPv6 SYN")
	}
}

// T1-A6-02: IPv6 UDP datagram allowed by TrafficGuard succeeds end-to-end.
func TestE2E_T1_A6_02_IPv6AllowedUDPEcho(t *testing.T) {
	udpIP, udpPort, pkts := startTunUDPEchoServer(t, "udp6", "[::1]:0")
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcV6 := net.ParseIP("fdfe:dcba:9876::1")
	msg := []byte("ipv6-udp-ok")
	h.injectIPv6(buildIPv6UDPPacket(srcV6, udpIP.To16(), 54002, udpPort, msg))

	reply, ok := expectUDPPayload(t, h, udpPort, 54002, 2*time.Second)
	if !ok || !bytes.Equal(reply, msg) {
		t.Fatalf("expected IPv6 UDP echo %q, got ok=%v reply=%q (pkts=%d)", msg, ok, reply, pkts.Load())
	}
}

// T1-A8-04: Excluded traffic blocked identically regardless of active outbound protocol.
func TestE2E_T1_A8_04_DisallowedBlockedAcrossAllOutbounds(t *testing.T) {
	echoIP, echoPort, conns := startTunTCPEchoServer(t)
	udpIP, udpPort, udpPkts := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")

	outbounds := []map[string]any{
		{"tag": yumi.NodeTagPrefix + "freedom", "protocol": "freedom"},
		{"tag": yumi.NodeTagPrefix + "socks", "protocol": "socks", "settings": map[string]any{"servers": []any{map[string]any{"address": "127.0.0.1", "port": 1}}}},
		{"tag": yumi.NodeTagPrefix + "vless", "protocol": "vless", "settings": map[string]any{"vnext": []any{map[string]any{"address": "127.0.0.1", "port": 1, "users": []any{map[string]any{"id": "11111111-2222-3333-4444-555555555555", "encryption": "none"}}}}}},
	}
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return false })
	cfg := buildTunConfigJSON(t, "", true, outbounds)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"freedom", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	for i, ob := range outbounds {
		tag := ob["tag"].(string)
		if err := h.selectOutbound(tag); err != nil {
			t.Fatalf("selectOutbound(%s): %v", tag, err)
		}
		h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, uint16(54100+i), echoPort, 1, 0, header.TCPFlagSyn, nil))
		if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagRst, time.Second); !ok {
			t.Fatalf("outbound %s: expected RST for disallowed TCP SYN", tag)
		}
		h.injectIPv4(buildIPv4UDPPacket(srcIP, udpIP.To4(), uint16(54200+i), udpPort, []byte("drop")))
		if _, ok := expectUDPPayload(t, h, udpPort, uint16(54200+i), 150*time.Millisecond); ok {
			t.Fatalf("outbound %s: expected UDP drop", tag)
		}
	}
	if conns.Load() != 0 || udpPkts.Load() != 0 {
		t.Fatalf("expected 0 leaked connections/packets, got tcp=%d udp=%d", conns.Load(), udpPkts.Load())
	}
}

var (
	testProtectorOnce sync.Once
	activeProtector   atomic.Pointer[recordingProtector]
)

func installTestProtectorController() {
	testProtectorOnce.Do(func() {
		_ = internet.RegisterDialerController(func(_, _ string, c syscall.RawConn) error {
			p := activeProtector.Load()
			if p == nil {
				return nil
			}
			return c.Control(func(fd uintptr) {
				p.Protect(int(fd))
			})
		})
	})
}

// T1-A8-05: Protector.Protect(fd) called across outbound dials without SO_BINDTODEVICE.
func TestE2E_T1_A8_05_ProtectorCalledAcrossOutboundDials(t *testing.T) {
	installTestProtectorController()
	echoIP, echoPort, _ := startTunTCPEchoServer(t)
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)
	activeProtector.Store(h.protector)
	t.Cleanup(func() { activeProtector.Store(nil) })

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	const srcPort = 54300
	const clientSeq = 1
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, srcPort, echoPort, clientSeq, 0, header.TCPFlagSyn, nil))
	synAck, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagSyn|header.TCPFlagAck, 2*time.Second)
	if !ok {
		t.Fatal("expected SYN-ACK")
	}
	ackNum := synAck.SequenceNumber() + 1
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, srcPort, echoPort, clientSeq+1, ackNum, header.TCPFlagAck, nil))
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, srcPort, echoPort, clientSeq+1, ackNum, header.TCPFlagAck|header.TCPFlagPsh, []byte("protect")))

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && h.protector.count.Load() == 0 {
		time.Sleep(20 * time.Millisecond)
	}
	if h.protector.count.Load() == 0 {
		t.Fatal("expected Protector.Protect(fd) to be called on outbound dial")
	}
}

// -----------------------------------------------------------------------------
// Tier 2 Tests: Boundary & Corner Cases
// -----------------------------------------------------------------------------

// T2-A2-05: Stop()/Start() cycles reset TrafficGuard cleanly without leaking prior policy.
func TestE2E_T2_A2_05_StopStartCyclesResetTrafficGuard(t *testing.T) {
	echoIP, echoPort, _ := startTunTCPEchoServer(t)
	guard1 := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return false })
	guard2 := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)

	h1 := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard1, false)
	srcIP := net.IPv4(172, 19, 0, 1).To4()
	h1.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, 54401, echoPort, 1, 0, header.TCPFlagSyn, nil))
	if _, ok := expectTCPFlags(t, h1, echoPort, header.TCPFlagRst, time.Second); !ok {
		t.Fatal("expected RST under guard1")
	}
	callsAfterCycle1 := len(guard1.Calls())
	_ = h1.inst.Close()
	h1.ep.Close()

	h2 := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard2, false)
	h2.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, 54402, echoPort, 1, 0, header.TCPFlagSyn, nil))
	if _, ok := expectTCPFlags(t, h2, echoPort, header.TCPFlagSyn|header.TCPFlagAck, 2*time.Second); !ok {
		t.Fatal("expected SYN-ACK under guard2")
	}
	if len(guard1.Calls()) != callsAfterCycle1 {
		t.Fatal("guard1 received calls after Close() and second Start()")
	}
	// Also verify yumi.StartWithGuard / yumi.Stop contract exists and Stop is idempotent.
	_ = yumi.StartWithGuard("{}", 0, "", h2.protector, guard2)
	if err := yumi.Stop(); err != nil {
		t.Fatalf("second Stop() failed: %v", err)
	}
	if err := yumi.Stop(); err != nil {
		t.Fatalf("duplicate Stop() should return nil, got %v", err)
	}
}

// T2-A3-01: UDP ephemeral port reuse across different destinations re-evaluates 5-tuple.
func TestE2E_T2_A3_01_UDPPortReuseAcrossDifferentDestinations(t *testing.T) {
	dstAIP, dstAPort, _ := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")
	dstBIP, dstBPort, dstBPkts := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")

	guard := newRecordingGuard(func(_ int, _ []byte, _ int, dstIP []byte, dstPort int) bool {
		return dstPort == int(dstAPort)
	})
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	const sharedSrcPort = 55000
	h.injectIPv4(buildIPv4UDPPacket(srcIP, dstAIP.To4(), sharedSrcPort, dstAPort, []byte("to-a")))
	if reply, ok := expectUDPPayload(t, h, dstAPort, sharedSrcPort, 2*time.Second); !ok || string(reply) != "to-a" {
		t.Fatalf("expected dstA echo, got ok=%v reply=%q", ok, reply)
	}

	h.injectIPv4(buildIPv4UDPPacket(srcIP, dstBIP.To4(), sharedSrcPort, dstBPort, []byte("to-b")))
	if reply, ok := expectUDPPayload(t, h, dstBPort, sharedSrcPort, 250*time.Millisecond); ok {
		t.Fatalf("expected dstB to be dropped despite shared srcPort, got %q", reply)
	}
	if dstBPkts.Load() != 0 {
		t.Fatalf("dstB unexpectedly received %d packets", dstBPkts.Load())
	}
}

// T2-A3-02: UDP ephemeral port reuse after flow eviction/close re-queries TrafficGuard.
func TestE2E_T2_A3_02_UDPPortReuseAfterFlowCloseEviction(t *testing.T) {
	udpIP, udpPort, pkts := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")
	var allowFlag atomic.Bool
	allowFlag.Store(true)
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool {
		return allowFlag.Load()
	})
	cfg := buildTunConfigJSON(t, "", true, nil)
	// Use a 1-second connIdle timeout so the active UDP flow closes and evicts naturally.
	cfg = strings.Replace(cfg, `"log":`, `"policy":{"levels":{"0":{"connIdle":1,"uplinkOnly":1,"downlinkOnly":1}}},"log":`, 1)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	h.injectIPv4(buildIPv4UDPPacket(srcIP, udpIP.To4(), 55001, udpPort, []byte("flow1")))
	if reply, ok := expectUDPPayload(t, h, udpPort, 55001, 2*time.Second); !ok || string(reply) != "flow1" {
		t.Fatalf("flow1 failed: ok=%v reply=%q", ok, reply)
	}

	// ActivityTimer fires after ~2*connIdle (~2s); wait 2.5s for flow closure & 5-tuple eviction.
	time.Sleep(2500 * time.Millisecond)
	allowFlag.Store(false)

	h.injectIPv4(buildIPv4UDPPacket(srcIP, udpIP.To4(), 55001, udpPort, []byte("flow2-blocked")))
	if reply, ok := expectUDPPayload(t, h, udpPort, 55001, 250*time.Millisecond); ok {
		t.Fatalf("expected evicted reused 5-tuple to be blocked, got %q", reply)
	}
	if pkts.Load() != 1 {
		t.Fatalf("expected only flow1 (1 pkt) at UDP server, got %d", pkts.Load())
	}
}

// T2-A3-03: Unconnected UDP socket sending to 2 destinations (dst1 allowed, dst2 denied).
func TestE2E_T2_A3_03_UnconnectedUDPTwoDestinations(t *testing.T) {
	dst1IP, dst1Port, _ := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")
	dst2IP, dst2Port, dst2Pkts := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")

	guard := newRecordingGuard(func(_ int, _ []byte, _ int, _ []byte, dstPort int) bool {
		return dstPort == int(dst1Port)
	})
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	h.injectIPv4(buildIPv4UDPPacket(srcIP, dst1IP.To4(), 55002, dst1Port, []byte("ok")))
	h.injectIPv4(buildIPv4UDPPacket(srcIP, dst2IP.To4(), 55002, dst2Port, []byte("deny")))

	if reply, ok := expectUDPPayload(t, h, dst1Port, 55002, 2*time.Second); !ok || string(reply) != "ok" {
		t.Fatalf("expected dst1 echo, got ok=%v reply=%q", ok, reply)
	}
	if reply, ok := expectUDPPayload(t, h, dst2Port, 55002, 200*time.Millisecond); ok {
		t.Fatalf("expected dst2 drop, got %q", reply)
	}
	if dst2Pkts.Load() != 0 {
		t.Fatalf("dst2 received %d packets", dst2Pkts.Load())
	}
}

// T2-A3-04: Burst of 100 concurrent disallowed TCP SYNs followed by 1 allowed SYN.
func TestE2E_T2_A3_04_Burst100ConcurrentDisallowedTCPSYNs(t *testing.T) {
	echoIP, echoPort, conns := startTunTCPEchoServer(t)
	guard := newRecordingGuard(func(_ int, _ []byte, srcPort int, _ []byte, _ int) bool {
		return srcPort == 56100
	})
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	var wg sync.WaitGroup
	for p := 56000; p < 56100; p++ {
		wg.Add(1)
		go func(port uint16) {
			defer wg.Done()
			h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, port, echoPort, 1, 0, header.TCPFlagSyn, nil))
		}(uint16(p))
	}
	wg.Wait()

	rstCount := 0
	deadline := time.Now().Add(3 * time.Second)
	for rstCount < 100 && time.Now().Before(deadline) {
		raw, proto, ok := h.readPacket(time.Until(deadline))
		if !ok {
			break
		}
		if proto == header.IPv4ProtocolNumber && len(raw) >= header.IPv4MinimumSize {
			ip4 := header.IPv4(raw)
			if ip4.TransportProtocol() == header.TCPProtocolNumber {
				tcpHdr := header.TCP(raw[ip4.HeaderLength():])
				if tcpHdr.Flags().Contains(header.TCPFlagRst) {
					rstCount++
				}
			}
		}
	}
	if rstCount != 100 {
		t.Fatalf("expected 100 RSTs for 100 disallowed SYNs, got %d", rstCount)
	}
	if conns.Load() != 0 {
		t.Fatalf("expected 0 leaked echo connections during flood, got %d", conns.Load())
	}

	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, 56100, echoPort, 1, 0, header.TCPFlagSyn, nil))
	if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagSyn|header.TCPFlagAck, 2*time.Second); !ok {
		t.Fatal("expected allowed SYN 56100 to succeed after flood")
	}
}

// T2-A3-05: Truncated / malformed IP/TCP/UDP packets dropped cleanly without panic.
func TestE2E_T2_A3_05_MalformedPacketsDroppedWithoutPanic(t *testing.T) {
	echoIP, echoPort, _ := startTunTCPEchoServer(t)
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	h.injectIPv4([]byte{0x45, 0x00, 0x00, 0x0a, 0, 0, 0, 0, 64, 6}) // 10-byte truncated IPv4
	h.injectIPv4([]byte{0x90, 0x00, 0x00, 0x14, 0, 0, 0, 0, 64, 17, 0, 0, 172, 19, 0, 1, 1, 1, 1, 1})
	h.injectIPv6([]byte{0x60, 0x00, 0x00, 0x00}) // truncated IPv6

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, 56200, echoPort, 1, 0, header.TCPFlagSyn, nil))
	if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagSyn|header.TCPFlagAck, 2*time.Second); !ok {
		t.Fatal("TUN stack unhealthy after malformed packets")
	}
}

// T2-A4-01: Excluded app UDP DNS to external resolver 8.8.8.8:53 is blocked before hijack.
func TestE2E_T2_A4_01_DisallowedExternalDNSTo8888Blocked(t *testing.T) {
	dnsAddr, queries := startLocalUDPUpstreamDNS(t, net.IPv4(93, 184, 216, 34))
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return false })
	cfg := buildTunConfigJSON(t, dnsAddr, true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	q := buildDNSQueryWire(0x4001, "leak-test.example", 1)
	h.injectIPv4(buildIPv4UDPPacket(net.IPv4(172, 19, 0, 1), net.IPv4(8, 8, 8, 8), 57001, 53, q))
	if resp, ok := expectUDPPayload(t, h, 53, 57001, 250*time.Millisecond); ok {
		t.Fatalf("expected 8.8.8.8:53 DNS query to be blocked by guard, got %x", resp)
	}
	if queries.Load() != 0 {
		t.Fatalf("expected 0 upstream DNS queries, got %d", queries.Load())
	}
}

// T2-A4-02: Excluded app UDP DNS to 172.19.0.2:53 blocked even when hijackDns = false.
func TestE2E_T2_A4_02_DisallowedDNSTo1721902BlockedWhenHijackFalse(t *testing.T) {
	dnsAddr, queries := startLocalUDPUpstreamDNS(t, net.IPv4(93, 184, 216, 34))
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return false })
	cfg := buildTunConfigJSON(t, dnsAddr, false, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	q := buildDNSQueryWire(0x4002, "no-hijack.example", 1)
	h.injectIPv4(buildIPv4UDPPacket(net.IPv4(172, 19, 0, 1), net.IPv4(172, 19, 0, 2), 57002, 53, q))
	if resp, ok := expectUDPPayload(t, h, 53, 57002, 250*time.Millisecond); ok {
		t.Fatalf("expected 172.19.0.2:53 DNS query blocked when hijackDns=false, got %x", resp)
	}
	if queries.Load() != 0 {
		t.Fatalf("expected 0 upstream DNS queries, got %d", queries.Load())
	}
}

// T2-A4-04: Non-A/AAAA DNS query (TXT) to 172.19.0.2:53 from excluded app is dropped silently.
func TestE2E_T2_A4_04_DisallowedNonATXTQueryBlocked(t *testing.T) {
	dnsAddr, queries := startLocalUDPUpstreamDNS(t, net.IPv4(93, 184, 216, 34))
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return false })
	cfg := buildTunConfigJSON(t, dnsAddr, true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	q := buildDNSQueryWire(0x4003, "txt.example", 16) // QTYPE=TXT
	h.injectIPv4(buildIPv4UDPPacket(net.IPv4(172, 19, 0, 1), net.IPv4(172, 19, 0, 2), 57003, 53, q))
	if resp, ok := expectUDPPayload(t, h, 53, 57003, 250*time.Millisecond); ok {
		t.Fatalf("expected TXT DNS query dropped, got %x", resp)
	}
	if queries.Load() != 0 {
		t.Fatalf("expected 0 upstream DNS queries, got %d", queries.Load())
	}
}

// T2-A5-01..05: ICMP boundary probes (oversized/short, timestamp/mask, multicast/RS, 500 flood, spoofed src).
func TestE2E_T2_A5_01_OversizedAndTruncatedICMPv4Suppressed(t *testing.T) {
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	dstIP := net.IPv4(172, 19, 0, 2).To4()
	h.injectIPv4(buildIPv4ICMPPacket(srcIP, dstIP, header.ICMPv4Echo, 0, 1, 1, []byte("tiny")))
	h.injectIPv4(buildIPv4ICMPPacket(srcIP, dstIP, header.ICMPv4Echo, 0, 1, 2, bytes.Repeat([]byte("A"), 1472)))
	assertNoICMPPackets(t, h, 200*time.Millisecond)
}

func TestE2E_T2_A5_02_ICMPv4TimestampAndAddressMaskSuppressed(t *testing.T) {
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	for _, dst := range []net.IP{srcIP, net.IPv4(172, 19, 0, 2)} {
		h.injectIPv4(buildIPv4ICMPPacket(srcIP, dst, header.ICMPv4Timestamp, 0, 1, 1, make([]byte, 12)))
		h.injectIPv4(buildIPv4ICMPPacket(srcIP, dst, header.ICMPv4Type(17), 0, 1, 2, make([]byte, 4)))
	}
	assertNoICMPPackets(t, h, 200*time.Millisecond)
}

func TestE2E_T2_A5_03_ICMPv6MulticastAndRouterSolicitationSuppressed(t *testing.T) {
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcV6 := net.ParseIP("fdfe:dcba:9876::1")
	h.injectIPv6(buildIPv6ICMPPacket(srcV6, net.ParseIP("ff02::1"), header.ICMPv6EchoRequest, 0, 1, 1, []byte("mcast")))
	h.injectIPv6(buildIPv6ICMPPacket(srcV6, net.ParseIP("ff02::2"), header.ICMPv6RouterSolicit, 0, 0, 0, nil))
	assertNoICMPPackets(t, h, 200*time.Millisecond)
}

func TestE2E_T2_A5_04_HighRate500ICMPFloodDoesNotStarveTCP(t *testing.T) {
	echoIP, echoPort, _ := startTunTCPEchoServer(t)
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	for i := 0; i < 500; i++ {
		h.injectIPv4(buildIPv4ICMPPacket(srcIP, net.IPv4(172, 19, 0, 2), header.ICMPv4Echo, 0, 9, uint16(i), []byte("flood")))
	}
	for i := 0; i < 5; i++ {
		h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, uint16(57500+i), echoPort, 1, 0, header.TCPFlagSyn, nil))
		if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagSyn|header.TCPFlagAck, 2*time.Second); !ok {
			t.Fatalf("TCP connection %d starved during ICMP flood", i)
		}
	}
}

func TestE2E_T2_A5_05_ICMPWithSpoofedSourceIPSuppressed(t *testing.T) {
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return true })
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	h.injectIPv4(buildIPv4ICMPPacket(net.IPv4(10, 99, 99, 99), net.IPv4(172, 19, 0, 2), header.ICMPv4Echo, 0, 1, 1, []byte("spoof")))
	assertNoICMPPackets(t, h, 200*time.Millisecond)
}

// -----------------------------------------------------------------------------
// Tier 3 & Tier 4 Go TUN Tests
// -----------------------------------------------------------------------------

// T3-04: API 26-28 allow vs API 29+ fail-closed INVALID_UID (-1) on identical TUN packets.
func TestE2E_T3_04_API26To28AllowVsAPI29FailClosed(t *testing.T) {
	echoIP, echoPort, _ := startTunTCPEchoServer(t)
	var failClosed atomic.Bool
	failClosed.Store(false) // API 26-28 mode: guard allows
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool {
		return !failClosed.Load()
	})
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, 58000, echoPort, 1, 0, header.TCPFlagSyn, nil))
	if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagSyn|header.TCPFlagAck, 2*time.Second); !ok {
		t.Fatal("expected API 26-28 mode to allow TCP flow")
	}

	failClosed.Store(true) // API 29+ INVALID_UID (-1) fail-closed
	h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, 58001, echoPort, 1, 0, header.TCPFlagSyn, nil))
	if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagRst, time.Second); !ok {
		t.Fatal("expected API 29+ fail-closed mode to return TCP RST")
	}
}

// T3-05: API 26-28 & API 29+ ICMP echoes never call TrafficGuard and stay suppressed.
func TestE2E_T3_05_API26AndAPI29ICMPEchoNeverCallsGuard(t *testing.T) {
	for _, allow := range []bool{true, false} {
		guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool { return allow })
		cfg := buildTunConfigJSON(t, "", true, nil)
		h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

		h.injectIPv4(buildIPv4ICMPPacket(net.IPv4(172, 19, 0, 1), net.IPv4(172, 19, 0, 2), header.ICMPv4Echo, 0, 1, 1, []byte("x")))
		h.injectIPv6(buildIPv6ICMPPacket(net.ParseIP("fdfe:dcba:9876::1"), net.ParseIP("fdfe:dcba:9876::2"), header.ICMPv6EchoRequest, 0, 1, 1, []byte("x")))
		assertNoICMPPackets(t, h, 150*time.Millisecond)
		if guard.CountProto(1) != 0 || guard.CountProto(58) != 0 {
			t.Fatalf("allow=%v: ICMP leaked into TrafficGuard", allow)
		}
		_ = yumi.Stop()
	}
}

// T3-06: UDP port reuse transitioning from allowed DNS (172.19.0.2:53) to disallowed external UDP.
func TestE2E_T3_06_UDPPortReuseAllowedDNSToDisallowedExternalUDP(t *testing.T) {
	dnsAddr, _ := startLocalUDPUpstreamDNS(t, net.IPv4(93, 184, 216, 34))
	extIP, extPort, extPkts := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")

	guard := newRecordingGuard(func(_ int, _ []byte, _ int, dstIP []byte, dstPort int) bool {
		return dstPort == 53 && net.IP(dstIP).Equal(net.IPv4(172, 19, 0, 2))
	})
	cfg := buildTunConfigJSON(t, dnsAddr, true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	h.injectIPv4(buildIPv4UDPPacket(srcIP, net.IPv4(172, 19, 0, 2), 58001, 53, buildDNSQueryWire(0x5555, "ok.example", 1)))
	if resp, ok := expectUDPPayload(t, h, 53, 58001, 2*time.Second); !ok || len(resp) < 12 {
		t.Fatalf("expected allowed DNS response, got ok=%v len=%d", ok, len(resp))
	}

	h.injectIPv4(buildIPv4UDPPacket(srcIP, extIP.To4(), 58001, extPort, []byte("leak")))
	if reply, ok := expectUDPPayload(t, h, extPort, 58001, 250*time.Millisecond); ok {
		t.Fatalf("expected external UDP to drop after DNS on same srcPort, got %q", reply)
	}
	if extPkts.Load() != 0 {
		t.Fatalf("expected 0 packets at external UDP server, got %d", extPkts.Load())
	}
}

// T3-07: Dual-stack TCP/UDP over IPv4 (172.19.0.1) and IPv6 (fdfe:dcba:9876::1).
func TestE2E_T3_07_DualStackTCPUDPIPv4AndIPv6Guard(t *testing.T) {
	echoIP, echoPort, _ := startTunTCPEchoServer(t)
	udp6IP, udp6Port, _ := startTunUDPEchoServer(t, "udp6", "[::1]:0")

	guard := newRecordingGuard(func(proto int, srcIP []byte, _ int, _ []byte, _ int) bool {
		// Allow UDP IPv6, deny TCP IPv4 & IPv6.
		return proto == 17 && len(srcIP) == 16
	})
	cfg := buildTunConfigJSON(t, "", true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	h.injectIPv4(buildIPv4TCPPacket(net.IPv4(172, 19, 0, 1), echoIP, 58100, echoPort, 1, 0, header.TCPFlagSyn, nil))
	if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagRst, time.Second); !ok {
		t.Fatal("expected IPv4 TCP RST")
	}

	h.injectIPv6(buildIPv6TCPPacket(net.ParseIP("fdfe:dcba:9876::1"), net.ParseIP("2001:db8::2"), 58101, 443, 1, 0, header.TCPFlagSyn, nil))
	if _, ok := expectTCPFlags(t, h, 443, header.TCPFlagRst, time.Second); !ok {
		t.Fatal("expected IPv6 TCP RST")
	}

	h.injectIPv6(buildIPv6UDPPacket(net.ParseIP("fdfe:dcba:9876::1"), udp6IP.To16(), 58102, udp6Port, []byte("v6-ok")))
	if reply, ok := expectUDPPayload(t, h, udp6Port, 58102, 2*time.Second); !ok || string(reply) != "v6-ok" {
		t.Fatalf("expected IPv6 UDP echo, got ok=%v reply=%q", ok, reply)
	}
}

// T3-10: Protocol hot-switch via SelectOutbound while excluded app attempts tun0 reconnect.
func TestE2E_T3_10_ProtocolHotSwitchSelectOutboundWhileExcludedReconnecting(t *testing.T) {
	echoIP, echoPort, _ := startTunTCPEchoServer(t)
	guard := newRecordingGuard(func(_ int, _ []byte, srcPort int, _ []byte, _ int) bool {
		return srcPort >= 58201 && srcPort < 58300 // 58300+ excluded, 58201..58299 allowed
	})
	outbounds := []map[string]any{
		{"tag": yumi.NodeTagPrefix + "a", "protocol": "freedom", "settings": map[string]any{"redirect": "127.0.0.1:0"}},
		{"tag": yumi.NodeTagPrefix + "b", "protocol": "freedom", "settings": map[string]any{"redirect": "127.0.0.1:0"}},
	}
	cfg := buildTunConfigJSON(t, "", true, outbounds)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"a", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	for i, tag := range []string{yumi.NodeTagPrefix + "a", yumi.NodeTagPrefix + "b"} {
		if err := h.selectOutbound(tag); err != nil {
			t.Fatalf("selectOutbound(%s): %v", tag, err)
		}
		h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, uint16(58300+i), echoPort, uint32(i+1), 0, header.TCPFlagSyn, nil))
		if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagRst, time.Second); !ok {
			t.Fatalf("%s: expected excluded TCP RST", tag)
		}
		h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, uint16(58201+i), echoPort, uint32(i+1), 0, header.TCPFlagSyn, nil))
		if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagSyn|header.TCPFlagAck, 2*time.Second); !ok {
			t.Fatalf("%s: expected allowed TCP SYN-ACK", tag)
		}
	}
}

// T4-01: RKNHardering excluded-app attack simulation on tun0 over IPv4 & IPv6 + allowed browser.
func TestE2E_T4_01_RKNHarderingAttackSimulationOnTun0(t *testing.T) {
	dnsAddr, _ := startLocalUDPUpstreamDNS(t, net.IPv4(93, 184, 216, 34))
	echoIP, echoPort, _ := startTunTCPEchoServer(t)

	// Browser uses source ports >= 60000 (allowed); RKNHardering uses < 60000 (excluded).
	guard := newRecordingGuard(func(_ int, _ []byte, srcPort int, _ []byte, _ int) bool {
		return srcPort >= 60000
	})
	cfg := buildTunConfigJSON(t, dnsAddr, true, nil)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcV4 := net.IPv4(172, 19, 0, 1).To4()
	srcV6 := net.ParseIP("fdfe:dcba:9876::1")

	// 1. RKNHardering direct tun0 probes (IPv4 TCP, IPv6 TCP, DNS UDP/TCP, ICMPv4/v6, loopback).
	h.injectIPv4(buildIPv4TCPPacket(srcV4, echoIP, 59001, echoPort, 1, 0, header.TCPFlagSyn, nil))
	if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagRst, time.Second); !ok {
		t.Fatal("RKNHardering IPv4 TCP probe did not receive RST")
	}
	h.injectIPv6(buildIPv6TCPPacket(srcV6, net.ParseIP("2001:db8::443"), 59002, 443, 1, 0, header.TCPFlagSyn, nil))
	if _, ok := expectTCPFlags(t, h, 443, header.TCPFlagRst, time.Second); !ok {
		t.Fatal("RKNHardering IPv6 TCP probe did not receive RST")
	}
	h.injectIPv4(buildIPv4UDPPacket(srcV4, net.IPv4(172, 19, 0, 2), 59003, 53, buildDNSQueryWire(0x9001, "ipify.org", 1)))
	if resp, ok := expectUDPPayload(t, h, 53, 59003, 200*time.Millisecond); ok {
		t.Fatalf("RKNHardering DNS probe unexpectedly received answer: %x", resp)
	}
	h.injectIPv4(buildIPv4ICMPPacket(srcV4, net.IPv4(172, 19, 0, 2), header.ICMPv4Echo, 0, 1, 1, []byte("rkn")))
	h.injectIPv6(buildIPv6ICMPPacket(srcV6, srcV6, header.ICMPv6EchoRequest, 0, 1, 2, []byte("rkn")))
	assertNoICMPPackets(t, h, 150*time.Millisecond)

	// 2. Allowed browser resolves DNS and opens TCP connection cleanly.
	h.injectIPv4(buildIPv4UDPPacket(srcV4, net.IPv4(172, 19, 0, 2), 60001, 53, buildDNSQueryWire(0x9002, "mozilla.org", 1)))
	if resp, ok := expectUDPPayload(t, h, 53, 60001, 2*time.Second); !ok || len(resp) < 12 {
		t.Fatalf("allowed browser DNS failed: ok=%v len=%d", ok, len(resp))
	}
	h.injectIPv4(buildIPv4TCPPacket(srcV4, echoIP, 60002, echoPort, 1, 0, header.TCPFlagSyn, nil))
	if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagSyn|header.TCPFlagAck, 2*time.Second); !ok {
		t.Fatal("allowed browser TCP failed")
	}
}

// T4-03: Protocol-switching leak resistance across multiple outbound tags.
func TestE2E_T4_03_ProtocolSwitchingLeakResistance(t *testing.T) {
	echoIP, echoPort, _ := startTunTCPEchoServer(t)
	udpIP, udpPort, udpPkts := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")

	guard := newRecordingGuard(func(_ int, _ []byte, srcPort int, _ []byte, _ int) bool {
		return srcPort >= 61000
	})
	outbounds := []map[string]any{
		{"tag": yumi.NodeTagPrefix + "vless", "protocol": "freedom", "settings": map[string]any{"redirect": "127.0.0.1:0"}},
		{"tag": yumi.NodeTagPrefix + "trojan", "protocol": "freedom", "settings": map[string]any{"redirect": "127.0.0.1:0"}},
		{"tag": yumi.NodeTagPrefix + "ss", "protocol": "freedom", "settings": map[string]any{"redirect": "127.0.0.1:0"}},
		{"tag": yumi.NodeTagPrefix + "hy2", "protocol": "freedom", "settings": map[string]any{"redirect": "127.0.0.1:0"}},
		{"tag": yumi.NodeTagPrefix + "wg", "protocol": "freedom", "settings": map[string]any{"redirect": "127.0.0.1:0"}},
	}
	cfg := buildTunConfigJSON(t, "", true, outbounds)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"vless", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()
	for idx, ob := range outbounds {
		tag := ob["tag"].(string)
		if err := h.selectOutbound(tag); err != nil {
			t.Fatalf("selectOutbound(%s): %v", tag, err)
		}
		// Excluded app fails TCP & UDP.
		h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, uint16(59500+idx), echoPort, 1, 0, header.TCPFlagSyn, nil))
		if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagRst, time.Second); !ok {
			t.Fatalf("%s: excluded TCP did not get RST", tag)
		}
		beforeUDP := udpPkts.Load()
		h.injectIPv4(buildIPv4UDPPacket(srcIP, udpIP.To4(), uint16(59600+idx), udpPort, []byte("evil")))
		if _, ok := expectUDPPayload(t, h, udpPort, uint16(59600+idx), 150*time.Millisecond); ok {
			t.Fatalf("%s: excluded UDP unexpectedly succeeded", tag)
		}
		if udpPkts.Load() != beforeUDP {
			t.Fatalf("%s: excluded UDP reached server", tag)
		}

		// Allowed app succeeds TCP & UDP.
		h.injectIPv4(buildIPv4TCPPacket(srcIP, echoIP, uint16(61000+idx), echoPort, 1, 0, header.TCPFlagSyn, nil))
		if _, ok := expectTCPFlags(t, h, echoPort, header.TCPFlagSyn|header.TCPFlagAck, 2*time.Second); !ok {
			t.Fatalf("%s: allowed TCP failed", tag)
		}
		h.injectIPv4(buildIPv4UDPPacket(srcIP, udpIP.To4(), uint16(61100+idx), udpPort, []byte("good")))
		if reply, ok := expectUDPPayload(t, h, udpPort, uint16(61100+idx), 2*time.Second); !ok || string(reply) != "good" {
			t.Fatalf("%s: allowed UDP failed: ok=%v reply=%q", tag, ok, reply)
		}
	}
}

// T5: Active-flow UDP 5-tuple port reuse with production connIdle=1800s blocks excluded apps
// immediately for DNS (172.19.0.2:53) and after short TTL for non-DNS UDP.
func TestE2E_T5_ActiveFlowUDPPortReuseWith1800sConnIdle(t *testing.T) {
	dnsAddr, dnsQueries := startLocalUDPUpstreamDNS(t, net.IPv4(93, 184, 216, 34))
	udpIP, udpPort, udpPkts := startTunUDPEchoServer(t, "udp4", "127.0.0.1:0")

	var allowFlag atomic.Bool
	allowFlag.Store(true)
	guard := newRecordingGuard(func(int, []byte, int, []byte, int) bool {
		return allowFlag.Load()
	})

	cfg := buildTunConfigJSON(t, dnsAddr, true, nil)
	// Enforce production connIdle = 1800s explicitly (matching XrayConfigFactory.CONN_IDLE_SECONDS).
	cfg = strings.Replace(cfg, `"log":`, `"policy":{"levels":{"0":{"connIdle":1800,"uplinkOnly":30,"downlinkOnly":30}}},"log":`, 1)
	h := startGuardedTunHarness(t, cfg, yumi.NodeTagPrefix+"direct", guard, false)

	srcIP := net.IPv4(172, 19, 0, 1).To4()

	// 1. Allowed App A resolves DNS via 172.19.0.2:53 on source port 55001.
	h.injectIPv4(buildIPv4UDPPacket(srcIP, net.IPv4(172, 19, 0, 2), 55001, 53, buildDNSQueryWire(0x5501, "allowed.example", 1)))
	if resp, ok := expectUDPPayload(t, h, 53, 55001, 2*time.Second); !ok || len(resp) < 12 {
		t.Fatalf("allowed DNS query failed: ok=%v len=%d", ok, len(resp))
	}
	if dnsQueries.Load() != 1 {
		t.Fatalf("expected 1 upstream DNS query, got %d", dnsQueries.Load())
	}

	// 2. Allowed App A sends non-DNS UDP on source port 55000.
	h.injectIPv4(buildIPv4UDPPacket(srcIP, udpIP.To4(), 55000, udpPort, []byte("allowed-udp")))
	if reply, ok := expectUDPPayload(t, h, udpPort, 55000, 2*time.Second); !ok || string(reply) != "allowed-udp" {
		t.Fatalf("allowed UDP failed: ok=%v reply=%q", ok, reply)
	}
	if udpPkts.Load() != 1 {
		t.Fatalf("expected 1 UDP packet at server, got %d", udpPkts.Load())
	}

	// 3. Flip guard to false while both 1800s udpConn flows remain active.
	allowFlag.Store(false)

	// DNS 172.19.0.2:53 MUST block immediately (0s wait) even on reused source port 55001.
	h.injectIPv4(buildIPv4UDPPacket(srcIP, net.IPv4(172, 19, 0, 2), 55001, 53, buildDNSQueryWire(0x5502, "leak.example", 1)))
	if resp, ok := expectUDPPayload(t, h, 53, 55001, 200*time.Millisecond); ok {
		t.Fatalf("excluded app bypassed TrafficGuard on active DNS flow port 55001: %x", resp)
	}
	if dnsQueries.Load() != 1 {
		t.Fatalf("excluded DNS port reuse leaked upstream query: count=%d", dnsQueries.Load())
	}

	// Non-DNS UDP MUST block after short 2s re-validation TTL even though connIdle is 1800s.
	time.Sleep(2100 * time.Millisecond)
	h.injectIPv4(buildIPv4UDPPacket(srcIP, udpIP.To4(), 55000, udpPort, []byte("excluded-udp")))
	if reply, ok := expectUDPPayload(t, h, udpPort, 55000, 200*time.Millisecond); ok {
		t.Fatalf("excluded app bypassed TrafficGuard on active non-DNS UDP flow port 55000: %q", reply)
	}
	if udpPkts.Load() != 1 {
		t.Fatalf("excluded UDP port reuse leaked packet to server: count=%d", udpPkts.Load())
	}
}
