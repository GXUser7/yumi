package tun

import (
	"context"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/errors"
	v2net "github.com/xtls/xray-core/common/net"
)

type udpPacket struct {
	data []byte
	dest *v2net.Destination
}

// udpEndpointKey uniquely identifies an IPv4 or IPv6 UDP endpoint without heap allocations.
type udpEndpointKey struct {
	isIPv6 bool
	ip     [16]byte
	port   uint16
}

func makeUDPEndpointKey(addr netip.Addr, port uint16) udpEndpointKey {
	k := udpEndpointKey{
		isIPv6: addr.Is6(),
		port:   port,
	}
	if addr.Is4() {
		a4 := addr.As4()
		copy(k.ip[:4], a4[:])
	} else {
		k.ip = addr.As16()
	}
	return k
}

const defaultUDPAllowCacheTTL = 2 * time.Second

type udpConnectionHandler struct {
	sync.RWMutex

	closed   bool
	udpConns map[udpEndpointKey]*udpConn

	guard            TrafficGuard
	allowCacheTTL    time.Duration
	now              func() time.Time
	handleConnection func(conn net.Conn, dest v2net.Destination)
	writePacket      func(data []byte, src v2net.Destination, dst v2net.Destination) error
}

func newUDPConnectionHandler(
	guard TrafficGuard,
	handleConnection func(conn net.Conn, dest v2net.Destination),
	writePacket func(data []byte, src v2net.Destination, dst v2net.Destination) error,
) *udpConnectionHandler {
	return &udpConnectionHandler{
		udpConns:         make(map[udpEndpointKey]*udpConn),
		guard:            NormalizeTrafficGuard(guard),
		allowCacheTTL:    defaultUDPAllowCacheTTL,
		now:              time.Now,
		handleConnection: handleConnection,
		writePacket:      writePacket,
	}
}

// HandlePacket enforces TrafficGuard per 5-tuple (srcIP, srcPort, dstIP, dstPort) with
// immediate re-validation for DNS (dstPort == 53) and short-TTL re-validation for other UDP
// destinations, evicting denied entries and empty udpConn flows cleanly.
func (u *udpConnectionHandler) HandlePacket(
	src v2net.Destination,
	dst v2net.Destination,
	srcKey udpEndpointKey,
	dstKey udpEndpointKey,
	srcIP []byte,
	srcPort int,
	dstIP []byte,
	dstPort int,
	data []byte,
) {
	nowFn := u.now
	if nowFn == nil {
		nowFn = time.Now
	}
	ttl := u.allowCacheTTL
	if ttl <= 0 {
		ttl = defaultUDPAllowCacheTTL
	}
	now := nowFn()

	// Fast path under RLock:
	// - When guard == nil, preserve unguarded caching.
	// - When guard != nil, re-validate DNS (dstPort == 53) on every packet and non-DNS UDP
	//   destinations once their short allowCacheTTL expires.
	u.RLock()
	if u.closed {
		u.RUnlock()
		return
	}
	conn, found := u.udpConns[srcKey]
	if found {
		if expiresAt, allowed := conn.allowedDests[dstKey]; allowed {
			if u.guard == nil || (dstPort != 53 && now.Before(expiresAt)) {
				u.enqueueLocked(conn, dst, data)
				u.RUnlock()
				return
			}
		}
	}
	u.RUnlock()

	// Slow path: invoke TrafficGuard OUTSIDE mutex so JNI/Binder latency never stalls other flows.
	if u.guard != nil && !u.guard.AllowConnection(17, srcIP, srcPort, dstIP, dstPort) {
		// Fail-closed silent drop; never cache false and evict any previously cached entry.
		var evicted *udpConn
		u.Lock()
		if !u.closed {
			if conn, found := u.udpConns[srcKey]; found {
				delete(conn.allowedDests, dstKey)
				if len(conn.allowedDests) == 0 {
					delete(u.udpConns, srcKey)
					evicted = conn
				}
			}
		}
		u.Unlock()
		if evicted != nil {
			evicted.closeOnce.Do(func() {
				close(evicted.egress)
			})
		}
		return
	}

	var expiresAt time.Time
	if u.guard != nil && dstPort != 53 {
		expiresAt = nowFn().Add(ttl)
	}

	u.Lock()
	defer u.Unlock()
	if u.closed {
		return
	}

	conn, found = u.udpConns[srcKey]
	if !found {
		conn = &udpConn{
			handler:      u,
			egress:       make(chan *udpPacket, 1024),
			srcKey:       srcKey,
			src:          src,
			dst:          dst,
			allowedDests: make(map[udpEndpointKey]time.Time),
		}
		conn.allowedDests[dstKey] = expiresAt
		u.udpConns[srcKey] = conn
		go u.handleConnection(conn, dst)
	} else {
		conn.allowedDests[dstKey] = expiresAt
	}

	u.enqueueLocked(conn, dst, data)
}

func (u *udpConnectionHandler) enqueueLocked(conn *udpConn, dst v2net.Destination, data []byte) {
	dstCopy := dst
	select {
	case conn.egress <- &udpPacket{
		data: data,
		dest: &dstCopy,
	}:
	default:
		errors.LogDebug(context.Background(), "drop udp with size ", len(data), " to ", dst.NetAddr(), " > queue full")
	}
}

func (u *udpConnectionHandler) connectionFinished(c *udpConn) {
	u.Lock()
	if cur, found := u.udpConns[c.srcKey]; found && cur == c {
		delete(u.udpConns, c.srcKey)
	}
	u.Unlock()
	c.closeOnce.Do(func() {
		close(c.egress)
	})
}

// CloseAll tears down all active UDP flows and evicts all cached 5-tuple permissions.
func (u *udpConnectionHandler) CloseAll() {
	u.Lock()
	u.closed = true
	conns := make([]*udpConn, 0, len(u.udpConns))
	for k, c := range u.udpConns {
		conns = append(conns, c)
		delete(u.udpConns, k)
	}
	u.Unlock()

	for _, c := range conns {
		c.closeOnce.Do(func() {
			close(c.egress)
		})
	}
}

type udpConn struct {
	handler      *udpConnectionHandler
	egress       chan *udpPacket
	srcKey       udpEndpointKey
	src          v2net.Destination
	dst          v2net.Destination
	allowedDests map[udpEndpointKey]time.Time // read under u.RLock, mutated under u.Lock
	closeOnce    sync.Once
}

func (c *udpConn) ReadMultiBuffer() (buf.MultiBuffer, error) {
	for {
		e, ok := <-c.egress
		if !ok {
			return nil, io.EOF
		}

		b := buf.New()
		if _, err := b.Write(e.data); err != nil {
			errors.LogErrorInner(context.Background(), err, "drop packet to ", e.dest, " with size ", len(e.data))
			b.Release()
			continue
		}
		b.UDP = e.dest
		return buf.MultiBuffer{b}, nil
	}
}

func (c *udpConn) Read(p []byte) (int, error) {
	e, ok := <-c.egress
	if !ok {
		return 0, io.EOF
	}
	n := copy(p, e.data)
	if n != len(e.data) {
		return 0, io.ErrShortBuffer
	}
	return n, nil
}

func (c *udpConn) WriteMultiBuffer(mb buf.MultiBuffer) error {
	c.handler.RLock()
	closed := c.handler.closed
	c.handler.RUnlock()
	if closed {
		buf.ReleaseMulti(mb)
		return io.ErrClosedPipe
	}
	for i, b := range mb {
		dst := c.dst
		if b.UDP != nil {
			if b.UDP.Address.Family().IsDomain() {
				errors.LogError(context.Background(), "impossible domain packet ", b.UDP, " reply via original target ", dst)
			} else {
				dst = *b.UDP
			}
		}
		if err := c.handler.writePacket(b.Bytes(), dst, c.src); err != nil {
			buf.ReleaseMulti(mb[i:])
			return err
		}
		b.Release()
	}
	return nil
}

func (c *udpConn) Write(p []byte) (int, error) {
	c.handler.RLock()
	closed := c.handler.closed
	c.handler.RUnlock()
	if closed {
		return 0, io.ErrClosedPipe
	}
	if err := c.handler.writePacket(p, c.dst, c.src); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *udpConn) Close() error {
	c.handler.connectionFinished(c)
	return nil
}

func (c *udpConn) LocalAddr() net.Addr {
	return c.dst.RawNetAddr()
}

func (c *udpConn) RemoteAddr() net.Addr {
	return c.src.RawNetAddr()
}

func (c *udpConn) SetDeadline(time.Time) error      { return nil }
func (c *udpConn) SetReadDeadline(time.Time) error  { return nil }
func (c *udpConn) SetWriteDeadline(time.Time) error { return nil }
