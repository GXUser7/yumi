package tun

import (
	"context"
	"io"
	"net"
	"sync"
	"time"

	"github.com/xtls/xray-core/common/errors"
	v2net "github.com/xtls/xray-core/common/net"
	tunicmp "github.com/xtls/xray-core/proxy/tun/icmp"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/checksum"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	defaultNIC tcpip.NICID = 1

	tcpRXBufMinSize = tcp.MinBufferSize
	tcpRXBufDefSize = tcp.DefaultSendBufferSize
	tcpRXBufMaxSize = 8 << 20 // 8 MiB

	tcpTXBufMinSize = tcp.MinBufferSize
	tcpTXBufDefSize = tcp.DefaultReceiveBufferSize
	tcpTXBufMaxSize = 6 << 20 // 6 MiB
)

// StackOptions configures the gVisor netstack instance.
type StackOptions struct {
	Endpoint    stack.LinkEndpoint
	IdleTimeout time.Duration
	Guard       TrafficGuard
}

type stackGVisor struct {
	ctx              context.Context
	idleTimeout      time.Duration
	guard            TrafficGuard
	handleConnection func(conn net.Conn, destination v2net.Destination)

	mu           sync.RWMutex
	closed       bool
	stack        *stack.Stack
	endpoint     stack.LinkEndpoint
	udpForwarder *udpConnectionHandler
}

// NewStack builds a gVisor netstack around the provided LinkEndpoint and TrafficGuard.
func NewStack(
	ctx context.Context,
	options StackOptions,
	handleConnection func(conn net.Conn, destination v2net.Destination),
) (*stackGVisor, error) {
	if options.Endpoint == nil {
		return nil, errors.New("tun stack requires a non-nil LinkEndpoint")
	}
	if handleConnection == nil {
		return nil, errors.New("tun stack requires a non-nil connection handler")
	}
	if ctx == nil {
		ctx = context.Background()
	}
	return &stackGVisor{
		ctx:              ctx,
		idleTimeout:      options.IdleTimeout,
		guard:            NormalizeTrafficGuard(options.Guard),
		handleConnection: handleConnection,
		endpoint:         options.Endpoint,
	}, nil
}

// Start initializes gVisor TCP, UDP, and ICMP handlers and attaches the endpoint.
func (t *stackGVisor) Start() error {
	ep := t.endpoint
	if t.guard != nil {
		// Wrap at the NetworkDispatcher boundary so ICMPv6EchoRequest is dropped before
		// gVisor's ipv6/icmp.go auto-replies without calling DeliverTransportPacket.
		ep = &icmpEchoDropEndpoint{LinkEndpoint: ep}
	}

	ipStack, err := createStack(ep)
	if err != nil {
		return err
	}

	tcpForwarder := tcp.NewForwarder(ipStack, 0, 65535, func(r *tcp.ForwarderRequest) {
		go func(r *tcp.ForwarderRequest) {
			id := r.ID()
			srcIP, _, okSrc := normalizeAddr(id.RemoteAddress)
			dstIP, dstAddr, okDst := normalizeAddr(id.LocalAddress)
			if !okSrc || !okDst || id.RemotePort == 0 || id.LocalPort == 0 {
				r.Complete(true)
				return
			}
			if t.guard != nil && !t.guard.AllowConnection(6, srcIP, int(id.RemotePort), dstIP, int(id.LocalPort)) {
				// Immediate RST pre-SYN-ACK; never calls CreateEndpoint or dispatches.
				r.Complete(true)
				return
			}

			var wq waiter.Queue
			ep, err := r.CreateEndpoint(&wq)
			if err != nil {
				errors.LogError(t.ctx, err.String())
				r.Complete(true)
				return
			}

			options := ep.SocketOptions()
			options.SetKeepAlive(false)
			options.SetReuseAddress(true)
			options.SetReusePort(true)

			t.handleConnection(
				gonet.NewTCPConn(&wq, ep),
				v2net.TCPDestination(v2net.IPAddress(dstAddr.AsSlice()), v2net.Port(id.LocalPort)),
			)

			ep.Close()
			r.Complete(false)
		}(r)
	})
	ipStack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	udpForwarder := newUDPConnectionHandler(t.guard, t.handleConnection, t.writeRawUDPPacket)

	t.mu.Lock()
	t.stack = ipStack
	t.endpoint = ep
	t.udpForwarder = udpForwarder
	t.closed = false
	t.mu.Unlock()

	ipStack.SetTransportProtocolHandler(udp.ProtocolNumber, func(id stack.TransportEndpointID, pkt *stack.PacketBuffer) bool {
		srcIP, srcAddr, okSrc := normalizeAddr(id.RemoteAddress)
		dstIP, dstAddr, okDst := normalizeAddr(id.LocalAddress)
		if !okSrc || !okDst || id.RemotePort == 0 || id.LocalPort == 0 {
			return true
		}
		data := pkt.Data().AsRange().ToSlice()
		src := v2net.UDPDestination(v2net.IPAddress(srcAddr.AsSlice()), v2net.Port(id.RemotePort))
		dst := v2net.UDPDestination(v2net.IPAddress(dstAddr.AsSlice()), v2net.Port(id.LocalPort))
		srcKey := makeUDPEndpointKey(srcAddr, id.RemotePort)
		dstKey := makeUDPEndpointKey(dstAddr, id.LocalPort)
		udpForwarder.HandlePacket(src, dst, srcKey, dstKey, srcIP, int(id.RemotePort), dstIP, int(id.LocalPort), data)
		return true
	})

	ipStack.SetTransportProtocolHandler(icmp.ProtocolNumber4, t.handleICMPv4Packet)
	ipStack.SetTransportProtocolHandler(icmp.ProtocolNumber6, t.handleICMPv6Packet)

	return nil
}

// Close tears down the gVisor stack and all active UDP flows cleanly.
func (t *stackGVisor) Close() error {
	t.mu.Lock()
	udpForwarder := t.udpForwarder
	if t.closed || t.stack == nil {
		t.mu.Unlock()
		if udpForwarder != nil {
			udpForwarder.CloseAll()
		}
		return nil
	}
	t.closed = true
	ep := t.endpoint
	s := t.stack
	t.mu.Unlock()

	if udpForwarder != nil {
		udpForwarder.CloseAll()
	}
	if ep != nil {
		ep.Attach(nil)
	}
	s.Close()
	for _, endpoint := range s.CleanupEndpoints() {
		endpoint.Abort()
	}
	return nil
}

func (t *stackGVisor) isClosed() bool {
	t.mu.RLock()
	closed := t.closed || t.stack == nil
	t.mu.RUnlock()
	return closed
}

func (t *stackGVisor) writePacketToStack(ipProtocol tcpip.NetworkProtocolNumber, pkt *stack.PacketBuffer, label string) error {
	t.mu.RLock()
	if t.closed || t.stack == nil {
		t.mu.RUnlock()
		return io.ErrClosedPipe
	}
	err := t.stack.WriteRawPacket(defaultNIC, ipProtocol, buffer.MakeWithView(pkt.ToView()))
	t.mu.RUnlock()
	if err != nil {
		return errors.New("failed to write raw "+label+" packet back to stack", err)
	}
	return nil
}

func (t *stackGVisor) writeRawUDPPacket(payload []byte, src v2net.Destination, dst v2net.Destination) error {
	if t.isClosed() {
		return io.ErrClosedPipe
	}
	if src.Address.Family() != dst.Address.Family() {
		return errors.New("mismatched IP address family for UDP reply")
	}
	udpLen := header.UDPMinimumSize + len(payload)
	srcIP := tcpip.AddrFromSlice(src.Address.IP())
	dstIP := tcpip.AddrFromSlice(dst.Address.IP())

	isIPv4 := dst.Address.Family().IsIPv4()
	ipHdrSize := header.IPv6MinimumSize
	ipProtocol := header.IPv6ProtocolNumber
	if isIPv4 {
		ipHdrSize = header.IPv4MinimumSize
		ipProtocol = header.IPv4ProtocolNumber
	}

	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
		ReserveHeaderBytes: ipHdrSize + header.UDPMinimumSize,
		Payload:            buffer.MakeWithData(payload),
	})
	defer pkt.DecRef()

	udpHdr := header.UDP(pkt.TransportHeader().Push(header.UDPMinimumSize))
	udpHdr.Encode(&header.UDPFields{
		SrcPort: uint16(src.Port),
		DstPort: uint16(dst.Port),
		Length:  uint16(udpLen),
	})

	xsum := header.PseudoHeaderChecksum(header.UDPProtocolNumber, srcIP, dstIP, uint16(udpLen))
	udpHdr.SetChecksum(^udpHdr.CalculateChecksum(checksum.Checksum(payload, xsum)))

	if isIPv4 {
		ipHdr := header.IPv4(pkt.NetworkHeader().Push(header.IPv4MinimumSize))
		ipHdr.Encode(&header.IPv4Fields{
			TotalLength: uint16(header.IPv4MinimumSize + udpLen),
			TTL:         64,
			Protocol:    uint8(header.UDPProtocolNumber),
			SrcAddr:     srcIP,
			DstAddr:     dstIP,
		})
		ipHdr.SetChecksum(^ipHdr.CalculateChecksum())
	} else {
		ipHdr := header.IPv6(pkt.NetworkHeader().Push(header.IPv6MinimumSize))
		ipHdr.Encode(&header.IPv6Fields{
			PayloadLength:     uint16(udpLen),
			TransportProtocol: header.UDPProtocolNumber,
			HopLimit:          64,
			SrcAddr:           srcIP,
			DstAddr:           dstIP,
		})
	}

	return t.writePacketToStack(ipProtocol, pkt, "udp")
}

func (t *stackGVisor) handleICMPv4Packet(id stack.TransportEndpointID, pkt *stack.PacketBuffer) bool {
	if t.guard != nil {
		return true
	}
	return t.handleICMPEchoPacket(header.IPv4ProtocolNumber, id, pkt)
}

func (t *stackGVisor) handleICMPv6Packet(id stack.TransportEndpointID, pkt *stack.PacketBuffer) bool {
	if t.guard != nil {
		return true
	}
	return t.handleICMPEchoPacket(header.IPv6ProtocolNumber, id, pkt)
}

func (t *stackGVisor) handleICMPEchoPacket(netProto tcpip.NetworkProtocolNumber, id stack.TransportEndpointID, pkt *stack.PacketBuffer) bool {
	srcIP := id.RemoteAddress
	dstIP := id.LocalAddress
	if srcIP.Len() == 0 || dstIP.Len() == 0 {
		return true
	}

	message := transportPacketBytes(pkt)
	ident, sequence, ok := tunicmp.ParseEchoRequest(netProto, message)
	if !ok {
		return true
	}

	reply, err := tunicmp.BuildLocalEchoReply(netProto, message, dstIP, srcIP)
	if err != nil {
		errors.LogInfoInner(t.ctx, err, "[tun] failed to build local icmp echo reply")
		return true
	}

	errors.LogDebug(t.ctx, "[tun][icmp] ", tunicmp.ProtocolLabel(netProto), " local echo reply ", dstIP, " -> ", srcIP, " id=", ident, " seq=", sequence)
	if err := t.writeRawICMPPacket(netProto, reply, dstIP, srcIP); err != nil {
		errors.LogInfoInner(t.ctx, err, "[tun] failed to write local icmp echo reply")
	}
	return true
}

func (t *stackGVisor) writeRawICMPPacket(netProto tcpip.NetworkProtocolNumber, message []byte, srcIP, dstIP tcpip.Address) error {
	if t.isClosed() {
		return io.ErrClosedPipe
	}
	ipHeaderSize := header.IPv6MinimumSize
	ipProtocol := header.IPv6ProtocolNumber
	transportProtocol := header.ICMPv6ProtocolNumber
	if netProto == header.IPv4ProtocolNumber {
		ipHeaderSize = header.IPv4MinimumSize
		ipProtocol = header.IPv4ProtocolNumber
		transportProtocol = header.ICMPv4ProtocolNumber
	}

	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
		ReserveHeaderBytes: ipHeaderSize,
		Payload:            buffer.MakeWithData(message),
	})
	defer pkt.DecRef()

	if netProto == header.IPv4ProtocolNumber {
		ipHdr := header.IPv4(pkt.NetworkHeader().Push(header.IPv4MinimumSize))
		ipHdr.Encode(&header.IPv4Fields{
			TotalLength: uint16(header.IPv4MinimumSize + len(message)),
			TTL:         64,
			Protocol:    uint8(transportProtocol),
			SrcAddr:     srcIP,
			DstAddr:     dstIP,
		})
		ipHdr.SetChecksum(^ipHdr.CalculateChecksum())
	} else {
		ipHdr := header.IPv6(pkt.NetworkHeader().Push(header.IPv6MinimumSize))
		ipHdr.Encode(&header.IPv6Fields{
			PayloadLength:     uint16(len(message)),
			TransportProtocol: transportProtocol,
			HopLimit:          64,
			SrcAddr:           srcIP,
			DstAddr:           dstIP,
		})
	}

	return t.writePacketToStack(ipProtocol, pkt, "icmp")
}

func transportPacketBytes(pkt *stack.PacketBuffer) []byte {
	headerBytes := pkt.TransportHeader().Slice()
	payloadBytes := pkt.Data().AsRange().ToSlice()
	message := make([]byte, len(headerBytes)+len(payloadBytes))
	copy(message, headerBytes)
	copy(message[len(headerBytes):], payloadBytes)
	return message
}

func createStack(ep stack.LinkEndpoint) (*stack.Stack, error) {
	opts := stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4, icmp.NewProtocol6},
		HandleLocal:        false,
	}
	gStack := stack.New(opts)

	if err := gStack.CreateNIC(defaultNIC, ep); err != nil {
		return nil, errors.New(err.String())
	}

	gStack.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: defaultNIC},
		{Destination: header.IPv6EmptySubnet, NIC: defaultNIC},
	})

	if err := gStack.SetSpoofing(defaultNIC, true); err != nil {
		return nil, errors.New(err.String())
	}
	if err := gStack.SetPromiscuousMode(defaultNIC, true); err != nil {
		return nil, errors.New(err.String())
	}

	cOpt := tcpip.CongestionControlOption("cubic")
	gStack.SetTransportProtocolOption(tcp.ProtocolNumber, &cOpt)
	sOpt := tcpip.TCPSACKEnabled(true)
	gStack.SetTransportProtocolOption(tcp.ProtocolNumber, &sOpt)
	mOpt := tcpip.TCPModerateReceiveBufferOption(true)
	gStack.SetTransportProtocolOption(tcp.ProtocolNumber, &mOpt)
	rOpt := tcpip.TCPRecovery(0)
	gStack.SetTransportProtocolOption(tcp.ProtocolNumber, &rOpt)

	tcpRXBufOpt := tcpip.TCPReceiveBufferSizeRangeOption{
		Min:     tcpRXBufMinSize,
		Default: tcpRXBufDefSize,
		Max:     tcpRXBufMaxSize,
	}
	if err := gStack.SetTransportProtocolOption(tcp.ProtocolNumber, &tcpRXBufOpt); err != nil {
		return nil, errors.New(err.String())
	}

	tcpTXBufOpt := tcpip.TCPSendBufferSizeRangeOption{
		Min:     tcpTXBufMinSize,
		Default: tcpTXBufDefSize,
		Max:     tcpTXBufMaxSize,
	}
	if err := gStack.SetTransportProtocolOption(tcp.ProtocolNumber, &tcpTXBufOpt); err != nil {
		return nil, errors.New(err.String())
	}

	return gStack, nil
}

// icmpEchoDropEndpoint wraps a LinkEndpoint so its NetworkDispatcher drops ICMPv4 Echo
// and ICMPv6 EchoRequest packets before gVisor's ipv4/ipv6 layers can auto-reply.
type icmpEchoDropEndpoint struct {
	stack.LinkEndpoint
}

func (e *icmpEchoDropEndpoint) Attach(dispatcher stack.NetworkDispatcher) {
	if dispatcher == nil {
		e.LinkEndpoint.Attach(nil)
		return
	}
	e.LinkEndpoint.Attach(&icmpEchoDropDispatcher{NetworkDispatcher: dispatcher})
}

type icmpEchoDropDispatcher struct {
	stack.NetworkDispatcher
}

func (d *icmpEchoDropDispatcher) DeliverNetworkPacket(protocol tcpip.NetworkProtocolNumber, pkt *stack.PacketBuffer) {
	if shouldDropICMPEcho(protocol, pkt) {
		return
	}
	d.NetworkDispatcher.DeliverNetworkPacket(protocol, pkt)
}

func shouldDropICMPEcho(protocol tcpip.NetworkProtocolNumber, pkt *stack.PacketBuffer) bool {
	if pkt == nil {
		return false
	}
	switch protocol {
	case header.IPv4ProtocolNumber:
		hdrBytes := pkt.Data().AsRange().Capped(header.IPv4MaximumHeaderSize + header.ICMPv4MinimumSize).ToSlice()
		if len(hdrBytes) < header.IPv4MinimumSize {
			return false
		}
		ipHdr := header.IPv4(hdrBytes)
		hlen := int(ipHdr.HeaderLength())
		if hlen < header.IPv4MinimumSize || len(hdrBytes) < hlen {
			return false
		}
		if ipHdr.Protocol() != uint8(header.ICMPv4ProtocolNumber) {
			return false
		}
		if ipHdr.FragmentOffset() != 0 {
			return false
		}
		rem := len(hdrBytes) - hlen
		if rem >= header.ICMPv4MinimumSize {
			icmpHdr := header.ICMPv4(hdrBytes[hlen:])
			return icmpHdr.Type() == header.ICMPv4Echo
		}
		if ipHdr.More() {
			return rem == 0 || hdrBytes[hlen] == uint8(header.ICMPv4Echo)
		}
		return false

	case header.IPv6ProtocolNumber:
		hdrBytes := pkt.Data().AsRange().ToSlice()
		if len(hdrBytes) < header.IPv6MinimumSize {
			return false
		}
		ipHdr := header.IPv6(hdrBytes)
		nextHdr := ipHdr.NextHeader()
		offset := header.IPv6MinimumSize
		isFirstFragment := false
		for offset <= len(hdrBytes) {
			if nextHdr == uint8(header.ICMPv6ProtocolNumber) {
				rem := len(hdrBytes) - offset
				if rem >= header.ICMPv6MinimumSize {
					icmpHdr := header.ICMPv6(hdrBytes[offset:])
					return icmpHdr.Type() == header.ICMPv6EchoRequest
				}
				if isFirstFragment {
					return rem == 0 || hdrBytes[offset] == uint8(header.ICMPv6EchoRequest)
				}
				return false
			}
			switch nextHdr {
			case 0, 43, 60: // Hop-by-Hop, Routing, Destination Options
				if len(hdrBytes) < offset+2 {
					return false
				}
				nextHdr = hdrBytes[offset]
				extLen := (int(hdrBytes[offset+1]) + 1) * 8
				if extLen <= 0 || offset+extLen > len(hdrBytes) {
					return false
				}
				offset += extLen
			case 44: // Fragment header
				if len(hdrBytes) < offset+8 {
					return false
				}
				fragOff := uint16(hdrBytes[offset+2])<<8 | uint16(hdrBytes[offset+3])
				if (fragOff & 0xFFF8) != 0 {
					return false
				}
				if (fragOff & 0x0001) != 0 {
					isFirstFragment = true
				}
				nextHdr = hdrBytes[offset]
				offset += 8
			case 253: // Experiment extension header (8 bytes)
				if len(hdrBytes) < offset+8 {
					return false
				}
				nextHdr = hdrBytes[offset]
				offset += 8
			default:
				return false
			}
		}
		return false
	default:
		return false
	}
}
