package yumi_test

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	yumi "github.com/GXUser7/yumi/core-xray"
	xlog "github.com/xtls/xray-core/common/log"
	v2net "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/inbound"
	"github.com/xtls/xray-core/features/outbound"
	"github.com/xtls/xray-core/features/routing"
	routing_session "github.com/xtls/xray-core/features/routing/session"
	"github.com/xtls/xray-core/features/stats"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
)

func init() {
	// Keep xray-core test output clean while exercising real core instances.
	xlog.RegisterHandler(noopLogHandler{})
}

type noopLogHandler struct{}

func (noopLogHandler) Handle(xlog.Message) {}

// buildProductionShapedConfig builds a Yumi production-shaped Xray JSON config.
// When includeTunInbound is false, "inbounds" is [] so real core.Instance.Start()
// runs on any host OS (including Windows without Wintun) with zero loopback proxies/APIs.
func buildProductionShapedConfig(includeTunInbound bool, proxyOutbounds []map[string]any, bypassLan bool) (string, error) {
	inbounds := []any{}
	if includeTunInbound {
		inbounds = append(inbounds, map[string]any{
			"tag":      "tun-in",
			"protocol": "tun",
			"settings": map[string]any{
				"name": "tun0",
				"mtu":  9000,
			},
			"sniffing": map[string]any{
				"enabled":      true,
				"destOverride": []string{"http", "tls", "quic"},
				"routeOnly":    true,
			},
		})
	}

	outbounds := make([]any, 0, len(proxyOutbounds)+3)
	for _, ob := range proxyOutbounds {
		outbounds = append(outbounds, ob)
	}
	outbounds = append(outbounds,
		map[string]any{
			"tag":      "direct",
			"protocol": "freedom",
			"settings": map[string]any{"domainStrategy": "UseIP"},
		},
		map[string]any{
			"tag":      "block",
			"protocol": "blackhole",
		},
		map[string]any{
			"tag":      "dns-out",
			"protocol": "dns",
		},
	)

	rules := []any{
		// DNS hijack/route to dns-out for TUN DNS 172.19.0.2:53.
		map[string]any{
			"type":        "field",
			"inboundTag":  []string{"tun-in"},
			"ip":          []string{"172.19.0.2/32"},
			"port":        "53",
			"outboundTag": "dns-out",
		},
		// Loopback protection from tun-in MUST precede any bypassLan / direct rules.
		map[string]any{
			"type":        "field",
			"inboundTag":  []string{"tun-in"},
			"ip":          []string{"127.0.0.0/8", "::1/128"},
			"outboundTag": "block",
		},
		// Non-DNS TUN subnet traffic blocked.
		map[string]any{
			"type":        "field",
			"inboundTag":  []string{"tun-in"},
			"ip":          []string{"172.19.0.0/30", "fdfe:dcba:9876::/126"},
			"outboundTag": "block",
		},
	}

	if bypassLan {
		rules = append(rules, map[string]any{
			"type":        "field",
			"ip":          []string{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7", "fe80::/10"},
			"outboundTag": "direct",
		})
	}

	var routingObj map[string]any
	if len(proxyOutbounds) > 0 {
		hasPrefix := false
		for _, ob := range proxyOutbounds {
			if tag, _ := ob["tag"].(string); strings.HasPrefix(tag, yumi.NodeTagPrefix) {
				hasPrefix = true
				break
			}
		}
		if hasPrefix {
			rules = append(rules, map[string]any{
				"type":        "field",
				"network":     "tcp,udp",
				"balancerTag": yumi.BalancerTag,
			})
			routingObj = map[string]any{
				"domainStrategy": "IPIfNonMatch",
				"balancers": []any{
					map[string]any{
						"tag":      yumi.BalancerTag,
						"selector": []string{yumi.NodeTagPrefix},
					},
				},
				"rules": rules,
			}
		} else {
			routingObj = map[string]any{
				"domainStrategy": "IPIfNonMatch",
				"rules":          rules,
			}
		}
	} else {
		routingObj = map[string]any{
			"domainStrategy": "IPIfNonMatch",
			"rules":          rules,
		}
	}

	doc := map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"policy": map[string]any{
			"levels": map[string]any{
				"0": map[string]any{
					"connIdle":     1800,
					"uplinkOnly":   30,
					"downlinkOnly": 30,
				},
			},
			"system": map[string]any{
				"statsOutboundUplink":   true,
				"statsOutboundDownlink": true,
			},
		},
		"stats": map[string]any{},
		"dns": map[string]any{
			"servers":       []any{"1.1.1.1"},
			"queryStrategy": "UseIPv4",
		},
		"inbounds":  inbounds,
		"outbounds": outbounds,
		"routing":   routingObj,
	}

	raw, err := json.Marshal(doc)
	if err != nil {
		return "", err
	}
	return string(raw), nil
}

func startXrayCoreFromJSON(t *testing.T, configJSON string) *core.Instance {
	t.Helper()
	cfg, err := serial.LoadJSONConfig(strings.NewReader(configJSON))
	if err != nil {
		t.Fatalf("serial.LoadJSONConfig failed: %v", err)
	}
	inst, err := core.New(cfg)
	if err != nil {
		t.Fatalf("core.New failed: %v", err)
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		t.Fatalf("inst.Start failed: %v", err)
	}
	t.Cleanup(func() {
		_ = inst.Close()
	})
	return inst
}

func dialViaCoreHTTP(ctx context.Context, inst *core.Instance, tag string, targetURL string, timeout time.Duration) (*http.Response, error) {
	dialCtx := ctx
	if tag != "" {
		content := new(session.Content)
		content.SkipDNSResolve = true
		dialCtx = session.ContextWithContent(dialCtx, content)
		dialCtx = session.SetForcedOutboundTagToContext(dialCtx, tag)
	}

	client := &http.Client{
		Transport: &http.Transport{
			Proxy: func(*http.Request) (*url.URL, error) { return nil, nil },
			DialContext: func(_ context.Context, network, addr string) (net.Conn, error) {
				dest, err := v2net.ParseDestination(network + ":" + addr)
				if err != nil {
					return nil, err
				}
				return core.Dial(dialCtx, inst, dest)
			},
			DisableKeepAlives: true,
		},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		Timeout:       timeout,
	}
	defer client.CloseIdleConnections()

	req, err := http.NewRequestWithContext(dialCtx, http.MethodGet, targetURL, nil)
	if err != nil {
		return nil, err
	}
	return client.Do(req)
}

func probeTCPOpen(host string, port int, timeout time.Duration) bool {
	addr := net.JoinHostPort(host, fmt.Sprintf("%d", port))
	conn, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

func startLocalHTTP204Server(t *testing.T) (string, *atomic.Int64) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen http204: %v", err)
	}
	var hits atomic.Int64
	srv := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			hits.Add(1)
			w.Header().Set("X-Yumi-Probe", "ok")
			w.WriteHeader(http.StatusNoContent)
		}),
	}
	go func() {
		_ = srv.Serve(ln)
	}()
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	})
	return ln.Addr().String(), &hits
}

// startLocalSOCKS5Server runs a minimal RFC 1928 no-auth SOCKS5 CONNECT proxy on 127.0.0.1:0.
func startLocalSOCKS5Server(t *testing.T) (string, int, *atomic.Int64) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen socks5: %v", err)
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
			go handleSOCKS5Conn(c)
		}
	}()

	tcpAddr := ln.Addr().(*net.TCPAddr)
	return tcpAddr.IP.String(), tcpAddr.Port, &conns
}

func handleSOCKS5Conn(c net.Conn) {
	defer c.Close()
	buf := make([]byte, 260)
	if _, err := io.ReadFull(c, buf[:2]); err != nil {
		return
	}
	nMethods := int(buf[1])
	if _, err := io.ReadFull(c, buf[:nMethods]); err != nil {
		return
	}
	if _, err := c.Write([]byte{0x05, 0x00}); err != nil {
		return
	}
	if _, err := io.ReadFull(c, buf[:4]); err != nil {
		return
	}
	if buf[1] != 0x01 { // CONNECT only
		_, _ = c.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	var host string
	switch buf[3] {
	case 0x01:
		if _, err := io.ReadFull(c, buf[:4]); err != nil {
			return
		}
		host = net.IP(buf[:4]).String()
	case 0x03:
		if _, err := io.ReadFull(c, buf[:1]); err != nil {
			return
		}
		dlen := int(buf[0])
		if _, err := io.ReadFull(c, buf[:dlen]); err != nil {
			return
		}
		host = string(buf[:dlen])
	case 0x04:
		if _, err := io.ReadFull(c, buf[:16]); err != nil {
			return
		}
		host = net.IP(buf[:16]).String()
	default:
		return
	}
	if _, err := io.ReadFull(c, buf[:2]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(buf[:2])
	target, err := net.DialTimeout("tcp", net.JoinHostPort(host, fmt.Sprintf("%d", port)), 2*time.Second)
	if err != nil {
		_, _ = c.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer target.Close()
	_, _ = c.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(target, c)
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(c, target)
	}()
	wg.Wait()
}

// startLocalHTTPConnectServer runs a minimal HTTP CONNECT proxy on 127.0.0.1:0.
func startLocalHTTPConnectServer(t *testing.T) (string, int, *atomic.Int64) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen http proxy: %v", err)
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
			go handleHTTPConnectConn(c)
		}
	}()

	tcpAddr := ln.Addr().(*net.TCPAddr)
	return tcpAddr.IP.String(), tcpAddr.Port, &conns
}

func handleHTTPConnectConn(c net.Conn) {
	defer c.Close()
	br := bufio.NewReader(c)
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	if req.Method != http.MethodConnect {
		_, _ = c.Write([]byte("HTTP/1.1 405 Method Not Allowed\r\n\r\n"))
		return
	}
	target, err := net.DialTimeout("tcp", req.Host, 2*time.Second)
	if err != nil {
		_, _ = c.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}
	defer target.Close()
	_, _ = c.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(target, br)
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(c, target)
	}()
	wg.Wait()
}

// -----------------------------------------------------------------------------
// A7: Zero Loopback Proxies/APIs & In-Process core.Dial
// -----------------------------------------------------------------------------

// T1-A7-02: Running core opens zero SOCKS/HTTP/Xray-gRPC/Clash-REST listeners on 127.0.0.1 / ::1.
func TestE2E_T1_A7_02_ZeroLoopbackProxiesAndAPIs(t *testing.T) {
	ports := []int{1080, 10808, 10809, 7890, 8080, 9090, 10085, 19090}
	hosts := []string{"127.0.0.1", "::1"}

	// Snapshot ports that were NOT already bound by unrelated host services.
	type hostPort struct {
		host string
		port int
	}
	freeBefore := make([]hostPort, 0, len(ports)*len(hosts))
	for _, h := range hosts {
		for _, p := range ports {
			if !probeTCPOpen(h, p, 80*time.Millisecond) {
				freeBefore = append(freeBefore, hostPort{h, p})
			}
		}
	}
	if len(freeBefore) == 0 {
		t.Fatal("all candidate probe ports were already open on host before test")
	}

	cfgJSON, err := buildProductionShapedConfig(false, []map[string]any{
		{"tag": yumi.NodeTagPrefix + "direct", "protocol": "freedom"},
	}, true)
	if err != nil {
		t.Fatalf("buildProductionShapedConfig: %v", err)
	}
	inst := startXrayCoreFromJSON(t, cfgJSON)

	// Verify Xray inbound.Manager registers zero loopback/API/proxy inbound handlers.
	im, ok := inst.GetFeature(inbound.ManagerType()).(inbound.Manager)
	if !ok || im == nil {
		t.Fatal("expected inbound.Manager feature")
	}
	if handlers := im.ListHandlers(context.Background()); len(handlers) != 0 {
		var tags []string
		for _, h := range handlers {
			tags = append(tags, h.Tag())
		}
		t.Fatalf("expected 0 loopback/proxy inbound handlers, got %v", tags)
	}

	for _, hp := range freeBefore {
		if probeTCPOpen(hp.host, hp.port, 100*time.Millisecond) {
			t.Fatalf("security leak: port %s:%d became open after starting xray-core", hp.host, hp.port)
		}
	}
}

// T2-A7-01: Extended port scan across common proxy/API/Clash/Xray ports during active core.Dial.
func TestE2E_T2_A7_01_ExtendedLoopbackScanDuringActiveCoreDial(t *testing.T) {
	ports := []int{1080, 1081, 7890, 7891, 8080, 8118, 8888, 9090, 9091, 10085, 10086, 10808, 10809, 19090, 20170, 20171}
	hosts := []string{"127.0.0.1", "::1"}

	type hostPort struct {
		host string
		port int
	}
	freeBefore := make([]hostPort, 0, len(ports)*len(hosts))
	for _, h := range hosts {
		for _, p := range ports {
			if !probeTCPOpen(h, p, 60*time.Millisecond) {
				freeBefore = append(freeBefore, hostPort{h, p})
			}
		}
	}

	targetAddr, hits := startLocalHTTP204Server(t)
	cfgJSON, err := buildProductionShapedConfig(false, []map[string]any{
		{"tag": yumi.NodeTagPrefix + "a", "protocol": "freedom"},
	}, false)
	if err != nil {
		t.Fatalf("buildProductionShapedConfig: %v", err)
	}
	inst := startXrayCoreFromJSON(t, cfgJSON)

	// Drive active in-process core.Dial traffic concurrently with port scanning.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := dialViaCoreHTTP(ctx, inst, yumi.NodeTagPrefix+"a", "http://"+targetAddr+"/generate_204", 2*time.Second)
	if err != nil {
		t.Fatalf("dialViaCoreHTTP failed: %v", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent || hits.Load() != 1 {
		t.Fatalf("unexpected probe response: code=%d hits=%d", resp.StatusCode, hits.Load())
	}

	for _, hp := range freeBefore {
		if probeTCPOpen(hp.host, hp.port, 60*time.Millisecond) {
			t.Fatalf("port %s:%d opened during active core.Dial", hp.host, hp.port)
		}
	}
}

// T1-A7-03: In-process core.Dial HTTP 204 helper succeeds through forced outbound without loopback port.
func TestE2E_T1_A7_03_InProcessCoreDialHTTP204ThroughOutbound(t *testing.T) {
	targetAddr, hits := startLocalHTTP204Server(t)
	cfgJSON, err := buildProductionShapedConfig(false, []map[string]any{
		{"tag": yumi.NodeTagPrefix + "allow", "protocol": "freedom"},
		{"tag": yumi.NodeTagPrefix + "deny", "protocol": "blackhole"},
	}, false)
	if err != nil {
		t.Fatalf("buildProductionShapedConfig: %v", err)
	}
	inst := startXrayCoreFromJSON(t, cfgJSON)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// Forced outbound "proxy-allow" reaches local HTTP 204 endpoint.
	resp, err := dialViaCoreHTTP(ctx, inst, yumi.NodeTagPrefix+"allow", "http://"+targetAddr+"/generate_204", 2*time.Second)
	if err != nil {
		t.Fatalf("core.Dial through proxy-allow failed: %v", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("expected HTTP 204, got %d", resp.StatusCode)
	}
	if got := resp.Header.Get("X-Yumi-Probe"); got != "ok" {
		t.Fatalf("expected X-Yumi-Probe=ok, got %q", got)
	}
	if hits.Load() != 1 {
		t.Fatalf("expected 1 hit on local server, got %d", hits.Load())
	}

	// Forced outbound "proxy-deny" (blackhole) must fail and NOT hit the server.
	resp2, err := dialViaCoreHTTP(ctx, inst, yumi.NodeTagPrefix+"deny", "http://"+targetAddr+"/generate_204", 500*time.Millisecond)
	if err == nil {
		_ = resp2.Body.Close()
		t.Fatalf("expected blackhole forced outbound to fail, got status %d", resp2.StatusCode)
	}
	if hits.Load() != 1 {
		t.Fatalf("blackhole outbound unexpectedly reached local server: hits=%d", hits.Load())
	}
}

// T2-A7-02: In-process core.Dial handles timeout and unreachable targets cleanly.
func TestE2E_T2_A7_02_InProcessCoreDialTimeoutAndUnreachable(t *testing.T) {
	// Blackhole TCP listener: accepts and holds without writing HTTP response.
	hangLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen hang server: %v", err)
	}
	var accepted atomic.Int64
	done := make(chan struct{})
	go func() {
		for {
			c, err := hangLn.Accept()
			if err != nil {
				return
			}
			accepted.Add(1)
			go func(conn net.Conn) {
				<-done
				_ = conn.Close()
			}(c)
		}
	}()
	t.Cleanup(func() {
		close(done)
		_ = hangLn.Close()
	})

	// Immediately closed listener for unreachable port (ECONNREFUSED).
	closedLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen closed probe: %v", err)
	}
	closedAddr := closedLn.Addr().String()
	_ = closedLn.Close()

	cfgJSON, err := buildProductionShapedConfig(false, []map[string]any{
		{"tag": yumi.NodeTagPrefix + "direct", "protocol": "freedom"},
	}, false)
	if err != nil {
		t.Fatalf("buildProductionShapedConfig: %v", err)
	}
	inst := startXrayCoreFromJSON(t, cfgJSON)

	// 1. Hanging server must time out within bounded duration (< 1s for 120ms timeout).
	start := time.Now()
	ctxTimeout, cancelTimeout := context.WithTimeout(context.Background(), 120*time.Millisecond)
	defer cancelTimeout()
	resp, err := dialViaCoreHTTP(ctxTimeout, inst, yumi.NodeTagPrefix+"direct", "http://"+hangLn.Addr().String()+"/slow", 120*time.Millisecond)
	elapsed := time.Since(start)
	if err == nil {
		_ = resp.Body.Close()
		t.Fatal("expected timeout error against hanging server")
	}
	if elapsed > 1500*time.Millisecond {
		t.Fatalf("timeout took too long: %v", elapsed)
	}

	// 2. Unreachable closed port must return error promptly.
	ctxClosed, cancelClosed := context.WithTimeout(context.Background(), time.Second)
	defer cancelClosed()
	resp2, err := dialViaCoreHTTP(ctxClosed, inst, yumi.NodeTagPrefix+"direct", "http://"+closedAddr+"/closed", time.Second)
	if err == nil {
		_ = resp2.Body.Close()
		t.Fatal("expected error dialing closed port")
	}
}

// T2-A7-03: Stopped core refuses MeasureOutbounds / SelectOutbound / invalid Start cleanly.
func TestE2E_T2_A7_03_StoppedCoreRefusesOperations(t *testing.T) {
	if err := yumi.Stop(); err != nil {
		t.Fatalf("initial Stop() failed: %v", err)
	}
	if yumi.IsRunning() {
		t.Fatal("expected IsRunning() == false after Stop()")
	}
	if got := yumi.MeasureOutbounds("proxy-a proxy-b", "http://127.0.0.1:80/generate_204", 200); got != "{}" {
		t.Fatalf("expected \"{}\" when core stopped, got %q", got)
	}
	if err := yumi.SelectOutbound("proxy-a"); err == nil {
		t.Fatal("expected error calling SelectOutbound when core is stopped")
	}
	if got := yumi.ActiveOutbound(); got != "" {
		t.Fatalf("expected empty ActiveOutbound when stopped, got %q", got)
	}
	if err := yumi.Stop(); err != nil {
		t.Fatalf("duplicate Stop() should be idempotent nil, got %v", err)
	}
	if err := yumi.Start("{}", 0, "", nil); err == nil {
		t.Fatal("expected Start with tunFd=0 to fail")
	}
	if v := yumi.Version(); strings.TrimSpace(v) == "" {
		t.Fatal("expected non-empty yumi.Version()")
	}
}

// T3-13: In-process core.Dial across freedom, SOCKS5, and HTTP outbounds without loopback ports.
func TestE2E_T3_13_InProcessCoreDialAcrossMultipleOutboundsNoLoopback(t *testing.T) {
	targetAddr, targetHits := startLocalHTTP204Server(t)
	socksHost, socksPort, socksConns := startLocalSOCKS5Server(t)
	httpHost, httpPort, httpConns := startLocalHTTPConnectServer(t)

	cfgJSON, err := buildProductionShapedConfig(false, []map[string]any{
		{
			"tag":      yumi.NodeTagPrefix + "freedom",
			"protocol": "freedom",
		},
		{
			"tag":      yumi.NodeTagPrefix + "socks",
			"protocol": "socks",
			"settings": map[string]any{
				"servers": []any{
					map[string]any{"address": socksHost, "port": socksPort},
				},
			},
		},
		{
			"tag":      yumi.NodeTagPrefix + "http",
			"protocol": "http",
			"settings": map[string]any{
				"servers": []any{
					map[string]any{"address": httpHost, "port": httpPort},
				},
			},
		},
	}, false)
	if err != nil {
		t.Fatalf("buildProductionShapedConfig: %v", err)
	}
	inst := startXrayCoreFromJSON(t, cfgJSON)

	// Confirm zero loopback inbound handlers exist on Xray.
	im := inst.GetFeature(inbound.ManagerType()).(inbound.Manager)
	if len(im.ListHandlers(context.Background())) != 0 {
		t.Fatalf("expected 0 Xray inbound handlers")
	}

	tags := []string{
		yumi.NodeTagPrefix + "freedom",
		yumi.NodeTagPrefix + "socks",
		yumi.NodeTagPrefix + "http",
	}
	for _, tag := range tags {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		resp, err := dialViaCoreHTTP(ctx, inst, tag, "http://"+targetAddr+"/generate_204", 3*time.Second)
		cancel()
		if err != nil {
			t.Fatalf("core.Dial via %s failed: %v", tag, err)
		}
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusNoContent {
			t.Fatalf("tag %s expected 204, got %d", tag, resp.StatusCode)
		}
	}

	if targetHits.Load() != 3 {
		t.Fatalf("expected 3 hits on local HTTP 204 server, got %d", targetHits.Load())
	}
	if socksConns.Load() < 1 {
		t.Fatalf("expected SOCKS5 outbound to dial local SOCKS5 server, got %d conns", socksConns.Load())
	}
	if httpConns.Load() < 1 {
		t.Fatalf("expected HTTP outbound to dial local HTTP CONNECT server, got %d conns", httpConns.Load())
	}
}

// -----------------------------------------------------------------------------
// A8: Uniform Protocol, Transport & Security Coverage + Loopback Routing
// -----------------------------------------------------------------------------

// T1-A8-01: All 9 supported outbound protocols parse via serial.LoadJSONConfig & core.New with single tun-in.
func TestE2E_T1_A8_01_AllSupportedProtocolsLoadValidXrayConfig(t *testing.T) {
	protocols := []struct {
		name     string
		outbound map[string]any
	}{
		{
			name: "vless",
			outbound: map[string]any{
				"tag":      yumi.NodeTagPrefix + "vless",
				"protocol": "vless",
				"settings": map[string]any{
					"vnext": []any{
						map[string]any{
							"address": "203.0.113.10",
							"port":    443,
							"users": []any{
								map[string]any{
									"id":         "11111111-2222-3333-4444-555555555555",
									"flow":       "xtls-rprx-vision",
									"encryption": "none",
								},
							},
						},
					},
				},
			},
		},
		{
			name: "vmess",
			outbound: map[string]any{
				"tag":      yumi.NodeTagPrefix + "vmess",
				"protocol": "vmess",
				"settings": map[string]any{
					"vnext": []any{
						map[string]any{
							"address": "203.0.113.11",
							"port":    443,
							"users": []any{
								map[string]any{
									"id":       "11111111-2222-3333-4444-555555555555",
									"security": "auto",
								},
							},
						},
					},
				},
			},
		},
		{
			name: "trojan",
			outbound: map[string]any{
				"tag":      yumi.NodeTagPrefix + "trojan",
				"protocol": "trojan",
				"settings": map[string]any{
					"servers": []any{
						map[string]any{
							"address":  "203.0.113.12",
							"port":     443,
							"password": "test-trojan-password",
						},
					},
				},
			},
		},
		{
			name: "shadowsocks",
			outbound: map[string]any{
				"tag":      yumi.NodeTagPrefix + "shadowsocks",
				"protocol": "shadowsocks",
				"settings": map[string]any{
					"servers": []any{
						map[string]any{
							"address":  "203.0.113.13",
							"port":     8388,
							"method":   "aes-256-gcm",
							"password": "test-ss-password",
						},
					},
				},
			},
		},
		{
			name: "hysteria2",
			outbound: map[string]any{
				"tag":      yumi.NodeTagPrefix + "hysteria2",
				"protocol": "hysteria",
				"settings": map[string]any{
					"version": 2,
					"address": "203.0.113.14",
					"port":    443,
				},
				"streamSettings": map[string]any{
					"network":  "hysteria",
					"security": "tls",
					"tlsSettings": map[string]any{
						"serverName": "hy2.example.com",
					},
					"hysteriaSettings": map[string]any{
						"version": 2,
						"auth":    "hy2-secret",
					},
				},
			},
		},
		{
			name: "wireguard",
			outbound: map[string]any{
				"tag":      yumi.NodeTagPrefix + "wireguard",
				"protocol": "wireguard",
				"settings": map[string]any{
					"secretKey": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
					"address":   []string{"10.7.0.2/32", "fd00::2/128"},
					"mtu":       1280,
					"reserved":  []int{1, 2, 3},
					"peers": []any{
						map[string]any{
							"publicKey":  "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
							"endpoint":   "203.0.113.15:51820",
							"allowedIPs": []string{"0.0.0.0/0", "::/0"},
						},
					},
				},
			},
		},
		{
			name: "http",
			outbound: map[string]any{
				"tag":      yumi.NodeTagPrefix + "http",
				"protocol": "http",
				"settings": map[string]any{
					"servers": []any{
						map[string]any{"address": "203.0.113.16", "port": 3128},
					},
				},
			},
		},
		{
			name: "socks",
			outbound: map[string]any{
				"tag":      yumi.NodeTagPrefix + "socks",
				"protocol": "socks",
				"settings": map[string]any{
					"servers": []any{
						map[string]any{"address": "203.0.113.17", "port": 1080},
					},
				},
			},
		},
		{
			name: "freedom",
			outbound: map[string]any{
				"tag":      yumi.NodeTagPrefix + "freedom",
				"protocol": "freedom",
			},
		},
	}

	for _, tc := range protocols {
		t.Run(tc.name, func(t *testing.T) {
			cfgJSON, err := buildProductionShapedConfig(true, []map[string]any{tc.outbound}, true)
			if err != nil {
				t.Fatalf("buildProductionShapedConfig(%s): %v", tc.name, err)
			}
			cfg, err := serial.LoadJSONConfig(strings.NewReader(cfgJSON))
			if err != nil {
				t.Fatalf("serial.LoadJSONConfig(%s) failed: %v", tc.name, err)
			}
			inst, err := core.New(cfg)
			if err != nil {
				t.Fatalf("core.New(%s) failed: %v", tc.name, err)
			}
			defer inst.Close()

			im := inst.GetFeature(inbound.ManagerType()).(inbound.Manager)
			handlers := im.ListHandlers(context.Background())
			if len(handlers) != 1 || handlers[0].Tag() != "tun-in" {
				t.Fatalf("%s: expected single inbound [tun-in], got %d handlers", tc.name, len(handlers))
			}

			om := inst.GetFeature(outbound.ManagerType()).(outbound.Manager)
			expectedTag := tc.outbound["tag"].(string)
			if om.GetHandler(expectedTag) == nil {
				t.Fatalf("%s: missing expected outbound %q", tc.name, expectedTag)
			}
			for _, sysTag := range []string{"direct", "block", "dns-out"} {
				if om.GetHandler(sysTag) == nil {
					t.Fatalf("%s: missing required outbound %q", tc.name, sysTag)
				}
			}
		})
	}
}

// T1-A8-02: All 6 transports (raw, ws, grpc, httpupgrade, xhttp, hysteria) x security (none, tls, reality)
// parse via serial.LoadJSONConfig and instantiate via core.New.
func TestE2E_T1_A8_02_AllTransportsAndSecurityModesLoadValidXrayConfig(t *testing.T) {
	// Valid 32-byte x25519 base64url public key for REALITY config parsing.
	const testRealityPubKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

	transports := []struct {
		name        string
		network     string
		extraKey    string
		extraVal    map[string]any
		tlsRequired bool
	}{
		{name: "raw"},
		{
			name:     "ws",
			network:  "ws",
			extraKey: "wsSettings",
			extraVal: map[string]any{"path": "/ws", "host": "cdn.example.com"},
		},
		{
			name:     "grpc",
			network:  "grpc",
			extraKey: "grpcSettings",
			extraVal: map[string]any{"serviceName": "yumiGrpc", "permit_without_stream": true},
		},
		{
			name:     "httpupgrade",
			network:  "httpupgrade",
			extraKey: "httpupgradeSettings",
			extraVal: map[string]any{"path": "/upgrade", "host": "cdn.example.com"},
		},
		{
			name:     "xhttp",
			network:  "xhttp",
			extraKey: "xhttpSettings",
			extraVal: map[string]any{"path": "/xhttp", "host": "cdn.example.com"},
		},
	}

	securities := []string{"none", "tls", "reality"}

	for _, tr := range transports {
		for _, sec := range securities {
			// Xray-core only allows REALITY on raw, grpc, and xhttp transports.
			if sec == "reality" && (tr.name == "ws" || tr.name == "httpupgrade") {
				continue
			}
			name := tr.name + "_" + sec
			t.Run(name, func(t *testing.T) {
				stream := map[string]any{
					"security": sec,
				}
				if tr.network != "" {
					stream["network"] = tr.network
				}
				if tr.extraKey != "" {
					stream[tr.extraKey] = tr.extraVal
				}
				switch sec {
				case "tls":
					stream["tlsSettings"] = map[string]any{
						"serverName":  "cdn.example.com",
						"fingerprint": "chrome",
					}
				case "reality":
					stream["realitySettings"] = map[string]any{
						"serverName":  "www.cloudflare.com",
						"publicKey":   testRealityPubKey,
						"shortId":     "abcd1234",
						"fingerprint": "chrome",
					}
				}

				ob := map[string]any{
					"tag":      yumi.NodeTagPrefix + "test",
					"protocol": "vless",
					"settings": map[string]any{
						"vnext": []any{
							map[string]any{
								"address": "203.0.113.20",
								"port":    443,
								"users": []any{
									map[string]any{
										"id":         "11111111-2222-3333-4444-555555555555",
										"encryption": "none",
									},
								},
							},
						},
					},
					"streamSettings": stream,
				}

				cfgJSON, err := buildProductionShapedConfig(true, []map[string]any{ob}, true)
				if err != nil {
					t.Fatalf("buildProductionShapedConfig(%s): %v", name, err)
				}
				cfg, err := serial.LoadJSONConfig(strings.NewReader(cfgJSON))
				if err != nil {
					t.Fatalf("serial.LoadJSONConfig(%s) failed: %v", name, err)
				}
				inst, err := core.New(cfg)
				if err != nil {
					t.Fatalf("core.New(%s) failed: %v", name, err)
				}
				_ = inst.Close()
			})
		}
	}

	// Also verify hysteria transport + tls + finalmask (6th transport).
	t.Run("hysteria_tls_finalmask", func(t *testing.T) {
		ob := map[string]any{
			"tag":      yumi.NodeTagPrefix + "hy2",
			"protocol": "hysteria",
			"settings": map[string]any{
				"version": 2,
				"address": "203.0.113.21",
				"port":    443,
			},
			"streamSettings": map[string]any{
				"network":  "hysteria",
				"security": "tls",
				"tlsSettings": map[string]any{
					"serverName": "hy2.example.com",
				},
				"hysteriaSettings": map[string]any{
					"version": 2,
					"auth":    "secret-auth",
				},
				"finalmask": map[string]any{
					"udp": []any{
						map[string]any{
							"type":     "salamander",
							"settings": map[string]any{"password": "obfs-password"},
						},
					},
				},
			},
		}
		cfgJSON, err := buildProductionShapedConfig(true, []map[string]any{ob}, true)
		if err != nil {
			t.Fatalf("buildProductionShapedConfig(hysteria): %v", err)
		}
		cfg, err := serial.LoadJSONConfig(strings.NewReader(cfgJSON))
		if err != nil {
			t.Fatalf("serial.LoadJSONConfig(hysteria) failed: %v", err)
		}
		inst, err := core.New(cfg)
		if err != nil {
			t.Fatalf("core.New(hysteria) failed: %v", err)
		}
		_ = inst.Close()
	})
}

// A6 / A8: Real xray-core Router blocks 127.0.0.0/8 & ::1/128 from tun-in ahead of bypassLan direct rules.
func TestE2E_A6_A8_LoopbackBlockedAheadOfDirectLanBypassInRouter(t *testing.T) {
	cfgJSON, err := buildProductionShapedConfig(true, []map[string]any{
		{"tag": yumi.NodeTagPrefix + "a", "protocol": "freedom"},
	}, true)
	if err != nil {
		t.Fatalf("buildProductionShapedConfig: %v", err)
	}
	cfg, err := serial.LoadJSONConfig(strings.NewReader(cfgJSON))
	if err != nil {
		t.Fatalf("serial.LoadJSONConfig: %v", err)
	}
	inst, err := core.New(cfg)
	if err != nil {
		t.Fatalf("core.New: %v", err)
	}
	defer inst.Close()

	router, ok := inst.GetFeature(routing.RouterType()).(routing.Router)
	if !ok || router == nil {
		t.Fatal("expected routing.Router feature")
	}

	tests := []struct {
		name        string
		dstIP       net.IP
		dstPort     v2net.Port
		expectedOut string
	}{
		{name: "ipv4_loopback_127_0_0_1", dstIP: net.ParseIP("127.0.0.1").To4(), dstPort: 8080, expectedOut: "block"},
		{name: "ipv4_loopback_127_255_255_254", dstIP: net.ParseIP("127.255.255.254").To4(), dstPort: 10808, expectedOut: "block"},
		{name: "ipv6_loopback_colon_1", dstIP: net.ParseIP("::1"), dstPort: 9090, expectedOut: "block"},
		{name: "tun_dns_172_19_0_2_53", dstIP: net.ParseIP("172.19.0.2").To4(), dstPort: 53, expectedOut: "dns-out"},
		{name: "tun_subnet_non_dns_blocked", dstIP: net.ParseIP("172.19.0.3").To4(), dstPort: 80, expectedOut: "block"},
		{name: "lan_bypass_192_168_1_1", dstIP: net.ParseIP("192.168.1.1").To4(), dstPort: 80, expectedOut: "direct"},
		{name: "public_ip_routed_to_proxy", dstIP: net.ParseIP("93.184.216.34").To4(), dstPort: 443, expectedOut: yumi.NodeTagPrefix + "a"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			rctx := &routing_session.Context{
				Inbound: &session.Inbound{
					Tag:    "tun-in",
					Source: v2net.TCPDestination(v2net.IPAddress(net.ParseIP("172.19.0.1").To4()), 50000),
				},
				Outbound: &session.Outbound{
					Target: v2net.TCPDestination(v2net.IPAddress(tc.dstIP), tc.dstPort),
				},
			}
			route, err := router.PickRoute(rctx)
			if err != nil {
				t.Fatalf("PickRoute(%s) failed: %v", tc.name, err)
			}
			gotTag := route.GetOutboundTag()
			// For balancer-selected rules, resolve concrete group/tag check.
			if gotTag == "" && len(route.GetOutboundGroupTags()) > 0 {
				gotTag = route.GetOutboundGroupTags()[0]
			}
			if gotTag != tc.expectedOut {
				// Also allow balancer tag "proxy" if PickRoute returned group before resolution.
				if !(tc.expectedOut == yumi.NodeTagPrefix+"a" && gotTag == yumi.BalancerTag) {
					t.Fatalf("%s: expected outbound %q, got %q (groups=%v)", tc.name, tc.expectedOut, gotTag, route.GetOutboundGroupTags())
				}
			}
		})
	}
}

// T2-A8-05: SelectOutbound rejects unknown tag, and balancer overrider pins valid tags cleanly.
func TestE2E_T2_A8_05_SelectOutboundRejectsUnknownTag(t *testing.T) {
	// Stopped core check via exported yumi.SelectOutbound.
	_ = yumi.Stop()
	if err := yumi.SelectOutbound("proxy-missing"); err == nil {
		t.Fatal("expected yumi.SelectOutbound(\"proxy-missing\") to return an error")
	}

	// Verify real router BalancerOverrider behavior on a running core.Instance with proxy-a and proxy-b.
	cfgJSON, err := buildProductionShapedConfig(false, []map[string]any{
		{"tag": yumi.NodeTagPrefix + "a", "protocol": "freedom"},
		{"tag": yumi.NodeTagPrefix + "b", "protocol": "blackhole"},
	}, false)
	if err != nil {
		t.Fatalf("buildProductionShapedConfig: %v", err)
	}
	inst := startXrayCoreFromJSON(t, cfgJSON)

	router, ok := inst.GetFeature(routing.RouterType()).(routing.Router)
	if !ok || router == nil {
		t.Fatal("expected routing.Router")
	}
	overrider, ok := router.(routing.BalancerOverrider)
	if !ok {
		t.Fatal("expected router to implement routing.BalancerOverrider")
	}
	if err := overrider.SetOverrideTarget(yumi.BalancerTag, yumi.NodeTagPrefix+"a"); err != nil {
		t.Fatalf("SetOverrideTarget(proxy-a) failed: %v", err)
	}
	got, err := overrider.GetOverrideTarget(yumi.BalancerTag)
	if err != nil {
		t.Fatalf("GetOverrideTarget failed: %v", err)
	}
	if got != yumi.NodeTagPrefix+"a" {
		t.Fatalf("expected override target proxy-a, got %q", got)
	}

	// Verify outbound.Manager distinguishes configured tags from missing tags.
	om := inst.GetFeature(outbound.ManagerType()).(outbound.Manager)
	if om.GetHandler(yumi.NodeTagPrefix+"a") == nil || om.GetHandler(yumi.NodeTagPrefix+"b") == nil {
		t.Fatal("expected proxy-a and proxy-b in outbound.Manager")
	}
	if om.GetHandler("proxy-missing") != nil {
		t.Fatal("expected proxy-missing to be absent from outbound.Manager")
	}

	// Ensure errors package is used if needed for wrapped checks.
	if errors.Is(context.Canceled, context.DeadlineExceeded) {
		t.Fatal("unexpected error identity")
	}
}

func TestE2E_FragmentDialerProxyAndStatsAndDomainNoDeadlock(t *testing.T) {
	targetAddr, _ := startLocalHTTP204Server(t)
	_, socksPort, _ := startLocalSOCKS5Server(t)

	// Dual-stack wildcard listener filtered strictly to loopback so addressing the proxy as
	// "localhost" works whether Windows/Linux resolves "localhost" to 127.0.0.1 or ::1 first.
	proxyLn, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatalf("listen fragment counting proxy: %v", err)
	}
	t.Cleanup(func() { _ = proxyLn.Close() })
	proxyPort := proxyLn.Addr().(*net.TCPAddr).Port

	var totalClientReads atomic.Int64
	var smallClientReads atomic.Int64
	go func() {
		for {
			client, err := proxyLn.Accept()
			if err != nil {
				return
			}
			if addr, ok := client.RemoteAddr().(*net.TCPAddr); !ok || !addr.IP.IsLoopback() {
				_ = client.Close()
				continue
			}
			go func(c net.Conn) {
				defer c.Close()
				upstream, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort))
				if err != nil {
					return
				}
				defer upstream.Close()
				var wg sync.WaitGroup
				wg.Add(1)
				go func() {
					defer wg.Done()
					_, _ = io.Copy(c, upstream)
				}()
				buf := make([]byte, 4096)
				for {
					n, readErr := c.Read(buf)
					if n > 0 {
						totalClientReads.Add(1)
						if n <= 3 {
							smallClientReads.Add(1)
						}
						if _, writeErr := upstream.Write(buf[:n]); writeErr != nil {
							break
						}
					}
					if readErr != nil {
						break
					}
				}
				_ = upstream.Close()
				wg.Wait()
			}(client)
		}
	}()

	cfgJSON, err := buildProductionShapedConfig(false, []map[string]any{
		{
			"tag":      yumi.NodeTagPrefix + "frag",
			"protocol": "socks",
			"settings": map[string]any{
				"servers": []map[string]any{
					{"address": "localhost", "port": proxyPort},
				},
			},
			"streamSettings": map[string]any{
				"sockopt": map[string]any{
					"dialerProxy": "fragment",
				},
			},
		},
		{
			"tag":      "fragment",
			"protocol": "freedom",
			"settings": map[string]any{
				"domainStrategy": "AsIs",
				"fragment": map[string]any{
					"packets":  "1-3",
					"length":   "1-3",
					"interval": "1-2",
				},
			},
		},
	}, false)
	if err != nil {
		t.Fatalf("buildProductionShapedConfig: %v", err)
	}

	inst := startXrayCoreFromJSON(t, cfgJSON)
	resp, err := dialViaCoreHTTP(
		context.Background(),
		inst,
		yumi.NodeTagPrefix+"frag",
		"http://"+targetAddr+"/generate_204",
		5*time.Second,
	)
	if err != nil {
		t.Fatalf("dialViaCoreHTTP through fragmented domain proxy failed: %v", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("expected 204 No Content, got %d", resp.StatusCode)
	}

	if smallClientReads.Load() < 2 {
		t.Fatalf(
			"expected fragmented <=3B wire reads on SOCKS5 hop, got small=%d total=%d",
			smallClientReads.Load(),
			totalClientReads.Load(),
		)
	}

	sm, ok := inst.GetFeature(stats.ManagerType()).(stats.Manager)
	if !ok || sm == nil {
		t.Fatal("expected stats.Manager feature")
	}
	up := sm.GetCounter("outbound>>>" + yumi.NodeTagPrefix + "frag>>>traffic>>>uplink")
	down := sm.GetCounter("outbound>>>" + yumi.NodeTagPrefix + "frag>>>traffic>>>downlink")
	if up == nil || up.Value() <= 0 {
		t.Fatalf("expected positive uplink counter on proxy-frag, got %v", up)
	}
	if down == nil || down.Value() <= 0 {
		t.Fatalf("expected positive downlink counter on proxy-frag, got %v", down)
	}
}

func TestE2E_AllTlsFingerprintsAndTlsHelloFragmentAndXhttpExtraParseInRealXray(t *testing.T) {
	fingerprints := []string{
		"chrome",
		"firefox",
		"safari",
		"ios",
		"android",
		"edge",
		"qq",
		"360",
		"random",
		"randomized",
		"hellochrome_106_shuffle",
	}

	realityPubKey := base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{5}, 32))
	for _, fp := range fingerprints {
		fp := fp
		t.Run("fp_"+fp, func(t *testing.T) {
			cfgJSON, err := buildProductionShapedConfig(false, []map[string]any{
				{
					"tag":      yumi.NodeTagPrefix + "tls",
					"protocol": "vless",
					"settings": map[string]any{
						"vnext": []map[string]any{{
							"address": "203.0.113.10",
							"port":    443,
							"users":   []map[string]any{{"id": "11111111-1111-1111-1111-111111111111", "encryption": "none"}},
						}},
					},
					"streamSettings": map[string]any{
						"network":  "tcp",
						"security": "tls",
						"tlsSettings": map[string]any{
							"serverName":  "example.com",
							"fingerprint": fp,
						},
					},
				},
				{
					"tag":      yumi.NodeTagPrefix + "rel",
					"protocol": "vless",
					"settings": map[string]any{
						"vnext": []map[string]any{{
							"address": "203.0.113.11",
							"port":    443,
							"users":   []map[string]any{{"id": "22222222-2222-2222-2222-222222222222", "encryption": "none"}},
						}},
					},
					"streamSettings": map[string]any{
						"network":  "tcp",
						"security": "reality",
						"realitySettings": map[string]any{
							"serverName":  "www.cloudflare.com",
							"publicKey":   realityPubKey,
							"shortId":     "abcd1234",
							"fingerprint": fp,
						},
					},
				},
			}, false)
			if err != nil {
				t.Fatalf("buildProductionShapedConfig(%s): %v", fp, err)
			}
			cfg, err := serial.LoadJSONConfig(strings.NewReader(cfgJSON))
			if err != nil {
				t.Fatalf("serial.LoadJSONConfig(%s): %v", fp, err)
			}
			inst, err := core.New(cfg)
			if err != nil {
				t.Fatalf("core.New(%s): %v", fp, err)
			}
			_ = inst.Close()
		})
	}

	pqEnc := "mlkem768x25519plus.native.1rtt." + base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{7}, 32))
	cfgJSON, err := buildProductionShapedConfig(false, []map[string]any{
		{
			"tag":      "fragment",
			"protocol": "freedom",
			"settings": map[string]any{
				"domainStrategy": "AsIs",
				"fragment": map[string]any{
					"packets":  "tlshello",
					"length":   "10-20",
					"interval": "5-10",
					"maxSplit": "8",
				},
			},
		},
		{
			"tag":      yumi.NodeTagPrefix + "xhttp",
			"protocol": "vless",
			"settings": map[string]any{
				"vnext": []map[string]any{{
					"address": "203.0.113.20",
					"port":    443,
					"users":   []map[string]any{{"id": "33333333-3333-3333-3333-333333333333", "encryption": pqEnc}},
				}},
			},
			"streamSettings": map[string]any{
				"network":  "xhttp",
				"security": "tls",
				"tlsSettings": map[string]any{
					"serverName":  "xh.example.com",
					"fingerprint": "firefox",
				},
				"xhttpSettings": map[string]any{
					"host": "xh.example.com",
					"path": "/up",
					"extra": map[string]any{
						"xPaddingBytes": "100-200",
						"downloadSettings": map[string]any{
							"address":  "dl.example.com",
							"port":     443,
							"network":  "xhttp",
							"security": "tls",
							"tlsSettings": map[string]any{
								"serverName":  "dl.example.com",
								"fingerprint": "chrome",
							},
							"xhttpSettings": map[string]any{
								"host": "dl.example.com",
								"path": "/dl",
								"mode": "stream-one",
							},
						},
					},
				},
				"sockopt": map[string]any{
					"dialerProxy": "fragment",
				},
			},
		},
		{
			"tag":      yumi.NodeTagPrefix + "grpc",
			"protocol": "vless",
			"settings": map[string]any{
				"vnext": []map[string]any{{
					"address": "203.0.113.21",
					"port":    443,
					"users":   []map[string]any{{"id": "44444444-4444-4444-4444-444444444444", "encryption": "none"}},
				}},
			},
			"streamSettings": map[string]any{
				"network":  "grpc",
				"security": "tls",
				"tlsSettings": map[string]any{
					"serverName":  "gr.example.com",
					"fingerprint": "edge",
				},
				"grpcSettings": map[string]any{
					"serviceName": "tun",
					"authority":   "grpc.example.com",
					"multiMode":   true,
				},
			},
		},
	}, false)
	if err != nil {
		t.Fatalf("buildProductionShapedConfig(anti-dpi matrix): %v", err)
	}
	cfg, err := serial.LoadJSONConfig(strings.NewReader(cfgJSON))
	if err != nil {
		t.Fatalf("serial.LoadJSONConfig(anti-dpi matrix): %v", err)
	}
	inst, err := core.New(cfg)
	if err != nil {
		t.Fatalf("core.New(anti-dpi matrix): %v", err)
	}
	_ = inst.Close()
}
