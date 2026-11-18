package yumi

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/platform"
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

type testProtector struct{}

func (testProtector) Protect(int) bool { return true }

type testGuard struct {
	mu    sync.Mutex
	calls []string
	allow bool
}

func (g *testGuard) AllowConnection(proto int, srcIP []byte, srcPort int, dstIP []byte, dstPort int) bool {
	g.mu.Lock()
	g.calls = append(g.calls, fmt.Sprintf("%d:%s:%d->%s:%d", proto, net.IP(srcIP), srcPort, net.IP(dstIP), dstPort))
	g.mu.Unlock()
	return g.allow
}

func testConfigJSON(dnsHost, dnsIP string, blockAll bool) string {
	dnsBlock := ""
	if dnsHost != "" && dnsIP != "" {
		dnsBlock = fmt.Sprintf(`"dns": {"hosts": {%q: %q}, "servers": ["localhost"]},`, dnsHost, dnsIP)
	}
	rules := `[]`
	if blockAll {
		// Deliberately route all ports to "block" so tests prove core.Dial bridge uses
		// activeOutboundTag + ForcedOutboundTag rather than falling back to Router rules.
		rules = `[{"type": "field", "port": "1-65535", "outboundTag": "block"}]`
	}
	return fmt.Sprintf(`{
		"log": {"loglevel": "warning"},
		%s
		"inbounds": [
			{
				"tag": "tun-in",
				"protocol": "tun",
				"settings": {"name": "tun0", "mtu": 1400, "userLevel": 0},
				"sniffing": {"enabled": true, "destOverride": ["http", "tls"], "routeOnly": true}
			}
		],
		"outbounds": [
			{"tag": "proxy-a", "protocol": "freedom"},
			{"tag": "proxy-b", "protocol": "freedom"},
			{"tag": "block", "protocol": "blackhole"}
		],
		"routing": {
			"balancers": [{"tag": "proxy", "selector": ["proxy-"]}],
			"rules": %s
		}
	}`, dnsBlock, rules)
}

func TestStartWithGuard_ValidationAndCleanup(t *testing.T) {
	_ = Stop()

	// 1. Missing tun-in inbound.
	noTun := `{"inbounds": [], "outbounds": [{"tag": "proxy-a", "protocol": "freedom"}]}`
	if err := StartWithGuard(noTun, 10, "", testProtector{}, &testGuard{allow: true}); err == nil {
		_ = Stop()
		t.Fatal("expected error when tun-in inbound is missing")
	}
	protectorMu.RLock()
	p := protector
	protectorMu.RUnlock()
	if p != nil {
		t.Fatal("protector must be cleared to nil when StartWithGuard fails")
	}

	// 2. Multiple tun-in inbounds.
	twoTun := `{
		"inbounds": [
			{"tag": "tun-in", "protocol": "tun", "settings": {"name": "tun0", "mtu": 1500}},
			{"tag": "tun-2", "protocol": "tun", "settings": {"name": "tun1", "mtu": 1500}}
		],
		"outbounds": [{"tag": "proxy-a", "protocol": "freedom"}]
	}`
	if err := StartWithGuard(twoTun, 10, "", testProtector{}, &testGuard{allow: true}); err == nil {
		_ = Stop()
		t.Fatal("expected error when multiple tun inbounds exist")
	}

	// 3. Invalid tunFd <= 0.
	if err := StartWithGuard(testConfigJSON("", "", false), 0, "proxy-a", testProtector{}, nil); err == nil {
		_ = Stop()
		t.Fatal("expected error when tunFd <= 0")
	}

	// 4. Unknown pinnedTag must fail and clean up protector + instance.
	ep := channel.New(16, 1400, "")
	defer ep.Close()
	if err := startCore(testConfigJSON("", "", false), 10, "proxy-missing", testProtector{}, &testGuard{allow: true}, ep); err == nil {
		_ = Stop()
		t.Fatal("expected error for unknown pinnedTag")
	}
	if IsRunning() {
		t.Fatal("IsRunning must be false after failed startCore")
	}
	protectorMu.RLock()
	p = protector
	protectorMu.RUnlock()
	if p != nil {
		t.Fatal("protector must be cleared after failed pinnedTag")
	}
}

func TestStartWithGuard_TunInterceptionAndLifecycle(t *testing.T) {
	_ = Stop()
	_ = os.Unsetenv(platform.NormalizeEnvName(platform.TunFdKey))

	ep := channel.New(32, 1400, "")
	defer ep.Close()
	guard := &testGuard{allow: false}

	if err := startCore(testConfigJSON("", "", false), 0, "proxy-b", testProtector{}, guard, ep); err != nil {
		t.Fatalf("startCore failed: %v", err)
	}
	defer func() { _ = Stop() }()

	if !IsRunning() {
		t.Fatal("IsRunning must be true")
	}
	if env := os.Getenv(platform.NormalizeEnvName(platform.TunFdKey)); env != "" {
		t.Fatalf("XRAY_TUN_FD must not be set, got %q", env)
	}
	if got := ActiveOutbound(); got != "proxy-b" {
		t.Fatalf("expected pinned outbound proxy-b, got %q", got)
	}
	if err := SelectOutbound("proxy-a"); err != nil {
		t.Fatalf("SelectOutbound proxy-a failed: %v", err)
	}
	if got := ActiveOutbound(); got != "proxy-a" {
		t.Fatalf("expected proxy-a after SelectOutbound, got %q", got)
	}

	// Inject a TCP SYN into the intercepted "tun-in" channel endpoint -> guard must reject with RST.
	src := [4]byte{172, 19, 0, 1}
	dst := [4]byte{93, 184, 216, 34}
	totalLen := header.IPv4MinimumSize + header.TCPMinimumSize
	syn := make([]byte, totalLen)
	ip := header.IPv4(syn[:header.IPv4MinimumSize])
	ip.Encode(&header.IPv4Fields{
		TotalLength: uint16(totalLen),
		TTL:         64,
		Protocol:    uint8(header.TCPProtocolNumber),
		SrcAddr:     tcpip.AddrFrom4(src),
		DstAddr:     tcpip.AddrFrom4(dst),
	})
	ip.SetChecksum(^ip.CalculateChecksum())
	th := header.TCP(syn[header.IPv4MinimumSize:])
	th.Encode(&header.TCPFields{
		SrcPort:    44444,
		DstPort:    443,
		SeqNum:     12345,
		DataOffset: header.TCPMinimumSize,
		Flags:      header.TCPFlagSyn,
		WindowSize: 65535,
	})
	xsum := header.PseudoHeaderChecksum(header.TCPProtocolNumber, tcpip.AddrFrom4(src), tcpip.AddrFrom4(dst), uint16(header.TCPMinimumSize))
	th.SetChecksum(^th.CalculateChecksum(xsum))

	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(syn)})
	ep.InjectInbound(header.IPv4ProtocolNumber, pkt)
	pkt.DecRef()

	deadline := time.Now().Add(2 * time.Second)
	gotRST := false
	for time.Now().Before(deadline) {
		resp := ep.Read()
		if resp != nil {
			v := resp.ToView()
			b := v.AsSlice()
			resp.DecRef()
			if len(b) >= header.IPv4MinimumSize+header.TCPMinimumSize {
				respTH := header.TCP(b[header.IPv4(b).HeaderLength():])
				if respTH.Flags().Contains(header.TCPFlagRst) {
					gotRST = true
					v.Release()
					break
				}
			}
			v.Release()
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !gotRST {
		t.Fatal("expected RST from intercepted guarded tun-in")
	}

	guard.mu.Lock()
	calls := len(guard.calls)
	guard.mu.Unlock()
	if calls == 0 {
		t.Fatal("expected TrafficGuard to be invoked by intercepted tun-in")
	}

	if err := Stop(); err != nil {
		t.Fatalf("Stop failed: %v", err)
	}
	if IsRunning() {
		t.Fatal("IsRunning must be false after Stop")
	}
	if err := Stop(); err != nil {
		t.Fatalf("second Stop must be idempotent, got %v", err)
	}
}

func TestCoreDialBridge_WhenStoppedReturnsError(t *testing.T) {
	_ = Stop()
	if _, err := ProbeHTTP("http://127.0.0.1:80/generate_204", 500, false); err == nil {
		t.Fatal("ProbeHTTP must error when core is not running")
	}
	if _, err := OpenHTTP("GET", "http://127.0.0.1:80/", "", 500, 500, false); err == nil {
		t.Fatal("OpenHTTP must error when core is not running")
	}
	if _, err := DialTunnel("tcp", "127.0.0.1:80", 500); err == nil {
		t.Fatal("DialTunnel must error when core is not running")
	}
	if _, err := DialTunnelTLS("127.0.0.1", 443, "example.com", 500); err == nil {
		t.Fatal("DialTunnelTLS must error when core is not running")
	}
}

func TestCoreDialBridge_ProbeOpenAndDialTunnel(t *testing.T) {
	_ = Stop()

	var seenUA atomic.Value
	var seenCustom atomic.Value
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenUA.Store(r.Header.Get("User-Agent"))
		seenCustom.Store(r.Header.Get("X-Test-Header"))
		switch r.URL.Path {
		case "/generate_204":
			w.WriteHeader(http.StatusNoContent)
		case "/redirect":
			w.Header().Set("Location", "https://example.com/next")
			w.WriteHeader(http.StatusFound)
		case "/stream":
			w.Header().Set("X-Custom", "yumi-val")
			w.Header().Set("Content-Type", "text/plain")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("0123456789abcdef"))
		case "/notfound":
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte("missing-body"))
		case "/slow":
			time.Sleep(400 * time.Millisecond)
			w.WriteHeader(http.StatusOK)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer srv.Close()

	// TCP echo server for DialTunnel.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("net.Listen failed: %v", err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				buf := make([]byte, 256)
				for {
					n, err := conn.Read(buf)
					if err != nil {
						return
					}
					if string(buf[:n]) == "SILENT" {
						time.Sleep(500 * time.Millisecond)
						return
					}
					_, _ = conn.Write(buf[:n])
				}
			}(c)
		}
	}()

	ep := channel.New(16, 1400, "")
	defer ep.Close()
	if err := startCore(testConfigJSON("probe.yumi.test", "127.0.0.1", true), 0, "proxy-a", testProtector{}, &testGuard{allow: true}, ep); err != nil {
		t.Fatalf("startCore failed: %v", err)
	}
	defer func() { _ = Stop() }()

	// 1. ProbeHTTP: 204 (even with catch-all "block" rule in routing!).
	code, err := ProbeHTTP(srv.URL+"/generate_204", 3000, false)
	if err != nil || code != 204 {
		t.Fatalf("ProbeHTTP 204 failed: code=%d err=%v", code, err)
	}
	if ua, _ := seenUA.Load().(string); ua != "Yumi" {
		t.Fatalf("expected ProbeHTTP User-Agent 'Yumi', got %q", ua)
	}

	// 2. ProbeHTTP: 302 not followed.
	code, err = ProbeHTTP(srv.URL+"/redirect", 3000, false)
	if err != nil || code != 302 {
		t.Fatalf("ProbeHTTP redirect must return 302 without following, got code=%d err=%v", code, err)
	}

	// 3. ProbeHTTP with resolveDNS=true resolving "probe.yumi.test" -> 127.0.0.1 via Xray DNS client.
	_, port, _ := net.SplitHostPort(strings.TrimPrefix(srv.URL, "http://"))
	code, err = ProbeHTTP("http://probe.yumi.test:"+port+"/generate_204", 3000, true)
	if err != nil || code != 204 {
		t.Fatalf("ProbeHTTP resolveDNS=true failed: code=%d err=%v", code, err)
	}

	// 4. ProbeHTTP timeout enforcement.
	if _, err := ProbeHTTP(srv.URL+"/slow", 80, false); err == nil {
		t.Fatal("expected ProbeHTTP timeout on slow endpoint")
	}

	// 5. OpenHTTP invalid headersJSON.
	if _, err := OpenHTTP("GET", srv.URL+"/stream", "{bad-json", 2000, 2000, false); err == nil {
		t.Fatal("expected error on invalid headersJSON")
	}

	// 6. OpenHTTP streaming Read + Header + HeadersJSON.
	resp, err := OpenHTTP("GET", srv.URL+"/stream", `{"X-Test-Header":"abc"}`, 2000, 2000, false)
	if err != nil {
		t.Fatalf("OpenHTTP stream failed: %v", err)
	}
	if resp.StatusCode != 200 || resp.Header("x-custom") != "yumi-val" {
		_ = resp.Close()
		t.Fatalf("unexpected OpenHTTP metadata: status=%d hdr=%q", resp.StatusCode, resp.Header("x-custom"))
	}
	if v, _ := seenCustom.Load().(string); v != "abc" {
		_ = resp.Close()
		t.Fatalf("expected custom request header 'abc', got %q", v)
	}
	var hdrMap map[string]string
	if err := json.Unmarshal([]byte(resp.HeadersJSON), &hdrMap); err != nil || hdrMap["x-custom"] != "yumi-val" {
		_ = resp.Close()
		t.Fatalf("unexpected HeadersJSON %q: %v", resp.HeadersJSON, err)
	}
	var assembled []byte
	for {
		chunk, err := resp.Read(5)
		if err != nil {
			_ = resp.Close()
			t.Fatalf("OpenHTTP Read error: %v", err)
		}
		if chunk == nil {
			break
		}
		assembled = append(assembled, chunk...)
	}
	_ = resp.Close()
	if string(assembled) != "0123456789abcdef" {
		t.Fatalf("expected streamed body '0123456789abcdef', got %q", string(assembled))
	}

	// 7. OpenHTTP 302 Location + 404 body via ReadAll.
	redirResp, err := OpenHTTP("GET", srv.URL+"/redirect", "", 2000, 2000, false)
	if err != nil {
		t.Fatalf("OpenHTTP redirect failed: %v", err)
	}
	if redirResp.StatusCode != 302 || redirResp.Location != "https://example.com/next" {
		_ = redirResp.Close()
		t.Fatalf("unexpected redirect response: status=%d location=%q", redirResp.StatusCode, redirResp.Location)
	}
	_ = redirResp.Close()

	nfResp, err := OpenHTTP("GET", srv.URL+"/notfound", "", 2000, 2000, false)
	if err != nil {
		t.Fatalf("OpenHTTP notfound failed: %v", err)
	}
	bodyBytes, err := nfResp.ReadAll(7)
	_ = nfResp.Close()
	if err != nil || string(bodyBytes) != "missing" {
		t.Fatalf("expected ReadAll(7)='missing', got %q err=%v", string(bodyBytes), err)
	}

	// 8. DialTunnel bidirectional data + SetReadTimeout + Close unblocking.
	tc, err := DialTunnel("tcp", ln.Addr().String(), 2000)
	if err != nil {
		t.Fatalf("DialTunnel failed: %v", err)
	}
	if _, err := tc.Write([]byte("echo-test")); err != nil {
		_ = tc.Close()
		t.Fatalf("TunnelConn.Write failed: %v", err)
	}
	got, err := tc.Read(64)
	if err != nil || string(got) != "echo-test" {
		_ = tc.Close()
		t.Fatalf("TunnelConn.Read failed: got=%q err=%v", string(got), err)
	}

	// Verify per-read timeout fires when peer stays silent.
	tc.SetReadTimeout(80)
	if _, err := tc.Write([]byte("SILENT")); err != nil {
		_ = tc.Close()
		t.Fatalf("TunnelConn.Write SILENT failed: %v", err)
	}
	if _, err := tc.Read(64); err == nil {
		_ = tc.Close()
		t.Fatal("expected read timeout error on silent peer")
	}
	_ = tc.Close()

	// Verify Close immediately unblocks concurrent Read.
	tc2, err := DialTunnel("tcp", ln.Addr().String(), 2000)
	if err != nil {
		t.Fatalf("DialTunnel 2 failed: %v", err)
	}
	readDone := make(chan error, 1)
	go func() {
		_, err := tc2.Read(64)
		readDone <- err
	}()
	time.Sleep(40 * time.Millisecond)
	_ = tc2.Close()
	select {
	case <-readDone:
	case <-time.After(1 * time.Second):
		t.Fatal("TunnelConn.Close did not unblock concurrent Read")
	}

	// 9. DialTunnelTLS against non-TLS echo server must fail handshake cleanly within timeout.
	host, portStr, _ := net.SplitHostPort(ln.Addr().String())
	var portInt int
	_, _ = fmt.Sscanf(portStr, "%d", &portInt)
	if _, err := DialTunnelTLS(host, portInt, "example.com", 200); err == nil {
		t.Fatal("expected DialTunnelTLS handshake failure against plain TCP echo server")
	}
}

func TestStartWithGuard_TypedNilGuardDoesNotPanicAndAllowsTraffic(t *testing.T) {
	_ = Stop()

	// Local TCP listener and UDP echo server on 127.0.0.1 so freedom outbound completes cleanly.
	tcpLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("net.Listen failed: %v", err)
	}
	defer tcpLn.Close()
	go func() {
		for {
			c, err := tcpLn.Accept()
			if err != nil {
				return
			}
			_ = c.Close()
		}
	}()
	tcpPort := tcpLn.Addr().(*net.TCPAddr).Port

	udpConn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("net.ListenPacket failed: %v", err)
	}
	defer udpConn.Close()
	go func() {
		buf := make([]byte, 512)
		for {
			n, addr, err := udpConn.ReadFrom(buf)
			if err != nil {
				return
			}
			_, _ = udpConn.WriteTo(append([]byte("echo:"), buf[:n]...), addr)
		}
	}()
	udpPort := udpConn.LocalAddr().(*net.UDPAddr).Port

	ep := channel.New(64, 1400, "")
	defer ep.Close()

	cfgJSON := fmt.Sprintf(`{
		"log": {"loglevel": "warning"},
		"inbounds": [
			{
				"tag": "tun-in",
				"protocol": "tun",
				"settings": {"name": "tun0", "mtu": 1400, "userLevel": 0}
			}
		],
		"outbounds": [
			{"tag": "proxy-tcp", "protocol": "freedom", "settings": {"redirect": "127.0.0.1:%d"}},
			{"tag": "proxy-udp", "protocol": "freedom", "settings": {"redirect": "127.0.0.1:%d"}}
		],
		"routing": {
			"balancers": [{"tag": "proxy", "selector": ["proxy-"]}],
			"rules": [
				{"type": "field", "network": "tcp", "outboundTag": "proxy-tcp"},
				{"type": "field", "network": "udp", "outboundTag": "proxy-udp"}
			]
		}
	}`, tcpPort, udpPort)

	var typedNil *panickingNilGuard
	if err := startCore(cfgJSON, 0, "proxy-tcp", testProtector{}, typedNil, ep); err != nil {
		t.Fatalf("startCore with typed-nil guard failed: %v", err)
	}
	defer func() { _ = Stop() }()

	srcV4 := [4]byte{172, 19, 0, 1}
	dstPublic := [4]byte{203, 0, 113, 10}

	// 1. Inject IPv4 TCP SYN -> must not panic and must reply with SYN-ACK.
	synLen := header.IPv4MinimumSize + header.TCPMinimumSize
	syn := make([]byte, synLen)
	ipTCP := header.IPv4(syn[:header.IPv4MinimumSize])
	ipTCP.Encode(&header.IPv4Fields{
		TotalLength: uint16(synLen),
		TTL:         64,
		Protocol:    uint8(header.TCPProtocolNumber),
		SrcAddr:     tcpip.AddrFrom4(srcV4),
		DstAddr:     tcpip.AddrFrom4(dstPublic),
	})
	ipTCP.SetChecksum(^ipTCP.CalculateChecksum())
	th := header.TCP(syn[header.IPv4MinimumSize:])
	th.Encode(&header.TCPFields{
		SrcPort:    45001,
		DstPort:    uint16(tcpPort),
		SeqNum:     1000,
		DataOffset: header.TCPMinimumSize,
		Flags:      header.TCPFlagSyn,
		WindowSize: 65535,
	})
	tcpXsum := header.PseudoHeaderChecksum(header.TCPProtocolNumber, tcpip.AddrFrom4(srcV4), tcpip.AddrFrom4(dstPublic), uint16(header.TCPMinimumSize))
	th.SetChecksum(^th.CalculateChecksum(tcpXsum))

	pktTCP := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(syn)})
	ep.InjectInbound(header.IPv4ProtocolNumber, pktTCP)
	pktTCP.DecRef()

	deadline := time.Now().Add(2 * time.Second)
	gotSynAck := false
	for time.Now().Before(deadline) {
		resp := ep.Read()
		if resp == nil {
			time.Sleep(10 * time.Millisecond)
			continue
		}
		v := resp.ToView()
		b := append([]byte(nil), v.AsSlice()...)
		v.Release()
		resp.DecRef()
		if len(b) >= header.IPv4MinimumSize+header.TCPMinimumSize && header.IPv4(b).Protocol() == uint8(header.TCPProtocolNumber) {
			respTH := header.TCP(b[header.IPv4(b).HeaderLength():])
			if respTH.Flags().Contains(header.TCPFlagSyn | header.TCPFlagAck) {
				gotSynAck = true
				break
			}
		}
	}
	if !gotSynAck {
		t.Fatal("expected TCP SYN-ACK when typed-nil guard is passed to startCore")
	}

	// 2. Inject IPv4 UDP packet -> must not panic and must forward/echo back.
	udpPayload := []byte("typed-nil-udp")
	udpLen := header.UDPMinimumSize + len(udpPayload)
	udpTotal := header.IPv4MinimumSize + udpLen
	udpRaw := make([]byte, udpTotal)
	copy(udpRaw[header.IPv4MinimumSize+header.UDPMinimumSize:], udpPayload)
	ipUDP := header.IPv4(udpRaw[:header.IPv4MinimumSize])
	ipUDP.Encode(&header.IPv4Fields{
		TotalLength: uint16(udpTotal),
		TTL:         64,
		Protocol:    uint8(header.UDPProtocolNumber),
		SrcAddr:     tcpip.AddrFrom4(srcV4),
		DstAddr:     tcpip.AddrFrom4(dstPublic),
	})
	ipUDP.SetChecksum(^ipUDP.CalculateChecksum())
	uh := header.UDP(udpRaw[header.IPv4MinimumSize:])
	uh.Encode(&header.UDPFields{
		SrcPort: 45002,
		DstPort: uint16(udpPort),
		Length:  uint16(udpLen),
	})
	udpXsum := header.PseudoHeaderChecksum(header.UDPProtocolNumber, tcpip.AddrFrom4(srcV4), tcpip.AddrFrom4(dstPublic), uint16(udpLen))
	uh.SetChecksum(^uh.CalculateChecksum(checksum.Checksum(udpPayload, udpXsum)))

	pktUDP := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(udpRaw)})
	ep.InjectInbound(header.IPv4ProtocolNumber, pktUDP)
	pktUDP.DecRef()

	deadline = time.Now().Add(2 * time.Second)
	gotUDPEcho := false
	for time.Now().Before(deadline) {
		resp := ep.Read()
		if resp == nil {
			time.Sleep(10 * time.Millisecond)
			continue
		}
		v := resp.ToView()
		b := append([]byte(nil), v.AsSlice()...)
		v.Release()
		resp.DecRef()
		if len(b) > header.IPv4MinimumSize+header.UDPMinimumSize && header.IPv4(b).Protocol() == uint8(header.UDPProtocolNumber) {
			gotPayload := string(b[int(header.IPv4(b).HeaderLength())+header.UDPMinimumSize:])
			if gotPayload == "echo:typed-nil-udp" {
				gotUDPEcho = true
				break
			}
		}
	}
	if !gotUDPEcho {
		t.Fatal("expected UDP echo reply when typed-nil guard is passed to startCore")
	}

	// 3. Inject IPv4 & IPv6 ICMP echo requests -> must not panic and must reply locally.
	icmp4Total := header.IPv4MinimumSize + header.ICMPv4MinimumSize + 4
	icmp4Raw := make([]byte, icmp4Total)
	copy(icmp4Raw[header.IPv4MinimumSize+header.ICMPv4MinimumSize:], []byte("ping"))
	ip4 := header.IPv4(icmp4Raw[:header.IPv4MinimumSize])
	ip4.Encode(&header.IPv4Fields{
		TotalLength: uint16(icmp4Total),
		TTL:         64,
		Protocol:    uint8(header.ICMPv4ProtocolNumber),
		SrcAddr:     tcpip.AddrFrom4(srcV4),
		DstAddr:     tcpip.AddrFrom4([4]byte{172, 19, 0, 2}),
	})
	ip4.SetChecksum(^ip4.CalculateChecksum())
	ic4 := header.ICMPv4(icmp4Raw[header.IPv4MinimumSize:])
	ic4.SetType(header.ICMPv4Echo)
	ic4.SetCode(0)
	ic4.SetIdent(1)
	ic4.SetSequence(1)
	ic4.SetChecksum(0)
	ic4.SetChecksum(header.ICMPv4Checksum(ic4, 0))

	pktICMP4 := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(icmp4Raw)})
	ep.InjectInbound(header.IPv4ProtocolNumber, pktICMP4)
	pktICMP4.DecRef()

	deadline = time.Now().Add(2 * time.Second)
	gotICMP4 := false
	for time.Now().Before(deadline) {
		resp := ep.Read()
		if resp == nil {
			time.Sleep(10 * time.Millisecond)
			continue
		}
		v := resp.ToView()
		b := append([]byte(nil), v.AsSlice()...)
		v.Release()
		resp.DecRef()
		if len(b) >= header.IPv4MinimumSize+header.ICMPv4MinimumSize && header.IPv4(b).Protocol() == uint8(header.ICMPv4ProtocolNumber) {
			if header.ICMPv4(b[header.IPv4(b).HeaderLength():]).Type() == header.ICMPv4EchoReply {
				gotICMP4 = true
				break
			}
		}
	}
	if !gotICMP4 {
		t.Fatal("expected ICMPv4 echo reply when typed-nil guard is passed to startCore")
	}

	srcV6 := [16]byte{0xfd, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}
	dstV6 := [16]byte{0xfd, 0xfe, 0xdc, 0xba, 0x98, 0x76, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2}
	icmp6Len := header.ICMPv6MinimumSize + 4
	icmp6Raw := make([]byte, header.IPv6MinimumSize+icmp6Len)
	copy(icmp6Raw[header.IPv6MinimumSize+header.ICMPv6MinimumSize:], []byte("png6"))
	ip6 := header.IPv6(icmp6Raw[:header.IPv6MinimumSize])
	ip6.Encode(&header.IPv6Fields{
		PayloadLength:     uint16(icmp6Len),
		TransportProtocol: header.ICMPv6ProtocolNumber,
		HopLimit:          64,
		SrcAddr:           tcpip.AddrFrom16(srcV6),
		DstAddr:           tcpip.AddrFrom16(dstV6),
	})
	ic6 := header.ICMPv6(icmp6Raw[header.IPv6MinimumSize:])
	ic6.SetType(header.ICMPv6EchoRequest)
	ic6.SetCode(0)
	ic6.SetIdent(2)
	ic6.SetSequence(1)
	ic6.SetChecksum(0)
	ic6.SetChecksum(header.ICMPv6Checksum(header.ICMPv6ChecksumParams{
		Header: ic6,
		Src:    tcpip.AddrFrom16(srcV6),
		Dst:    tcpip.AddrFrom16(dstV6),
	}))

	pktICMP6 := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(icmp6Raw)})
	ep.InjectInbound(header.IPv6ProtocolNumber, pktICMP6)
	pktICMP6.DecRef()

	deadline = time.Now().Add(2 * time.Second)
	gotICMP6 := false
	for time.Now().Before(deadline) {
		resp := ep.Read()
		if resp == nil {
			time.Sleep(10 * time.Millisecond)
			continue
		}
		v := resp.ToView()
		b := append([]byte(nil), v.AsSlice()...)
		v.Release()
		resp.DecRef()
		if len(b) >= header.IPv6MinimumSize+header.ICMPv6MinimumSize && header.IPv6(b).NextHeader() == uint8(header.ICMPv6ProtocolNumber) {
			if header.ICMPv6(b[header.IPv6MinimumSize:]).Type() == header.ICMPv6EchoReply {
				gotICMP6 = true
				break
			}
		}
	}
	if !gotICMP6 {
		t.Fatal("expected ICMPv6 echo reply when typed-nil guard is passed to startCore")
	}
}
