package tun

import (
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	v2net "github.com/xtls/xray-core/common/net"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/checksum"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type panickingNilGuard struct {
	allow bool
}

func (g *panickingNilGuard) AllowConnection(int, []byte, int, []byte, int) bool {
	return g.allow
}

type guardCall struct {
	proto   int
	srcIP   []byte
	srcPort int
	dstIP   []byte
	dstPort int
	allowed bool
}

type mockGuard struct {
	mu    sync.Mutex
	fn    func(proto int, srcIP []byte, srcPort int, dstIP []byte, dstPort int) bool
	calls []guardCall
}

func (m *mockGuard) AllowConnection(proto int, srcIP []byte, srcPort int, dstIP []byte, dstPort int) bool {
	allowed := false
	if m.fn != nil {
		allowed = m.fn(proto, srcIP, srcPort, dstIP, dstPort)
	}
	srcCopy := append([]byte(nil), srcIP...)
	dstCopy := append([]byte(nil), dstIP...)
	m.mu.Lock()
	m.calls = append(m.calls, guardCall{
		proto:   proto,
		srcIP:   srcCopy,
		srcPort: srcPort,
		dstIP:   dstCopy,
		dstPort: dstPort,
		allowed: allowed,
	})
	m.mu.Unlock()
	return allowed
}

func (m *mockGuard) callCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.calls)
}

func (m *mockGuard) lastCall() (guardCall, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.calls) == 0 {
		return guardCall{}, false
	}
	return m.calls[len(m.calls)-1], true
}

func injectIPv4(ep *channel.Endpoint, raw []byte) {
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
		Payload: buffer.MakeWithData(raw),
	})
	ep.InjectInbound(header.IPv4ProtocolNumber, pkt)
	pkt.DecRef()
}

func injectIPv6(ep *channel.Endpoint, raw []byte) {
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
		Payload: buffer.MakeWithData(raw),
	})
	ep.InjectInbound(header.IPv6ProtocolNumber, pkt)
	pkt.DecRef()
}

func readPacketTimeout(ep *channel.Endpoint, d time.Duration) (*stack.PacketBuffer, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), d)
	defer cancel()
	pkt := ep.ReadContext(ctx)
	if pkt == nil {
		return nil, false
	}
	return pkt, true
}

func packetToBytes(pkt *stack.PacketBuffer) []byte {
	v := pkt.ToView()
	defer v.Release()
	return append([]byte(nil), v.AsSlice()...)
}

func buildIPv4TCPSYN(srcIP, dstIP [4]byte, srcPort, dstPort uint16, seq uint32) []byte {
	totalLen := header.IPv4MinimumSize + header.TCPMinimumSize
	buf := make([]byte, totalLen)
	ip := header.IPv4(buf[:header.IPv4MinimumSize])
	ip.Encode(&header.IPv4Fields{
		TotalLength: uint16(totalLen),
		TTL:         64,
		Protocol:    uint8(header.TCPProtocolNumber),
		SrcAddr:     tcpip.AddrFrom4(srcIP),
		DstAddr:     tcpip.AddrFrom4(dstIP),
	})
	ip.SetChecksum(^ip.CalculateChecksum())

	tcpHdr := header.TCP(buf[header.IPv4MinimumSize:])
	tcpHdr.Encode(&header.TCPFields{
		SrcPort:    srcPort,
		DstPort:    dstPort,
		SeqNum:     seq,
		DataOffset: header.TCPMinimumSize,
		Flags:      header.TCPFlagSyn,
		WindowSize: 65535,
	})
	xsum := header.PseudoHeaderChecksum(header.TCPProtocolNumber, tcpip.AddrFrom4(srcIP), tcpip.AddrFrom4(dstIP), uint16(header.TCPMinimumSize))
	tcpHdr.SetChecksum(^tcpHdr.CalculateChecksum(xsum))
	return buf
}

func buildIPv4TCPPacket(srcIP, dstIP [4]byte, srcPort, dstPort uint16, seq, ack uint32, flags header.TCPFlags, payload []byte) []byte {
	tcpLen := header.TCPMinimumSize + len(payload)
	totalLen := header.IPv4MinimumSize + tcpLen
	buf := make([]byte, totalLen)
	copy(buf[header.IPv4MinimumSize+header.TCPMinimumSize:], payload)

	ip := header.IPv4(buf[:header.IPv4MinimumSize])
	ip.Encode(&header.IPv4Fields{
		TotalLength: uint16(totalLen),
		TTL:         64,
		Protocol:    uint8(header.TCPProtocolNumber),
		SrcAddr:     tcpip.AddrFrom4(srcIP),
		DstAddr:     tcpip.AddrFrom4(dstIP),
	})
	ip.SetChecksum(^ip.CalculateChecksum())

	tcpHdr := header.TCP(buf[header.IPv4MinimumSize:])
	tcpHdr.Encode(&header.TCPFields{
		SrcPort:    srcPort,
		DstPort:    dstPort,
		SeqNum:     seq,
		AckNum:     ack,
		DataOffset: header.TCPMinimumSize,
		Flags:      flags,
		WindowSize: 65535,
	})
	xsum := header.PseudoHeaderChecksum(header.TCPProtocolNumber, tcpip.AddrFrom4(srcIP), tcpip.AddrFrom4(dstIP), uint16(tcpLen))
	tcpHdr.SetChecksum(^tcpHdr.CalculateChecksum(checksum.Checksum(payload, xsum)))
	return buf
}

func buildIPv6TCPSYN(srcIP, dstIP [16]byte, srcPort, dstPort uint16, seq uint32) []byte {
	totalLen := header.IPv6MinimumSize + header.TCPMinimumSize
	buf := make([]byte, totalLen)
	ip := header.IPv6(buf[:header.IPv6MinimumSize])
	ip.Encode(&header.IPv6Fields{
		PayloadLength:     header.TCPMinimumSize,
		TransportProtocol: header.TCPProtocolNumber,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16(srcIP),
		DstAddr:           tcpip.AddrFrom16(dstIP),
	})

	tcpHdr := header.TCP(buf[header.IPv6MinimumSize:])
	tcpHdr.Encode(&header.TCPFields{
		SrcPort:    srcPort,
		DstPort:    dstPort,
		SeqNum:     seq,
		DataOffset: header.TCPMinimumSize,
		Flags:      header.TCPFlagSyn,
		WindowSize: 65535,
	})
	xsum := header.PseudoHeaderChecksum(header.TCPProtocolNumber, tcpip.AddrFrom16(srcIP), tcpip.AddrFrom16(dstIP), uint16(header.TCPMinimumSize))
	tcpHdr.SetChecksum(^tcpHdr.CalculateChecksum(xsum))
	return buf
}

func buildIPv4UDP(srcIP, dstIP [4]byte, srcPort, dstPort uint16, payload []byte) []byte {
	udpLen := header.UDPMinimumSize + len(payload)
	totalLen := header.IPv4MinimumSize + udpLen
	buf := make([]byte, totalLen)
	copy(buf[header.IPv4MinimumSize+header.UDPMinimumSize:], payload)

	ip := header.IPv4(buf[:header.IPv4MinimumSize])
	ip.Encode(&header.IPv4Fields{
		TotalLength: uint16(totalLen),
		TTL:         64,
		Protocol:    uint8(header.UDPProtocolNumber),
		SrcAddr:     tcpip.AddrFrom4(srcIP),
		DstAddr:     tcpip.AddrFrom4(dstIP),
	})
	ip.SetChecksum(^ip.CalculateChecksum())

	udpHdr := header.UDP(buf[header.IPv4MinimumSize:])
	udpHdr.Encode(&header.UDPFields{
		SrcPort: srcPort,
		DstPort: dstPort,
		Length:  uint16(udpLen),
	})
	xsum := header.PseudoHeaderChecksum(header.UDPProtocolNumber, tcpip.AddrFrom4(srcIP), tcpip.AddrFrom4(dstIP), uint16(udpLen))
	udpHdr.SetChecksum(^udpHdr.CalculateChecksum(checksum.Checksum(payload, xsum)))
	return buf
}

func buildIPv6UDP(srcIP, dstIP [16]byte, srcPort, dstPort uint16, payload []byte) []byte {
	udpLen := header.UDPMinimumSize + len(payload)
	totalLen := header.IPv6MinimumSize + udpLen
	buf := make([]byte, totalLen)
	copy(buf[header.IPv6MinimumSize+header.UDPMinimumSize:], payload)

	ip := header.IPv6(buf[:header.IPv6MinimumSize])
	ip.Encode(&header.IPv6Fields{
		PayloadLength:     uint16(udpLen),
		TransportProtocol: header.UDPProtocolNumber,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16(srcIP),
		DstAddr:           tcpip.AddrFrom16(dstIP),
	})

	udpHdr := header.UDP(buf[header.IPv6MinimumSize:])
	udpHdr.Encode(&header.UDPFields{
		SrcPort: srcPort,
		DstPort: dstPort,
		Length:  uint16(udpLen),
	})
	xsum := header.PseudoHeaderChecksum(header.UDPProtocolNumber, tcpip.AddrFrom16(srcIP), tcpip.AddrFrom16(dstIP), uint16(udpLen))
	udpHdr.SetChecksum(^udpHdr.CalculateChecksum(checksum.Checksum(payload, xsum)))
	return buf
}

func buildIPv4ICMPEcho(srcIP, dstIP [4]byte, ident, seq uint16, payload []byte) []byte {
	icmpLen := header.ICMPv4MinimumSize + len(payload)
	totalLen := header.IPv4MinimumSize + icmpLen
	buf := make([]byte, totalLen)
	copy(buf[header.IPv4MinimumSize+header.ICMPv4MinimumSize:], payload)

	ip := header.IPv4(buf[:header.IPv4MinimumSize])
	ip.Encode(&header.IPv4Fields{
		TotalLength: uint16(totalLen),
		TTL:         64,
		Protocol:    uint8(header.ICMPv4ProtocolNumber),
		SrcAddr:     tcpip.AddrFrom4(srcIP),
		DstAddr:     tcpip.AddrFrom4(dstIP),
	})
	ip.SetChecksum(^ip.CalculateChecksum())

	icmpHdr := header.ICMPv4(buf[header.IPv4MinimumSize:])
	icmpHdr.SetType(header.ICMPv4Echo)
	icmpHdr.SetCode(0)
	icmpHdr.SetIdent(ident)
	icmpHdr.SetSequence(seq)
	icmpHdr.SetChecksum(0)
	icmpHdr.SetChecksum(header.ICMPv4Checksum(icmpHdr, 0))
	return buf
}

func buildIPv6ICMPEcho(srcIP, dstIP [16]byte, ident, seq uint16, payload []byte) []byte {
	icmpLen := header.ICMPv6MinimumSize + len(payload)
	totalLen := header.IPv6MinimumSize + icmpLen
	buf := make([]byte, totalLen)
	copy(buf[header.IPv6MinimumSize+header.ICMPv6MinimumSize:], payload)

	ip := header.IPv6(buf[:header.IPv6MinimumSize])
	ip.Encode(&header.IPv6Fields{
		PayloadLength:     uint16(icmpLen),
		TransportProtocol: header.ICMPv6ProtocolNumber,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16(srcIP),
		DstAddr:           tcpip.AddrFrom16(dstIP),
	})

	icmpHdr := header.ICMPv6(buf[header.IPv6MinimumSize:])
	icmpHdr.SetType(header.ICMPv6EchoRequest)
	icmpHdr.SetCode(0)
	icmpHdr.SetIdent(ident)
	icmpHdr.SetSequence(seq)
	icmpHdr.SetChecksum(0)
	icmpHdr.SetChecksum(header.ICMPv6Checksum(header.ICMPv6ChecksumParams{
		Header: icmpHdr,
		Src:    tcpip.AddrFrom16(srcIP),
		Dst:    tcpip.AddrFrom16(dstIP),
	}))
	return buf
}

func TestNormalizeAddr_IPv4IPv6MappedAndInvalid(t *testing.T) {
	v4 := tcpip.AddrFrom4([4]byte{172, 19, 0, 1})
	b4, addr4, ok := normalizeAddr(v4)
	if !ok || len(b4) != 4 || !bytes.Equal(b4, []byte{172, 19, 0, 1}) || addr4.String() != "172.19.0.1" {
		t.Fatalf("unexpected ipv4 normalization: ok=%v b=%v addr=%v", ok, b4, addr4)
	}

	mapped := tcpip.AddrFrom16([16]byte{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 192, 0, 2, 55})
	bm, addrm, ok := normalizeAddr(mapped)
	if !ok || len(bm) != 4 || !bytes.Equal(bm, []byte{192, 0, 2, 55}) || !addrm.Is4() {
		t.Fatalf("expected IPv4-mapped IPv6 to unmap to 4 bytes, got ok=%v len=%d bytes=%v", ok, len(bm), bm)
	}

	v6 := tcpip.AddrFrom16([16]byte{0xfd, 0xfe, 0xdc, 0xba, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})
	b6, addr6, ok := normalizeAddr(v6)
	if !ok || len(b6) != 16 || !addr6.Is6() {
		t.Fatalf("unexpected ipv6 normalization: ok=%v len=%d", ok, len(b6))
	}

	if _, _, ok := normalizeAddr(tcpip.Address{}); ok {
		t.Fatal("empty tcpip.Address must fail normalization")
	}
	if _, _, ok := normalizeAddr(tcpip.AddrFrom4([4]byte{0, 0, 0, 0})); ok {
		t.Fatal("unspecified IPv4 must fail normalization")
	}
	if _, _, ok := normalizeAddr(tcpip.AddrFrom16([16]byte{})); ok {
		t.Fatal("unspecified IPv6 must fail normalization")
	}
}

func TestTCPPreSynAck_DenySendsRST_AllowDispatches(t *testing.T) {
	ep := channel.New(64, 1500, "")
	defer ep.Close()

	var dispatched atomic.Int32
	guard := &mockGuard{
		fn: func(proto int, srcIP []byte, srcPort int, dstIP []byte, dstPort int) bool {
			return dstPort == 443 // allow 443, deny everything else
		},
	}

	s, err := NewStack(context.Background(), StackOptions{
		Endpoint: ep,
		Guard:    guard,
	}, func(conn net.Conn, destination v2net.Destination) {
		defer conn.Close()
		dispatched.Add(1)
		buf := make([]byte, 128)
		n, err := conn.Read(buf)
		if err != nil && err != io.EOF {
			return
		}
		if n > 0 {
			_, _ = conn.Write(buf[:n])
		}
	})
	if err != nil {
		t.Fatalf("NewStack failed: %v", err)
	}
	if err := s.Start(); err != nil {
		t.Fatalf("stack.Start failed: %v", err)
	}
	defer s.Close()

	srcV4 := [4]byte{172, 19, 0, 1}
	dstV4 := [4]byte{93, 184, 216, 34}

	// 1. Denied IPv4 TCP SYN -> must receive RST immediately and NOT dispatch.
	injectIPv4(ep, buildIPv4TCPSYN(srcV4, dstV4, 41001, 80, 1000))
	pkt, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected TCP RST response for denied IPv4 SYN")
	}
	rawResp := packetToBytes(pkt)
	pkt.DecRef()
	ipResp := header.IPv4(rawResp)
	tcpResp := header.TCP(rawResp[ipResp.HeaderLength():])
	if !tcpResp.Flags().Contains(header.TCPFlagRst) {
		t.Fatalf("expected RST flag on denied SYN response, got flags=%v", tcpResp.Flags())
	}
	if tcpResp.Flags().Contains(header.TCPFlagSyn) {
		t.Fatalf("denied SYN must not receive SYN-ACK, got flags=%v", tcpResp.Flags())
	}
	if dispatched.Load() != 0 {
		t.Fatalf("denied TCP SYN must not invoke dispatcher, count=%d", dispatched.Load())
	}
	call, ok := guard.lastCall()
	if !ok || call.proto != 6 || call.srcPort != 41001 || call.dstPort != 80 || len(call.srcIP) != 4 || len(call.dstIP) != 4 {
		t.Fatalf("unexpected guard call metadata: %+v", call)
	}

	// 2. Zero port must fail-closed with RST without calling guard.
	callsBefore := guard.callCount()
	injectIPv4(ep, buildIPv4TCPSYN(srcV4, dstV4, 0, 443, 1001))
	pktZero, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected RST for zero srcPort")
	}
	rawZero := packetToBytes(pktZero)
	pktZero.DecRef()
	tcpZero := header.TCP(rawZero[header.IPv4(rawZero).HeaderLength():])
	if !tcpZero.Flags().Contains(header.TCPFlagRst) {
		t.Fatalf("expected RST on zero port, got flags=%v", tcpZero.Flags())
	}
	if guard.callCount() != callsBefore {
		t.Fatalf("zero port should fail-closed before calling guard")
	}

	// 3. Allowed IPv4 TCP SYN -> completes handshake & echoes data.
	injectIPv4(ep, buildIPv4TCPSYN(srcV4, dstV4, 41002, 443, 2000))
	pktSynAck, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected SYN-ACK for allowed IPv4 SYN")
	}
	rawSynAck := packetToBytes(pktSynAck)
	pktSynAck.DecRef()
	tcpSynAck := header.TCP(rawSynAck[header.IPv4(rawSynAck).HeaderLength():])
	if !tcpSynAck.Flags().Contains(header.TCPFlagSyn | header.TCPFlagAck) {
		t.Fatalf("expected SYN-ACK, got flags=%v", tcpSynAck.Flags())
	}

	// Complete 3-way handshake + send payload.
	injectIPv4(ep, buildIPv4TCPPacket(srcV4, dstV4, 41002, 443, 2001, tcpSynAck.SequenceNumber()+1, header.TCPFlagAck|header.TCPFlagPsh, []byte("hello-v4")))
	deadline := time.Now().Add(2 * time.Second)
	var echoed []byte
	for time.Now().Before(deadline) {
		p, ok := readPacketTimeout(ep, 500*time.Millisecond)
		if !ok {
			break
		}
		b := packetToBytes(p)
		p.DecRef()
		ip := header.IPv4(b)
		th := header.TCP(b[ip.HeaderLength():])
		payload := b[int(ip.HeaderLength())+int(th.DataOffset()):]
		if len(payload) > 0 {
			echoed = append(echoed, payload...)
			break
		}
	}
	if string(echoed) != "hello-v4" {
		t.Fatalf("expected echoed TCP payload 'hello-v4', got %q", string(echoed))
	}
	if dispatched.Load() != 1 {
		t.Fatalf("expected 1 dispatched TCP connection, got %d", dispatched.Load())
	}

	// 4. IPv6 denied TCP SYN -> RST, and IPv6 allowed TCP SYN -> SYN-ACK (fresh endpoint so no lingering v4 FIN).
	epV6 := channel.New(64, 1500, "")
	defer epV6.Close()
	sV6, err := NewStack(context.Background(), StackOptions{
		Endpoint: epV6,
		Guard:    guard,
	}, func(conn net.Conn, destination v2net.Destination) {
		defer conn.Close()
		buf := make([]byte, 128)
		n, _ := conn.Read(buf)
		if n > 0 {
			_, _ = conn.Write(buf[:n])
		}
	})
	if err != nil {
		t.Fatalf("NewStack IPv6 failed: %v", err)
	}
	if err := sV6.Start(); err != nil {
		t.Fatalf("stack.Start IPv6 failed: %v", err)
	}
	defer sV6.Close()

	srcV6 := [16]byte{0xfd, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}
	dstV6 := [16]byte{0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}

	injectIPv6(epV6, buildIPv6TCPSYN(srcV6, dstV6, 42001, 80, 3000))
	pktV6Rst, ok := readPacketTimeout(epV6, 2*time.Second)
	if !ok {
		t.Fatal("expected IPv6 TCP RST")
	}
	rawV6Rst := packetToBytes(pktV6Rst)
	pktV6Rst.DecRef()
	if len(rawV6Rst) < header.IPv6MinimumSize+header.TCPMinimumSize {
		t.Fatalf("expected IPv6 TCP packet >= 60 bytes, got %d", len(rawV6Rst))
	}
	tcpV6Rst := header.TCP(rawV6Rst[header.IPv6MinimumSize:])
	if !tcpV6Rst.Flags().Contains(header.TCPFlagRst) {
		t.Fatalf("expected RST flag on denied IPv6 SYN, got %v", tcpV6Rst.Flags())
	}

	injectIPv6(epV6, buildIPv6TCPSYN(srcV6, dstV6, 42002, 443, 4000))
	pktV6SynAck, ok := readPacketTimeout(epV6, 2*time.Second)
	if !ok {
		t.Fatal("expected IPv6 SYN-ACK for allowed port 443")
	}
	rawV6SynAck := packetToBytes(pktV6SynAck)
	pktV6SynAck.DecRef()
	if len(rawV6SynAck) < header.IPv6MinimumSize+header.TCPMinimumSize {
		t.Fatalf("expected IPv6 TCP SYN-ACK >= 60 bytes, got %d", len(rawV6SynAck))
	}
	tcpV6SynAck := header.TCP(rawV6SynAck[header.IPv6MinimumSize:])
	if !tcpV6SynAck.Flags().Contains(header.TCPFlagSyn | header.TCPFlagAck) {
		t.Fatalf("expected IPv6 SYN-ACK, got %v", tcpV6SynAck.Flags())
	}
}

func TestUDPGuard_FiveTupleDNS53AndPortReuseEviction(t *testing.T) {
	ep := channel.New(64, 1500, "")
	defer ep.Close()

	var allowFlag atomic.Bool
	var allowAltDst atomic.Bool
	guard := &mockGuard{
		fn: func(proto int, srcIP []byte, srcPort int, dstIP []byte, dstPort int) bool {
			if dstPort == 53 && bytes.Equal(dstIP, []byte{8, 8, 8, 8}) {
				return allowAltDst.Load()
			}
			return allowFlag.Load()
		},
	}

	var connMu sync.Mutex
	var activeConns []net.Conn
	var received []string

	s, err := NewStack(context.Background(), StackOptions{
		Endpoint: ep,
		Guard:    guard,
	}, func(conn net.Conn, destination v2net.Destination) {
		connMu.Lock()
		activeConns = append(activeConns, conn)
		connMu.Unlock()
		defer conn.Close()

		buf := make([]byte, 512)
		for {
			n, err := conn.Read(buf)
			if err != nil {
				return
			}
			connMu.Lock()
			received = append(received, string(buf[:n]))
			connMu.Unlock()
			_, _ = conn.Write([]byte("pong:" + string(buf[:n])))
		}
	})
	if err != nil {
		t.Fatalf("NewStack failed: %v", err)
	}
	if err := s.Start(); err != nil {
		t.Fatalf("stack.Start failed: %v", err)
	}
	defer s.Close()

	srcV4 := [4]byte{172, 19, 0, 1}
	dns1 := [4]byte{172, 19, 0, 2}
	dns2 := [4]byte{8, 8, 8, 8}

	// 1. Denied DNS packet -> silent drop (no ICMP Port Unreachable!), no negative cache.
	allowFlag.Store(false)
	injectIPv4(ep, buildIPv4UDP(srcV4, dns1, 51001, 53, []byte("dns-blocked")))
	if p, ok := readPacketTimeout(ep, 120*time.Millisecond); ok {
		p.DecRef()
		t.Fatal("denied UDP must be silently dropped with zero ICMP Port Unreachable")
	}
	if guard.callCount() != 1 {
		t.Fatalf("expected 1 guard call, got %d", guard.callCount())
	}

	// 2. Flip allowFlag to true -> same 5-tuple must now succeed (no negative caching!).
	allowFlag.Store(true)
	injectIPv4(ep, buildIPv4UDP(srcV4, dns1, 51001, 53, []byte("dns-ok-1")))
	p1, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected UDP reply after guard allowed 5-tuple")
	}
	b1 := packetToBytes(p1)
	p1.DecRef()
	ip1 := header.IPv4(b1)
	udpPayload1 := b1[int(ip1.HeaderLength())+header.UDPMinimumSize:]
	if string(udpPayload1) != "pong:dns-ok-1" {
		t.Fatalf("unexpected UDP reply payload %q", string(udpPayload1))
	}
	if guard.callCount() != 2 {
		t.Fatalf("expected 2 guard calls, got %d", guard.callCount())
	}

	// 3. Second packet on DNS (dstPort == 53) re-validates guard immediately; non-DNS UDP within TTL uses flow cache.
	injectIPv4(ep, buildIPv4UDP(srcV4, dns1, 51001, 53, []byte("dns-ok-2")))
	p2, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected second UDP reply on DNS 53")
	}
	p2.DecRef()
	if guard.callCount() != 3 {
		t.Fatalf("DNS 53 must re-validate guard on every packet; got %d calls", guard.callCount())
	}

	// Non-DNS UDP (port 5353) caches for the short allowCacheTTL across consecutive packets.
	injectIPv4(ep, buildIPv4UDP(srcV4, dns1, 51001, 5353, []byte("udp-ttl-1")))
	pTTL1, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected first non-DNS UDP reply")
	}
	pTTL1.DecRef()
	if guard.callCount() != 4 {
		t.Fatalf("expected 4 guard calls after first non-DNS UDP packet, got %d", guard.callCount())
	}
	injectIPv4(ep, buildIPv4UDP(srcV4, dns1, 51001, 5353, []byte("udp-ttl-2")))
	pTTL2, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected second non-DNS UDP reply within TTL")
	}
	pTTL2.DecRef()
	if guard.callCount() != 4 {
		t.Fatalf("non-DNS UDP within TTL must use flow cache without extra guard call; got %d calls", guard.callCount())
	}

	// 4. Same src (172.19.0.1:51001) to DIFFERENT dst (8.8.8.8:53) -> MUST re-check guard!
	allowAltDst.Store(false)
	injectIPv4(ep, buildIPv4UDP(srcV4, dns2, 51001, 53, []byte("alt-denied")))
	if p, ok := readPacketTimeout(ep, 120*time.Millisecond); ok {
		p.DecRef()
		t.Fatal("denied new destination on same srcPort must be dropped")
	}
	if guard.callCount() != 5 {
		t.Fatalf("distinct dst on same src must query guard; got %d calls", guard.callCount())
	}

	allowAltDst.Store(true)
	injectIPv4(ep, buildIPv4UDP(srcV4, dns2, 51001, 53, []byte("alt-allowed")))
	p3, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected reply after alt dst allowed")
	}
	p3.DecRef()
	if guard.callCount() != 6 {
		t.Fatalf("expected 6 guard calls, got %d", guard.callCount())
	}

	// 5. Close active UDP flow -> evicts cache -> port reuse MUST re-query guard.
	connMu.Lock()
	for _, c := range activeConns {
		_ = c.Close()
	}
	activeConns = nil
	connMu.Unlock()

	allowFlag.Store(false)
	injectIPv4(ep, buildIPv4UDP(srcV4, dns1, 51001, 53, []byte("reused-port")))
	if p, ok := readPacketTimeout(ep, 120*time.Millisecond); ok {
		p.DecRef()
		t.Fatal("reused UDP port after flow close must re-query guard and drop when denied")
	}
	if guard.callCount() != 7 {
		t.Fatalf("expected 7 guard calls after flow close + port reuse, got %d", guard.callCount())
	}

	// 6. IPv6 UDP allow & reply.
	allowFlag.Store(true)
	srcV6 := [16]byte{0xfd, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}
	dstV6 := [16]byte{0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0x88, 0x88}
	injectIPv6(ep, buildIPv6UDP(srcV6, dstV6, 52002, 53, []byte("v6-dns")))
	pV6, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected IPv6 UDP reply")
	}
	bV6 := packetToBytes(pV6)
	pV6.DecRef()
	if string(bV6[header.IPv6MinimumSize+header.UDPMinimumSize:]) != "pong:v6-dns" {
		t.Fatalf("unexpected IPv6 UDP reply payload: %q", string(bV6[header.IPv6MinimumSize+header.UDPMinimumSize:]))
	}
}

func TestUDPConnectionFinished_PointerIdentityPreventsStaleEviction(t *testing.T) {
	u := newUDPConnectionHandler(nil, func(net.Conn, v2net.Destination) {}, func([]byte, v2net.Destination, v2net.Destination) error {
		return nil
	})
	srcAddr := netip.MustParseAddr("172.19.0.1")
	srcKey := makeUDPEndpointKey(srcAddr, 12345)
	oldConn := &udpConn{handler: u, egress: make(chan *udpPacket, 1), srcKey: srcKey}
	newConn := &udpConn{handler: u, egress: make(chan *udpPacket, 1), srcKey: srcKey}

	u.Lock()
	u.udpConns[srcKey] = newConn
	u.Unlock()

	// Closing oldConn must close oldConn.egress without removing newConn!
	_ = oldConn.Close()
	_ = oldConn.Close() // idempotent double-close must not panic

	u.RLock()
	cur := u.udpConns[srcKey]
	u.RUnlock()
	if cur != newConn {
		t.Fatal("stale connectionFinished evicted newer conn with same srcKey")
	}
	u.CloseAll()
}

func TestICMPEchoHardening_GuardedDrops_UnguardedReplies(t *testing.T) {
	srcV4 := [4]byte{172, 19, 0, 1}
	dstV4 := [4]byte{172, 19, 0, 2}
	srcV6 := [16]byte{0xfd, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}
	dstV6 := [16]byte{0xfd, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2}

	// 1. Guarded stack: even when guard allows everything, ICMPv4 & ICMPv6 echo requests MUST be dropped.
	epGuarded := channel.New(64, 1500, "")
	defer epGuarded.Close()
	allowAll := &mockGuard{fn: func(int, []byte, int, []byte, int) bool { return true }}
	sGuarded, err := NewStack(context.Background(), StackOptions{
		Endpoint: epGuarded,
		Guard:    allowAll,
	}, func(conn net.Conn, _ v2net.Destination) { _ = conn.Close() })
	if err != nil {
		t.Fatalf("NewStack guarded failed: %v", err)
	}
	if err := sGuarded.Start(); err != nil {
		t.Fatalf("guarded start failed: %v", err)
	}
	defer sGuarded.Close()

	injectIPv4(epGuarded, buildIPv4ICMPEcho(srcV4, dstV4, 1, 1, []byte("ping4")))
	if p, ok := readPacketTimeout(epGuarded, 150*time.Millisecond); ok {
		p.DecRef()
		t.Fatal("guarded TUN must drop ICMPv4 echo request without replying")
	}

	injectIPv6(epGuarded, buildIPv6ICMPEcho(srcV6, dstV6, 2, 2, []byte("ping6")))
	if p, ok := readPacketTimeout(epGuarded, 150*time.Millisecond); ok {
		p.DecRef()
		t.Fatal("guarded TUN must drop ICMPv6 echo request without replying (including ipv6/icmp.go bypass)")
	}

	// ICMPv6 EchoRequest behind Hop-by-Hop extension header must also be dropped.
	extEcho := buildIPv6ICMPEcho(srcV6, dstV6, 3, 3, []byte("ping6ext"))
	extPkt := make([]byte, len(extEcho)+8)
	copy(extPkt[:header.IPv6MinimumSize], extEcho[:header.IPv6MinimumSize])
	extPkt[6] = 0 // NextHeader = Hop-by-Hop (0)
	binary.BigEndian.PutUint16(extPkt[4:6], binary.BigEndian.Uint16(extEcho[4:6])+8)
	extPkt[header.IPv6MinimumSize] = uint8(header.ICMPv6ProtocolNumber) // NextHeader = 58
	extPkt[header.IPv6MinimumSize+1] = 0                                // HdrExtLen = 0 -> 8 bytes
	copy(extPkt[header.IPv6MinimumSize+8:], extEcho[header.IPv6MinimumSize:])
	injectIPv6(epGuarded, extPkt)
	if p, ok := readPacketTimeout(epGuarded, 150*time.Millisecond); ok {
		p.DecRef()
		t.Fatal("guarded TUN must drop ICMPv6 echo request behind IPv6 extension header")
	}

	// Non-echo ICMP (ICMPv4 Destination Unreachable / FragNeeded) must NOT be dropped by filter.
	fragNeeded := make([]byte, header.IPv4MinimumSize+header.ICMPv4MinimumSize)
	ipFn := header.IPv4(fragNeeded[:header.IPv4MinimumSize])
	ipFn.Encode(&header.IPv4Fields{
		TotalLength: uint16(len(fragNeeded)),
		TTL:         64,
		Protocol:    uint8(header.ICMPv4ProtocolNumber),
		SrcAddr:     tcpip.AddrFrom4(srcV4),
		DstAddr:     tcpip.AddrFrom4(dstV4),
	})
	icmpFn := header.ICMPv4(fragNeeded[header.IPv4MinimumSize:])
	icmpFn.SetType(header.ICMPv4DstUnreachable)
	icmpFn.SetCode(header.ICMPv4FragmentationNeeded)
	pktFn := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(fragNeeded)})
	if shouldDropICMPEcho(header.IPv4ProtocolNumber, pktFn) {
		pktFn.DecRef()
		t.Fatal("non-echo ICMPv4 FragNeeded must not be dropped")
	}
	pktFn.DecRef()

	// 2. Unguarded stack (guard == nil): local ICMPv4 and ICMPv6 echo replies are preserved.
	epOpen := channel.New(64, 1500, "")
	defer epOpen.Close()
	sOpen, err := NewStack(context.Background(), StackOptions{
		Endpoint: epOpen,
		Guard:    nil,
	}, func(conn net.Conn, _ v2net.Destination) { _ = conn.Close() })
	if err != nil {
		t.Fatalf("NewStack unguarded failed: %v", err)
	}
	if err := sOpen.Start(); err != nil {
		t.Fatalf("unguarded start failed: %v", err)
	}
	defer sOpen.Close()

	injectIPv4(epOpen, buildIPv4ICMPEcho(srcV4, dstV4, 7, 1, []byte("ping4")))
	p4, ok := readPacketTimeout(epOpen, 2*time.Second)
	if !ok {
		t.Fatal("unguarded TUN should reply to ICMPv4 echo")
	}
	b4 := packetToBytes(p4)
	p4.DecRef()
	if header.ICMPv4(b4[header.IPv4(b4).HeaderLength():]).Type() != header.ICMPv4EchoReply {
		t.Fatalf("expected ICMPv4EchoReply")
	}

	injectIPv6(epOpen, buildIPv6ICMPEcho(srcV6, dstV6, 8, 1, []byte("ping6")))
	p6, ok := readPacketTimeout(epOpen, 2*time.Second)
	if !ok {
		t.Fatal("unguarded TUN should reply to ICMPv6 echo")
	}
	b6 := packetToBytes(p6)
	p6.DecRef()
	if header.ICMPv6(b6[header.IPv6MinimumSize:]).Type() != header.ICMPv6EchoReply {
		t.Fatalf("expected ICMPv6EchoReply")
	}

	// 3. Long IPv6 extension header chain (>512 bytes, <=2048 bytes): guarded drops, unguarded replies.
	// Chain 3 x 256-byte extension headers (768 bytes total): Hop-by-Hop (0) -> Routing (43) -> DstOpts (60) -> ICMPv6 (58).
	baseEcho := buildIPv6ICMPEcho(srcV6, dstV6, 9, 1, []byte("long-ext"))
	extChainLen := 256 * 3
	longPkt := make([]byte, len(baseEcho)+extChainLen)
	copy(longPkt[:header.IPv6MinimumSize], baseEcho[:header.IPv6MinimumSize])
	longPkt[6] = 0 // Hop-by-Hop
	binary.BigEndian.PutUint16(longPkt[4:6], binary.BigEndian.Uint16(baseEcho[4:6])+uint16(extChainLen))
	off := header.IPv6MinimumSize
	// Header 1: Hop-by-Hop (next=43, HdrExtLen=31 -> (31+1)*8 = 256 bytes)
	longPkt[off] = 43
	longPkt[off+1] = 31
	off += 256
	// Header 2: Routing (next=60, HdrExtLen=31 -> 256 bytes)
	longPkt[off] = 60
	longPkt[off+1] = 31
	off += 256
	// Header 3: Destination Options (next=58 ICMPv6, HdrExtLen=31 -> 256 bytes)
	longPkt[off] = uint8(header.ICMPv6ProtocolNumber)
	longPkt[off+1] = 31
	off += 256
	copy(longPkt[off:], baseEcho[header.IPv6MinimumSize:])

	injectIPv6(epGuarded, longPkt)
	if p, ok := readPacketTimeout(epGuarded, 150*time.Millisecond); ok {
		p.DecRef()
		t.Fatal("guarded TUN must drop ICMPv6 echo behind >512B IPv6 extension-header chain")
	}

	injectIPv6(epOpen, longPkt)
	pLongOpen, ok := readPacketTimeout(epOpen, 2*time.Second)
	if !ok {
		t.Fatal("unguarded TUN should reply to valid ICMPv6 echo behind >512B IPv6 extension-header chain")
	}
	bLongOpen := packetToBytes(pLongOpen)
	pLongOpen.DecRef()
	if header.ICMPv6(bLongOpen[header.IPv6MinimumSize:]).Type() != header.ICMPv6EchoReply {
		t.Fatalf("expected ICMPv6EchoReply for long extension header packet")
	}
}

func buildIPv6WithExtChain(srcIP, dstIP [16]byte, extHeaders []struct {
	proto  uint8
	length int // multiple of 8, >= 8
}, upperProto uint8, upperPayload []byte) []byte {
	extTotal := 0
	for _, eh := range extHeaders {
		extTotal += eh.length
	}
	pkt := make([]byte, header.IPv6MinimumSize+extTotal+len(upperPayload))
	firstProto := tcpip.TransportProtocolNumber(upperProto)
	if len(extHeaders) > 0 {
		firstProto = tcpip.TransportProtocolNumber(extHeaders[0].proto)
	}
	ip := header.IPv6(pkt[:header.IPv6MinimumSize])
	ip.Encode(&header.IPv6Fields{
		PayloadLength:     uint16(extTotal + len(upperPayload)),
		TransportProtocol: firstProto,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16(srcIP),
		DstAddr:           tcpip.AddrFrom16(dstIP),
	})
	off := header.IPv6MinimumSize
	for i, eh := range extHeaders {
		next := upperProto
		if i+1 < len(extHeaders) {
			next = extHeaders[i+1].proto
		}
		pkt[off] = next
		pkt[off+1] = uint8(eh.length/8 - 1)
		off += eh.length
	}
	copy(pkt[off:], upperPayload)
	return pkt
}

func TestICMPEchoHardening_IPv6AdversarialBypassRegressions(t *testing.T) {
	srcV6 := [16]byte{0xfd, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}
	dstV6 := [16]byte{0xfd, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2}

	startStacks := func(mtu uint32, guard TrafficGuard, handler func(net.Conn, v2net.Destination)) (*channel.Endpoint, *stackGVisor) {
		ep := channel.New(64, mtu, "")
		s, err := NewStack(context.Background(), StackOptions{
			Endpoint: ep,
			Guard:    guard,
		}, handler)
		if err != nil {
			ep.Close()
			t.Fatalf("NewStack failed: %v", err)
		}
		if err := s.Start(); err != nil {
			ep.Close()
			t.Fatalf("stack.Start failed: %v", err)
		}
		return ep, s
	}

	allowAll := &mockGuard{fn: func(int, []byte, int, []byte, int) bool { return true }}
	epGuarded, sGuarded := startStacks(9000, allowAll, func(conn net.Conn, dst v2net.Destination) {
		defer conn.Close()
		if dst.Network == v2net.Network_UDP {
			buf := make([]byte, 512)
			n, err := conn.Read(buf)
			if err == nil && n > 0 {
				_, _ = conn.Write(append([]byte("pong:"), buf[:n]...))
			}
		}
	})
	defer func() {
		_ = sGuarded.Close()
		epGuarded.Close()
	}()

	epOpen, sOpen := startStacks(9000, nil, func(conn net.Conn, _ v2net.Destination) { _ = conn.Close() })
	defer func() {
		_ = sOpen.Close()
		epOpen.Close()
	}()

	baseEcho := buildIPv6ICMPEcho(srcV6, dstV6, 21, 1, []byte("bypass-check"))
	icmpPayload := baseEcho[header.IPv6MinimumSize:]

	// 1. 17 x 8B chained IPv6 extension headers (>16 loop cap) -> guarded drops, unguarded replies.
	chain17 := make([]struct {
		proto  uint8
		length int
	}, 17)
	chain17[0] = struct {
		proto  uint8
		length int
	}{proto: 0, length: 8} // Hop-by-Hop first
	for i := 1; i < 17; i++ {
		chain17[i] = struct {
			proto  uint8
			length int
		}{proto: 60, length: 8} // Destination Options
	}
	pkt17 := buildIPv6WithExtChain(srcV6, dstV6, chain17, uint8(header.ICMPv6ProtocolNumber), icmpPayload)
	injectIPv6(epGuarded, pkt17)
	if p, ok := readPacketTimeout(epGuarded, 150*time.Millisecond); ok {
		p.DecRef()
		t.Fatal("guarded TUN must drop ICMPv6 echo behind 17x8B chained extension headers")
	}
	injectIPv6(epOpen, pkt17)
	p17Open, ok := readPacketTimeout(epOpen, 2*time.Second)
	if !ok {
		t.Fatal("unguarded TUN should reply to ICMPv6 echo behind 17x8B chained extension headers")
	}
	b17Open := packetToBytes(p17Open)
	p17Open.DecRef()
	if header.ICMPv6(b17Open[header.IPv6MinimumSize:]).Type() != header.ICMPv6EchoReply {
		t.Fatalf("expected ICMPv6EchoReply for 17x8B extension-header packet")
	}

	// 2. >= 2048B extension headers on 9000-MTU endpoint (4x512B = 2048B and 2304B) -> guarded drops, unguarded replies.
	chain2048 := []struct {
		proto  uint8
		length int
	}{
		{proto: 0, length: 512},
		{proto: 43, length: 512},
		{proto: 60, length: 512},
		{proto: 60, length: 512},
	}
	pkt2048 := buildIPv6WithExtChain(srcV6, dstV6, chain2048, uint8(header.ICMPv6ProtocolNumber), icmpPayload)
	injectIPv6(epGuarded, pkt2048)
	if p, ok := readPacketTimeout(epGuarded, 150*time.Millisecond); ok {
		p.DecRef()
		t.Fatal("guarded TUN must drop ICMPv6 echo behind 2048B chained extension headers")
	}
	injectIPv6(epOpen, pkt2048)
	p2048Open, ok := readPacketTimeout(epOpen, 2*time.Second)
	if !ok {
		t.Fatal("unguarded TUN should reply to ICMPv6 echo behind 2048B chained extension headers")
	}
	b2048Open := packetToBytes(p2048Open)
	p2048Open.DecRef()
	if header.ICMPv6(b2048Open[header.IPv6MinimumSize:]).Type() != header.ICMPv6EchoReply {
		t.Fatalf("expected ICMPv6EchoReply for 2048B extension-header packet")
	}

	chain2304 := make([]struct {
		proto  uint8
		length int
	}, 9)
	chain2304[0] = struct {
		proto  uint8
		length int
	}{proto: 0, length: 256}
	for i := 1; i < 9; i++ {
		chain2304[i] = struct {
			proto  uint8
			length int
		}{proto: 60, length: 256}
	}
	pkt2304 := buildIPv6WithExtChain(srcV6, dstV6, chain2304, uint8(header.ICMPv6ProtocolNumber), icmpPayload)
	injectIPv6(epGuarded, pkt2304)
	if p, ok := readPacketTimeout(epGuarded, 150*time.Millisecond); ok {
		p.DecRef()
		t.Fatal("guarded TUN must drop ICMPv6 echo behind 2304B chained extension headers")
	}
	injectIPv6(epOpen, pkt2304)
	p2304Open, ok := readPacketTimeout(epOpen, 2*time.Second)
	if !ok {
		t.Fatal("unguarded TUN should reply to ICMPv6 echo behind 2304B chained extension headers")
	}
	b2304Open := packetToBytes(p2304Open)
	p2304Open.DecRef()
	if header.ICMPv6(b2304Open[header.IPv6MinimumSize:]).Type() != header.ICMPv6EchoReply {
		t.Fatalf("expected ICMPv6EchoReply for 2304B extension-header packet")
	}

	// 3. 2-fragment IPv6 ICMPv6EchoRequest:
	// Fragment 0: IPv6(Next=44) -> Fragment(Next=60, Offset=0, M=1) -> DstOpts(Next=58, 8B) (0 ICMPv6 bytes)
	// Fragment 1: IPv6(Next=44) -> Fragment(Next=60, Offset=8, M=0) -> ICMPv6EchoRequest
	fragEchoID := uint32(0x11223344)
	fragEcho0 := make([]byte, header.IPv6MinimumSize+8+8)
	header.IPv6(fragEcho0[:header.IPv6MinimumSize]).Encode(&header.IPv6Fields{
		PayloadLength:     16,
		TransportProtocol: 44, // Fragment header
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16(srcV6),
		DstAddr:           tcpip.AddrFrom16(dstV6),
	})
	fragEcho0[header.IPv6MinimumSize] = 60 // NextHeader = DstOpts
	binary.BigEndian.PutUint16(fragEcho0[header.IPv6MinimumSize+2:header.IPv6MinimumSize+4], 0x0001)
	binary.BigEndian.PutUint32(fragEcho0[header.IPv6MinimumSize+4:header.IPv6MinimumSize+8], fragEchoID)
	fragEcho0[header.IPv6MinimumSize+8] = uint8(header.ICMPv6ProtocolNumber) // DstOpts NextHeader = 58
	fragEcho0[header.IPv6MinimumSize+9] = 0                                  // HdrExtLen = 0 -> 8B

	fragEcho1 := make([]byte, header.IPv6MinimumSize+8+len(icmpPayload))
	header.IPv6(fragEcho1[:header.IPv6MinimumSize]).Encode(&header.IPv6Fields{
		PayloadLength:     uint16(8 + len(icmpPayload)),
		TransportProtocol: 44,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16(srcV6),
		DstAddr:           tcpip.AddrFrom16(dstV6),
	})
	fragEcho1[header.IPv6MinimumSize] = 60 // NextHeader = DstOpts (per RFC 8200)
	binary.BigEndian.PutUint16(fragEcho1[header.IPv6MinimumSize+2:header.IPv6MinimumSize+4], 0x0008)
	binary.BigEndian.PutUint32(fragEcho1[header.IPv6MinimumSize+4:header.IPv6MinimumSize+8], fragEchoID)
	copy(fragEcho1[header.IPv6MinimumSize+8:], icmpPayload)

	injectIPv6(epGuarded, fragEcho0)
	injectIPv6(epGuarded, fragEcho1)
	if p, ok := readPacketTimeout(epGuarded, 150*time.Millisecond); ok {
		p.DecRef()
		t.Fatal("guarded TUN must drop fragmented IPv6 EchoRequest before gVisor reassembly")
	}

	injectIPv6(epOpen, fragEcho0)
	injectIPv6(epOpen, fragEcho1)
	pFragOpen, ok := readPacketTimeout(epOpen, 2*time.Second)
	if !ok {
		t.Fatal("unguarded TUN should reassemble and reply to 2-fragment IPv6 EchoRequest")
	}
	bFragOpen := packetToBytes(pFragOpen)
	pFragOpen.DecRef()
	if header.ICMPv6(bFragOpen[header.IPv6MinimumSize:]).Type() != header.ICMPv6EchoReply {
		t.Fatalf("expected ICMPv6EchoReply for fragmented EchoRequest on unguarded stack")
	}

	// 4. 2-fragment IPv6 UDP packet MUST NOT be dropped by Fragment 0 ICMP hardening and must reach guard/handler.
	udpPayload := []byte("frag-udp")
	fullUDPV6 := buildIPv6UDP(srcV6, dstV6, 54321, 53, udpPayload)
	udpDatagram := fullUDPV6[header.IPv6MinimumSize:] // 8B UDP header + 8B payload = 16B
	fragUDPId := uint32(0x55667788)

	fragUDP0 := make([]byte, header.IPv6MinimumSize+8+8)
	header.IPv6(fragUDP0[:header.IPv6MinimumSize]).Encode(&header.IPv6Fields{
		PayloadLength:     16,
		TransportProtocol: 44,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16(srcV6),
		DstAddr:           tcpip.AddrFrom16(dstV6),
	})
	fragUDP0[header.IPv6MinimumSize] = uint8(header.UDPProtocolNumber)
	binary.BigEndian.PutUint16(fragUDP0[header.IPv6MinimumSize+2:header.IPv6MinimumSize+4], 0x0001)
	binary.BigEndian.PutUint32(fragUDP0[header.IPv6MinimumSize+4:header.IPv6MinimumSize+8], fragUDPId)
	copy(fragUDP0[header.IPv6MinimumSize+8:], udpDatagram[:8])

	fragUDP1 := make([]byte, header.IPv6MinimumSize+8+8)
	header.IPv6(fragUDP1[:header.IPv6MinimumSize]).Encode(&header.IPv6Fields{
		PayloadLength:     16,
		TransportProtocol: 44,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16(srcV6),
		DstAddr:           tcpip.AddrFrom16(dstV6),
	})
	fragUDP1[header.IPv6MinimumSize] = uint8(header.UDPProtocolNumber)
	binary.BigEndian.PutUint16(fragUDP1[header.IPv6MinimumSize+2:header.IPv6MinimumSize+4], 0x0008)
	binary.BigEndian.PutUint32(fragUDP1[header.IPv6MinimumSize+4:header.IPv6MinimumSize+8], fragUDPId)
	copy(fragUDP1[header.IPv6MinimumSize+8:], udpDatagram[8:])

	callsBefore := allowAll.callCount()
	injectIPv6(epGuarded, fragUDP0)
	injectIPv6(epGuarded, fragUDP1)
	pUDPReply, ok := readPacketTimeout(epGuarded, 2*time.Second)
	if !ok {
		t.Fatal("expected 2-fragment IPv6 UDP packet to reassemble and receive reply when guard allows")
	}
	bUDPReply := packetToBytes(pUDPReply)
	pUDPReply.DecRef()
	if string(bUDPReply[header.IPv6MinimumSize+header.UDPMinimumSize:]) != "pong:frag-udp" {
		t.Fatalf("unexpected fragmented IPv6 UDP reply payload: %q", string(bUDPReply[header.IPv6MinimumSize+header.UDPMinimumSize:]))
	}
	if allowAll.callCount() != callsBefore+1 {
		t.Fatalf("expected 1 guard call for fragmented IPv6 UDP, got %d", allowAll.callCount()-callsBefore)
	}

	// 5. Non-echo ICMPv6 PacketTooBig (whole and split Fragment 0 with Type byte present) MUST NOT be dropped.
	ptbPkt := make([]byte, header.IPv6MinimumSize+header.ICMPv6MinimumSize)
	header.IPv6(ptbPkt[:header.IPv6MinimumSize]).Encode(&header.IPv6Fields{
		PayloadLength:     header.ICMPv6MinimumSize,
		TransportProtocol: header.ICMPv6ProtocolNumber,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16(srcV6),
		DstAddr:           tcpip.AddrFrom16(dstV6),
	})
	header.ICMPv6(ptbPkt[header.IPv6MinimumSize:]).SetType(header.ICMPv6PacketTooBig)
	pbPTB := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(ptbPkt)})
	if shouldDropICMPEcho(header.IPv6ProtocolNumber, pbPTB) {
		pbPTB.DecRef()
		t.Fatal("non-echo ICMPv6 PacketTooBig must not be dropped")
	}
	pbPTB.DecRef()

	ptbFrag0 := make([]byte, header.IPv6MinimumSize+8+2)
	header.IPv6(ptbFrag0[:header.IPv6MinimumSize]).Encode(&header.IPv6Fields{
		PayloadLength:     10,
		TransportProtocol: 44,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16(srcV6),
		DstAddr:           tcpip.AddrFrom16(dstV6),
	})
	ptbFrag0[header.IPv6MinimumSize] = uint8(header.ICMPv6ProtocolNumber)
	binary.BigEndian.PutUint16(ptbFrag0[header.IPv6MinimumSize+2:header.IPv6MinimumSize+4], 0x0001)
	ptbFrag0[header.IPv6MinimumSize+8] = uint8(header.ICMPv6PacketTooBig)
	pbPTBFrag := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(ptbFrag0)})
	if shouldDropICMPEcho(header.IPv6ProtocolNumber, pbPTBFrag) {
		pbPTBFrag.DecRef()
		t.Fatal("split Fragment 0 of non-echo ICMPv6 PacketTooBig must not be dropped")
	}
	pbPTBFrag.DecRef()
}

func TestNilGuardAllowsTCPAndUDP(t *testing.T) {
	ep := channel.New(64, 1500, "")
	defer ep.Close()

	var accepted atomic.Int32
	s, err := NewStack(context.Background(), StackOptions{
		Endpoint: ep,
		Guard:    nil,
	}, func(conn net.Conn, _ v2net.Destination) {
		accepted.Add(1)
		_ = conn.Close()
	})
	if err != nil {
		t.Fatalf("NewStack failed: %v", err)
	}
	if err := s.Start(); err != nil {
		t.Fatalf("stack.Start failed: %v", err)
	}
	defer s.Close()

	srcV4 := [4]byte{172, 19, 0, 1}
	dstV4 := [4]byte{1, 1, 1, 1}
	injectIPv4(ep, buildIPv4TCPSYN(srcV4, dstV4, 50001, 443, 100))
	p, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected SYN-ACK when guard is nil")
	}
	b := packetToBytes(p)
	p.DecRef()
	if !header.TCP(b[header.IPv4(b).HeaderLength():]).Flags().Contains(header.TCPFlagSyn | header.TCPFlagAck) {
		t.Fatal("expected SYN-ACK flags when guard is nil")
	}

	injectIPv4(ep, buildIPv4UDP(srcV4, dstV4, 50002, 53, []byte("hi")))
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && accepted.Load() < 1 {
		time.Sleep(10 * time.Millisecond)
	}
	if accepted.Load() < 1 {
		t.Fatal("expected UDP connection to be accepted when guard is nil")
	}
}

func TestNormalizeTrafficGuard_AndTypedNilGuardDoesNotPanic(t *testing.T) {
	if got := NormalizeTrafficGuard(nil); got != nil {
		t.Fatalf("expected untyped nil to remain nil, got %v", got)
	}
	var typedNil *panickingNilGuard
	if got := NormalizeTrafficGuard(typedNil); got != nil {
		t.Fatalf("expected typed-nil pointer to normalize to nil, got %#v", got)
	}
	nonNil := &panickingNilGuard{allow: true}
	if got := NormalizeTrafficGuard(nonNil); got == nil {
		t.Fatal("expected non-nil guard to remain non-nil")
	}

	// Direct newUDPConnectionHandler defense-in-depth check.
	var udpAccepted atomic.Int32
	uh := newUDPConnectionHandler(typedNil, func(conn net.Conn, _ v2net.Destination) {
		udpAccepted.Add(1)
		_ = conn.Close()
	}, func([]byte, v2net.Destination, v2net.Destination) error { return nil })
	srcAddr := netip.MustParseAddr("172.19.0.1")
	dstAddr := netip.MustParseAddr("172.19.0.2")
	uh.HandlePacket(
		v2net.UDPDestination(v2net.IPAddress(srcAddr.AsSlice()), 50100),
		v2net.UDPDestination(v2net.IPAddress(dstAddr.AsSlice()), 53),
		makeUDPEndpointKey(srcAddr, 50100),
		makeUDPEndpointKey(dstAddr, 53),
		srcAddr.AsSlice(), 50100,
		dstAddr.AsSlice(), 53,
		[]byte("typed-nil-direct"),
	)
	uh.CloseAll()
	if uh.guard != nil {
		t.Fatal("expected udpConnectionHandler.guard to normalize typed-nil to nil")
	}

	// Full NewStack test with (*panickingNilGuard)(nil): TCP SYN, UDP, and ICMP echo must not panic and must allow through.
	ep := channel.New(64, 1500, "")
	defer ep.Close()

	var acceptedUDP atomic.Int32
	s, err := NewStack(context.Background(), StackOptions{
		Endpoint: ep,
		Guard:    typedNil,
	}, func(conn net.Conn, dst v2net.Destination) {
		if dst.Network == v2net.Network_UDP {
			acceptedUDP.Add(1)
		}
		_ = conn.Close()
	})
	if err != nil {
		t.Fatalf("NewStack failed: %v", err)
	}
	if err := s.Start(); err != nil {
		t.Fatalf("stack.Start failed: %v", err)
	}
	defer s.Close()

	srcV4 := [4]byte{172, 19, 0, 1}
	dstV4 := [4]byte{172, 19, 0, 2}
	srcV6 := [16]byte{0xfd, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}
	dstV6 := [16]byte{0xfd, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2}

	// TCP IPv4 SYN -> SYN-ACK
	injectIPv4(ep, buildIPv4TCPSYN(srcV4, dstV4, 51001, 443, 100))
	pTCP, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected TCP SYN-ACK with typed-nil guard")
	}
	bTCP := packetToBytes(pTCP)
	pTCP.DecRef()
	if !header.TCP(bTCP[header.IPv4(bTCP).HeaderLength():]).Flags().Contains(header.TCPFlagSyn | header.TCPFlagAck) {
		t.Fatalf("expected SYN-ACK flags with typed-nil guard")
	}

	// UDP IPv4 -> accepted
	injectIPv4(ep, buildIPv4UDP(srcV4, dstV4, 51002, 53, []byte("hello-typed-nil")))
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && acceptedUDP.Load() < 1 {
		time.Sleep(10 * time.Millisecond)
	}
	if acceptedUDP.Load() < 1 {
		t.Fatal("expected UDP packet to be accepted with typed-nil guard")
	}

	// ICMPv4 Echo -> local echo reply (since typed-nil guard normalized to nil)
	injectIPv4(ep, buildIPv4ICMPEcho(srcV4, dstV4, 11, 1, []byte("icmp4")))
	pICMP4, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected ICMPv4 echo reply with typed-nil guard")
	}
	bICMP4 := packetToBytes(pICMP4)
	pICMP4.DecRef()
	if header.ICMPv4(bICMP4[header.IPv4(bICMP4).HeaderLength():]).Type() != header.ICMPv4EchoReply {
		t.Fatalf("expected ICMPv4EchoReply with typed-nil guard")
	}

	// ICMPv6 Echo -> local echo reply
	injectIPv6(ep, buildIPv6ICMPEcho(srcV6, dstV6, 12, 1, []byte("icmp6")))
	pICMP6, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected ICMPv6 echo reply with typed-nil guard")
	}
	bICMP6 := packetToBytes(pICMP6)
	pICMP6.DecRef()
	if header.ICMPv6(bICMP6[header.IPv6MinimumSize:]).Type() != header.ICMPv6EchoReply {
		t.Fatalf("expected ICMPv6EchoReply with typed-nil guard")
	}
}

func TestUDPGuard_ActiveFlowDNS53RevalidatesAndEvictsEmptyConn(t *testing.T) {
	ep := channel.New(64, 1500, "")
	defer ep.Close()

	var allow atomic.Bool
	allow.Store(true)
	guard := &mockGuard{
		fn: func(int, []byte, int, []byte, int) bool {
			return allow.Load()
		},
	}

	var connClosed atomic.Bool
	s, err := NewStack(context.Background(), StackOptions{
		Endpoint: ep,
		Guard:    guard,
	}, func(conn net.Conn, _ v2net.Destination) {
		defer func() {
			_ = conn.Close()
			connClosed.Store(true)
		}()
		buf := make([]byte, 256)
		for {
			n, err := conn.Read(buf)
			if err != nil {
				return
			}
			_, _ = conn.Write([]byte("ok:" + string(buf[:n])))
		}
	})
	if err != nil {
		t.Fatalf("NewStack failed: %v", err)
	}
	if err := s.Start(); err != nil {
		t.Fatalf("stack.Start failed: %v", err)
	}
	defer s.Close()

	srcV4 := [4]byte{172, 19, 0, 1}
	dnsV4 := [4]byte{172, 19, 0, 2}

	injectIPv4(ep, buildIPv4UDP(srcV4, dnsV4, 54001, 53, []byte("q1")))
	p, ok := readPacketTimeout(ep, 2*time.Second)
	if !ok {
		t.Fatal("expected DNS reply for allowed flow")
	}
	p.DecRef()

	// Flip guard to false WHILE udpConn is still active (never closed by dispatcher).
	allow.Store(false)
	injectIPv4(ep, buildIPv4UDP(srcV4, dnsV4, 54001, 53, []byte("q2-excluded-reuse")))
	if resp, ok := readPacketTimeout(ep, 150*time.Millisecond); ok {
		resp.DecRef()
		t.Fatal("excluded port reuse on active DNS 53 flow must be dropped immediately")
	}
	if guard.callCount() != 2 {
		t.Fatalf("expected 2 guard calls, got %d", guard.callCount())
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && !connClosed.Load() {
		time.Sleep(10 * time.Millisecond)
	}
	if !connClosed.Load() {
		t.Fatal("expected empty udpConn to be closed & evicted on DNS re-check denial")
	}
	s.udpForwarder.RLock()
	remaining := len(s.udpForwarder.udpConns)
	s.udpForwarder.RUnlock()
	if remaining != 0 {
		t.Fatalf("expected 0 remaining udpConns after eviction, got %d", remaining)
	}
}

func TestUDPGuard_ActiveFlowNonDNSTTLRevalidatesPartialAndFullEviction(t *testing.T) {
	var curTime atomic.Int64
	curTime.Store(time.Unix(1_700_000_000, 0).UnixNano())

	var allowA atomic.Bool
	var allowB atomic.Bool
	allowA.Store(true)
	allowB.Store(true)

	dstAAddr := netip.MustParseAddr("203.0.113.10")
	dstBAddr := netip.MustParseAddr("203.0.113.20")
	srcAddr := netip.MustParseAddr("172.19.0.1")

	guard := &mockGuard{
		fn: func(_ int, _ []byte, _ int, dstIP []byte, _ int) bool {
			if bytes.Equal(dstIP, dstAAddr.AsSlice()) {
				return allowA.Load()
			}
			return allowB.Load()
		},
	}

	var dispatched atomic.Int32
	var forwarded atomic.Int32
	var connClosed atomic.Bool
	u := newUDPConnectionHandler(
		guard,
		func(conn net.Conn, _ v2net.Destination) {
			dispatched.Add(1)
			defer func() {
				_ = conn.Close()
				connClosed.Store(true)
			}()
			buf := make([]byte, 256)
			for {
				if _, err := conn.Read(buf); err != nil {
					return
				}
				forwarded.Add(1)
			}
		},
		func([]byte, v2net.Destination, v2net.Destination) error { return nil },
	)
	u.now = func() time.Time { return time.Unix(0, curTime.Load()) }
	defer u.CloseAll()

	srcKey := makeUDPEndpointKey(srcAddr, 55000)
	dstAKey := makeUDPEndpointKey(dstAAddr, 443)
	dstBKey := makeUDPEndpointKey(dstBAddr, 8443)
	srcDest := v2net.UDPDestination(v2net.IPAddress(srcAddr.AsSlice()), 55000)
	dstADest := v2net.UDPDestination(v2net.IPAddress(dstAAddr.AsSlice()), 443)
	dstBDest := v2net.UDPDestination(v2net.IPAddress(dstBAddr.AsSlice()), 8443)

	// Allow both dstA and dstB on the same active udpConn.
	u.HandlePacket(srcDest, dstADest, srcKey, dstAKey, srcAddr.AsSlice(), 55000, dstAAddr.AsSlice(), 443, []byte("a1"))
	u.HandlePacket(srcDest, dstBDest, srcKey, dstBKey, srcAddr.AsSlice(), 55000, dstBAddr.AsSlice(), 8443, []byte("b1"))
	if guard.callCount() != 2 {
		t.Fatalf("expected 2 initial guard calls, got %d", guard.callCount())
	}

	// Within 2s TTL (+1s): cached fast-path does NOT call guard.
	curTime.Add(int64(1 * time.Second))
	u.HandlePacket(srcDest, dstADest, srcKey, dstAKey, srcAddr.AsSlice(), 55000, dstAAddr.AsSlice(), 443, []byte("a2-cached"))
	if guard.callCount() != 2 {
		t.Fatalf("expected cached hit within TTL, got %d guard calls", guard.callCount())
	}

	// Past 2s TTL (+1.5s = 2.5s total): deny dstB only -> partial eviction keeps dstA & conn alive!
	allowB.Store(false)
	curTime.Add(int64(1500 * time.Millisecond))
	u.HandlePacket(srcDest, dstBDest, srcKey, dstBKey, srcAddr.AsSlice(), 55000, dstBAddr.AsSlice(), 8443, []byte("b2-denied"))
	if guard.callCount() != 3 {
		t.Fatalf("expected TTL expiry to re-query guard for dstB, got %d", guard.callCount())
	}
	u.RLock()
	curConn := u.udpConns[srcKey]
	_, hasA := curConn.allowedDests[dstAKey]
	_, hasB := curConn.allowedDests[dstBKey]
	u.RUnlock()
	if curConn == nil || !hasA || hasB {
		t.Fatalf("expected partial eviction of dstB keeping dstA alive: conn=%v hasA=%v hasB=%v", curConn != nil, hasA, hasB)
	}
	if connClosed.Load() {
		t.Fatal("conn must remain open while dstA is still allowed")
	}

	// Deny dstA as well -> full eviction closes udpConn!
	allowA.Store(false)
	u.HandlePacket(srcDest, dstADest, srcKey, dstAKey, srcAddr.AsSlice(), 55000, dstAAddr.AsSlice(), 443, []byte("a3-denied"))
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && !connClosed.Load() {
		time.Sleep(10 * time.Millisecond)
	}
	if !connClosed.Load() {
		t.Fatal("expected udpConn to close once last allowedDests entry is evicted")
	}
	u.RLock()
	_, stillPresent := u.udpConns[srcKey]
	u.RUnlock()
	if stillPresent {
		t.Fatal("expected empty udpConn to be removed from udpConns")
	}
	if dispatched.Load() != 1 {
		t.Fatalf("expected 1 dispatched udpConn, got %d", dispatched.Load())
	}

	// Nil guard preserves caching across arbitrary time jumps without panicking.
	uNil := newUDPConnectionHandler(
		nil,
		func(conn net.Conn, _ v2net.Destination) { _ = conn.Close() },
		func([]byte, v2net.Destination, v2net.Destination) error { return nil },
	)
	uNil.now = func() time.Time { return time.Unix(0, curTime.Load()) }
	defer uNil.CloseAll()
	uNil.HandlePacket(srcDest, dstADest, srcKey, dstAKey, srcAddr.AsSlice(), 55000, dstAAddr.AsSlice(), 443, []byte("nil1"))
	curTime.Add(int64(10 * time.Second))
	uNil.HandlePacket(srcDest, dstADest, srcKey, dstAKey, srcAddr.AsSlice(), 55000, dstAAddr.AsSlice(), 443, []byte("nil2"))
}

func TestStackGVisor_ConcurrentWriteDuringAndAfterCloseDoesNotPanic(t *testing.T) {
	ep := channel.New(256, 1500, "")
	defer ep.Close()

	guard := &mockGuard{
		fn: func(int, []byte, int, []byte, int) bool { return true },
	}

	connCh := make(chan net.Conn, 1)
	s, err := NewStack(context.Background(), StackOptions{
		Endpoint: ep,
		Guard:    guard,
	}, func(conn net.Conn, _ v2net.Destination) {
		select {
		case connCh <- conn:
		default:
		}
		buf := make([]byte, 256)
		for {
			if _, err := conn.Read(buf); err != nil {
				_ = conn.Close()
				return
			}
		}
	})
	if err != nil {
		t.Fatalf("NewStack failed: %v", err)
	}
	if err := s.Start(); err != nil {
		t.Fatalf("stack.Start failed: %v", err)
	}

	srcV4 := [4]byte{172, 19, 0, 1}
	dnsV4 := [4]byte{172, 19, 0, 2}
	injectIPv4(ep, buildIPv4UDP(srcV4, dnsV4, 54321, 53, []byte("dns-query")))

	var activeConn net.Conn
	select {
	case activeConn = <-connCh:
	case <-time.After(2 * time.Second):
		_ = s.Close()
		t.Fatal("timed out waiting for UDP conn handler")
	}

	srcDest := v2net.UDPDestination(v2net.IPAddress(srcV4[:]), 54321)
	dnsDest := v2net.UDPDestination(v2net.IPAddress(dnsV4[:]), 53)
	srcAddr := tcpip.AddrFrom4(srcV4)
	dnsAddr := tcpip.AddrFrom4(dnsV4)
	icmpReply := make([]byte, header.ICMPv4MinimumSize)
	icmpHdr := header.ICMPv4(icmpReply)
	icmpHdr.SetType(header.ICMPv4EchoReply)
	icmpHdr.SetChecksum(^checksum.Checksum(icmpReply, 0))

	// Drain outbound packets from channel.Endpoint so the ring never blocks writers.
	stopDrain := make(chan struct{})
	var drainWg sync.WaitGroup
	drainWg.Add(1)
	go func() {
		defer drainWg.Done()
		for {
			select {
			case <-stopDrain:
				return
			default:
				if pkt, ok := readPacketTimeout(ep, 20*time.Millisecond); ok {
					pkt.DecRef()
				}
			}
		}
	}()

	var wg sync.WaitGroup
	for g := 0; g < 6; g++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for i := 0; i < 200; i++ {
				switch (id + i) % 3 {
				case 0:
					_, _ = activeConn.Write([]byte("dns-reply"))
				case 1:
					_ = s.writeRawUDPPacket([]byte("dns-raw"), dnsDest, srcDest)
				default:
					_ = s.writeRawICMPPacket(header.IPv4ProtocolNumber, icmpReply, dnsAddr, srcAddr)
				}
				if i == 15 {
					time.Sleep(time.Millisecond)
				}
			}
		}(g)
	}

	time.Sleep(2 * time.Millisecond)
	if err := s.Close(); err != nil {
		t.Fatalf("stack.Close failed: %v", err)
	}
	wg.Wait()
	close(stopDrain)
	drainWg.Wait()

	if _, err := activeConn.Write([]byte("after-close")); err != io.ErrClosedPipe {
		t.Fatalf("expected io.ErrClosedPipe from activeConn.Write after Close, got %v", err)
	}
	if err := s.writeRawUDPPacket([]byte("after-close"), dnsDest, srcDest); err != io.ErrClosedPipe {
		t.Fatalf("expected io.ErrClosedPipe from writeRawUDPPacket after Close, got %v", err)
	}
	if err := s.writeRawICMPPacket(header.IPv4ProtocolNumber, icmpReply, dnsAddr, srcAddr); err != io.ErrClosedPipe {
		t.Fatalf("expected io.ErrClosedPipe from writeRawICMPPacket after Close, got %v", err)
	}
	// Repeat Close must remain idempotent.
	if err := s.Close(); err != nil {
		t.Fatalf("second stack.Close failed: %v", err)
	}
}

func TestStackCloseDoesNotLeakQDiscGoroutines(t *testing.T) {
	runCycle := func() {
		ep := channel.New(64, 1500, "")
		s, err := NewStack(context.Background(), StackOptions{
			Endpoint:    ep,
			IdleTimeout: 5 * time.Second,
			Guard:       &mockGuard{fn: func(int, []byte, int, []byte, int) bool { return false }},
		}, func(conn net.Conn, _ v2net.Destination) {
			_ = conn.Close()
		})
		if err != nil {
			t.Fatalf("NewStack failed: %v", err)
		}
		if err := s.Start(); err != nil {
			t.Fatalf("stack.Start failed: %v", err)
		}

		// Trigger TCP RST through gVisor's NIC qDisc before closing.
		injectIPv4(ep, buildIPv4TCPSYN(
			[4]byte{172, 19, 0, 1},
			[4]byte{1, 1, 1, 1},
			45000,
			443,
			1,
		))

		if err := s.Close(); err != nil {
			t.Fatalf("stack.Close failed: %v", err)
		}
		ep.Close()
	}

	// Warm up one-time runtime/gVisor goroutines before snapshotting baseline.
	runCycle()
	runtime.GC()
	time.Sleep(50 * time.Millisecond)
	before := runtime.NumGoroutine()

	const cycles = 20
	for i := 0; i < cycles; i++ {
		runCycle()
	}

	deadline := time.Now().Add(2 * time.Second)
	for {
		runtime.GC()
		runtime.Gosched()
		after := runtime.NumGoroutine()
		if after <= before+4 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("goroutine count grew across %d Start/Close cycles: before=%d after=%d", cycles, before, after)
		}
		time.Sleep(25 * time.Millisecond)
	}
}
