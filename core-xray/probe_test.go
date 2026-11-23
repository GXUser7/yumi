package yumi

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
)

type probeCallbackRecorder struct {
	mu      sync.Mutex
	results map[string]int
	order   []string
	ch      chan string
}

func newProbeCallbackRecorder() *probeCallbackRecorder {
	return &probeCallbackRecorder{
		results: make(map[string]int),
		ch:      make(chan string, 32),
	}
}

func (r *probeCallbackRecorder) OnResult(tag string, delay int) {
	r.mu.Lock()
	r.results[tag] = delay
	r.order = append(r.order, tag)
	r.mu.Unlock()
	select {
	case r.ch <- tag:
	default:
	}
}

func (r *probeCallbackRecorder) snapshot() map[string]int {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make(map[string]int, len(r.results))
	for k, v := range r.results {
		out[k] = v
	}
	return out
}

type captureLogger struct {
	mu    sync.Mutex
	lines []string
}

func (l *captureLogger) Log(level int, message string) {
	l.mu.Lock()
	l.lines = append(l.lines, fmt.Sprintf("%d:%s", level, message))
	l.mu.Unlock()
}

func (l *captureLogger) count() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.lines)
}

func freeTCPPort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("freeTCPPort: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()
	return port
}

func freeUDPPort(t *testing.T) int {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("freeUDPPort: %v", err)
	}
	port := pc.LocalAddr().(*net.UDPAddr).Port
	_ = pc.Close()
	return port
}

func startXrayTestServer(t *testing.T, configJSON string) *core.Instance {
	t.Helper()
	cfg, err := serial.LoadJSONConfig(strings.NewReader(configJSON))
	if err != nil {
		t.Fatalf("LoadJSONConfig server: %v", err)
	}
	inst, err := core.New(cfg)
	if err != nil {
		t.Fatalf("core.New server: %v", err)
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		t.Fatalf("inst.Start server: %v", err)
	}
	t.Cleanup(func() { _ = inst.Close() })
	return inst
}

func generatePinnedSelfSignedCert(t *testing.T, dnsName string) (certLines []string, keyLines []string, sha256Hex string) {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("GenerateKey: %v", err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: dnsName},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     []string{dnsName},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &priv.PublicKey, priv)
	if err != nil {
		t.Fatalf("CreateCertificate: %v", err)
	}
	keyDER, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		t.Fatalf("MarshalECPrivateKey: %v", err)
	}
	certPEM := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
	keyPEM := string(pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}))
	sum := sha256.Sum256(der)
	return strings.Split(strings.TrimSpace(certPEM), "\n"),
		strings.Split(strings.TrimSpace(keyPEM), "\n"),
		hex.EncodeToString(sum[:])
}

func TestStartProbeStreamsResultsAndCancels(t *testing.T) {
	_ = Stop()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	probeURL := srv.URL + "/generate_204"

	// Local Xray VLESS server for TCP and Fragment probes.
	vlessPort := freeTCPPort(t)
	vlessUUID := "11111111-2222-3333-4444-555555555555"
	serverCfg := fmt.Sprintf(`{
		"log": {"loglevel": "none"},
		"inbounds": [{
			"tag": "in-vless",
			"listen": "127.0.0.1",
			"port": %d,
			"protocol": "vless",
			"settings": {
				"clients": [{"id": "%s"}],
				"decryption": "none"
			}
		}],
		"outbounds": [{
			"tag": "direct",
			"protocol": "freedom",
			"settings": {"finalRules": [{"action": "allow", "ip": ["127.0.0.1/32"]}]}
		}]
	}`, vlessPort, vlessUUID)
	startXrayTestServer(t, serverCfg)

	// Black-hole TCP listener that accepts but never writes back, forcing timeout unless cancelled.
	stallLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen stall: %v", err)
	}
	defer stallLn.Close()
	var held []net.Conn
	var heldMu sync.Mutex
	go func() {
		for {
			c, err := stallLn.Accept()
			if err != nil {
				return
			}
			heldMu.Lock()
			held = append(held, c)
			heldMu.Unlock()
		}
	}()
	defer func() {
		heldMu.Lock()
		for _, c := range held {
			_ = c.Close()
		}
		heldMu.Unlock()
	}()
	stallPort := stallLn.Addr().(*net.TCPAddr).Port
	deadPort := freeTCPPort(t)

	probeCfg := fmt.Sprintf(`{
		"outbounds": [
			{
				"tag": "proxy-ok",
				"protocol": "vless",
				"settings": {
					"vnext": [{
						"address": "127.0.0.1",
						"port": %d,
						"users": [{"id": "%s", "encryption": "none"}]
					}]
				}
			},
			{
				"tag": "proxy-frag",
				"protocol": "vless",
				"settings": {
					"vnext": [{
						"address": "127.0.0.1",
						"port": %d,
						"users": [{"id": "%s", "encryption": "none"}]
					}]
				},
				"streamSettings": {
					"sockopt": {"dialerProxy": "fragment"}
				}
			},
			{
				"tag": "proxy-dead",
				"protocol": "vless",
				"settings": {
					"vnext": [{
						"address": "127.0.0.1",
						"port": %d,
						"users": [{"id": "%s", "encryption": "none"}]
					}]
				}
			},
			{
				"tag": "fragment",
				"protocol": "freedom",
				"settings": {
					"domainStrategy": "AsIs",
					"fragment": {"packets": "1-3", "length": "10-20", "interval": "1-2"}
				}
			},
			{"tag": "direct", "protocol": "freedom", "settings": {"domainStrategy": "AsIs"}}
		]
	}`, vlessPort, vlessUUID, vlessPort, vlessUUID, deadPort, vlessUUID)

	rec := newProbeCallbackRecorder()
	sess, err := StartProbe(probeCfg, "proxy-ok proxy-frag proxy-dead", probeURL, 2000, 3, rec)
	if err != nil {
		t.Fatalf("StartProbe: %v", err)
	}
	sess.Await()

	got := rec.snapshot()
	if got["proxy-ok"] <= 0 {
		t.Fatalf("expected proxy-ok > 0 ms, got %v", got)
	}
	if got["proxy-frag"] <= 0 {
		t.Fatalf("expected proxy-frag > 0 ms, got %v", got)
	}
	if got["proxy-dead"] != -1 {
		t.Fatalf("expected proxy-dead = -1, got %v", got)
	}

	// Verify Cancel aborts a stalling probe well before its 8s timeout and suppresses late callbacks.
	stallCfg := fmt.Sprintf(`{
		"outbounds": [
			{
				"tag": "proxy-ok",
				"protocol": "vless",
				"settings": {
					"vnext": [{
						"address": "127.0.0.1",
						"port": %d,
						"users": [{"id": "%s", "encryption": "none"}]
					}]
				}
			},
			{
				"tag": "proxy-stall",
				"protocol": "vless",
				"settings": {
					"vnext": [{
						"address": "127.0.0.1",
						"port": %d,
						"users": [{"id": "%s", "encryption": "none"}]
					}]
				}
			}
		]
	}`, vlessPort, vlessUUID, stallPort, vlessUUID)

	recCancel := newProbeCallbackRecorder()
	cancelStart := time.Now()
	sess2, err := StartProbe(stallCfg, "proxy-ok proxy-stall", probeURL, 8000, 2, recCancel)
	if err != nil {
		t.Fatalf("StartProbe(cancel): %v", err)
	}
	select {
	case firstTag := <-recCancel.ch:
		if firstTag != "proxy-ok" {
			t.Fatalf("expected proxy-ok to stream first, got %q", firstTag)
		}
	case <-time.After(3 * time.Second):
		sess2.Cancel()
		t.Fatal("timed out waiting for streaming proxy-ok callback before Wait()")
	}
	sess2.Cancel()
	sess2.Await()
	if elapsed := time.Since(cancelStart); elapsed > 2500*time.Millisecond {
		t.Fatalf("Cancel()+Wait() took too long: %v", elapsed)
	}
	afterCancel := recCancel.snapshot()
	if _, leaked := afterCancel["proxy-stall"]; leaked {
		t.Fatalf("expected cancelled proxy-stall not to emit late callback, got %v", afterCancel)
	}
}

func TestStartProbeHysteria2SalamanderAndSharedEndpointMultiAuth(t *testing.T) {
	_ = Stop()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	probeURL := srv.URL + "/generate_204"

	certLines, keyLines, certHash := generatePinnedSelfSignedCert(t, "hy2.test.local")
	hyPort := freeUDPPort(t)
	salamanderPass := "obfs-secret-key"

	serverDoc := map[string]any{
		"log": map[string]any{"loglevel": "none"},
		"inbounds": []any{
			map[string]any{
				"tag":      "in-hy2",
				"listen":   "127.0.0.1",
				"port":     hyPort,
				"protocol": "hysteria",
				"settings": map[string]any{
					"version": 2,
					"users": []any{
						map[string]any{"auth": "good-pass"},
					},
				},
				"streamSettings": map[string]any{
					"network":  "hysteria",
					"security": "tls",
					"tlsSettings": map[string]any{
						"certificates": []any{
							map[string]any{
								"certificate": certLines,
								"key":         keyLines,
							},
						},
					},
					"hysteriaSettings": map[string]any{
						"version": 2,
					},
					"finalmask": map[string]any{
						"udp": []any{
							map[string]any{
								"type":     "salamander",
								"settings": map[string]any{"password": salamanderPass},
							},
						},
					},
				},
			},
		},
		"outbounds": []any{
			map[string]any{
				"tag":      "direct",
				"protocol": "freedom",
				"settings": map[string]any{
					"finalRules": []any{
						map[string]any{
							"action": "allow",
							"ip":     []string{"127.0.0.1/32"},
						},
					},
				},
			},
		},
	}
	serverBytes, err := json.Marshal(serverDoc)
	if err != nil {
		t.Fatalf("marshal hy2 server config: %v", err)
	}
	startXrayTestServer(t, string(serverBytes))

	buildHyOutbound := func(tag, auth string) map[string]any {
		return map[string]any{
			"tag":      tag,
			"protocol": "hysteria",
			"settings": map[string]any{
				"version": 2,
				"address": "127.0.0.1",
				"port":    hyPort,
			},
			"streamSettings": map[string]any{
				"network":  "hysteria",
				"security": "tls",
				"tlsSettings": map[string]any{
					"serverName":           "hy2.test.local",
					"pinnedPeerCertSha256": certHash,
				},
				"hysteriaSettings": map[string]any{
					"version": 2,
					"auth":    auth,
				},
				"finalmask": map[string]any{
					"udp": []any{
						map[string]any{
							"type":     "salamander",
							"settings": map[string]any{"password": salamanderPass},
						},
					},
				},
			},
		}
	}

	probeDoc := map[string]any{
		"outbounds": []any{
			buildHyOutbound("proxy-hy-bad", "wrong-pass"),
			buildHyOutbound("proxy-hy-good", "good-pass"),
		},
	}
	probeBytes, err := json.Marshal(probeDoc)
	if err != nil {
		t.Fatalf("marshal hy2 probe config: %v", err)
	}

	rec := newProbeCallbackRecorder()
	sess, err := StartProbe(string(probeBytes), "proxy-hy-bad proxy-hy-good", probeURL, 3000, 2, rec)
	if err != nil {
		t.Fatalf("StartProbe(hy2): %v", err)
	}
	sess.Await()

	got := rec.snapshot()
	if got["proxy-hy-good"] <= 0 {
		t.Fatalf("expected proxy-hy-good > 0 ms with Salamander + cert pin, got %v", got)
	}
	if got["proxy-hy-bad"] != -1 {
		t.Fatalf("expected proxy-hy-bad = -1 (no shared cache bleed on same ip:port), got %v", got)
	}
}

func TestStartProbeCoexistsWithLiveCore(t *testing.T) {
	_ = Stop()
	defer func() {
		SetLogger(nil)
		_ = Stop()
	}()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	probeURL := srv.URL + "/generate_204"

	vlessPort := freeTCPPort(t)
	vlessUUID := "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
	serverCfg := fmt.Sprintf(`{
		"log": {"loglevel": "none"},
		"inbounds": [{
			"tag": "in-vless",
			"listen": "127.0.0.1",
			"port": %d,
			"protocol": "vless",
			"settings": {
				"clients": [{"id": "%s"}],
				"decryption": "none"
			}
		}],
		"outbounds": [{
			"tag": "direct",
			"protocol": "freedom",
			"settings": {"finalRules": [{"action": "allow", "ip": ["127.0.0.1/32"]}]}
		}]
	}`, vlessPort, vlessUUID)
	startXrayTestServer(t, serverCfg)

	liveCfg := fmt.Sprintf(`{
		"log": {"loglevel": "info"},
		"inbounds": [{
			"tag": "tun-in",
			"protocol": "tun",
			"settings": {"name": "tun0", "mtu": 1400, "userLevel": 0}
		}],
		"outbounds": [
			{
				"tag": "proxy-live",
				"protocol": "vless",
				"settings": {
					"vnext": [{
						"address": "127.0.0.1",
						"port": %d,
						"users": [{"id": "%s", "encryption": "none"}]
					}]
				}
			},
			{"tag": "direct", "protocol": "freedom", "settings": {"domainStrategy": "AsIs"}}
		],
		"routing": {
			"balancers": [{"tag": "proxy", "selector": ["proxy-"]}],
			"rules": []
		}
	}`, vlessPort, vlessUUID)

	probeCfg := fmt.Sprintf(`{
		"outbounds": [
			{
				"tag": "proxy-a",
				"protocol": "vless",
				"settings": {
					"vnext": [{
						"address": "127.0.0.1",
						"port": %d,
						"users": [{"id": "%s", "encryption": "none"}]
					}]
				},
				"streamSettings": {"sockopt": {"dialerProxy": "fragment"}}
			},
			{
				"tag": "proxy-b",
				"protocol": "vless",
				"settings": {
					"vnext": [{
						"address": "127.0.0.1",
						"port": %d,
						"users": [{"id": "%s", "encryption": "none"}]
					}]
				}
			},
			{
				"tag": "fragment",
				"protocol": "freedom",
				"settings": {
					"domainStrategy": "AsIs",
					"fragment": {"packets": "1-3", "length": "10-20", "interval": "1-2"}
				}
			},
			{"tag": "direct", "protocol": "freedom", "settings": {"domainStrategy": "AsIs"}}
		]
	}`, vlessPort, vlessUUID, vlessPort, vlessUUID)

	t.Run("probe_while_tunnel_connected", func(t *testing.T) {
		logger := &captureLogger{}
		SetLogger(logger)

		ep := channel.New(16, 1400, "")
		defer ep.Close()
		if err := startCore(liveCfg, 0, "proxy-live", testProtector{}, &testGuard{allow: true}, ep); err != nil {
			t.Fatalf("startCore failed: %v", err)
		}
		defer func() { _ = Stop() }()

		code, err := ProbeHTTP(probeURL, 2000, false)
		if err != nil || code != http.StatusNoContent {
			t.Fatalf("live ProbeHTTP before probe failed: code=%d err=%v", code, err)
		}
		beforeLogs := logger.count()

		rec := newProbeCallbackRecorder()
		sess, err := StartProbe(probeCfg, "proxy-a proxy-b", probeURL, 2000, 2, rec)
		if err != nil {
			t.Fatalf("StartProbe while connected: %v", err)
		}
		sess.Await()

		got := rec.snapshot()
		if got["proxy-a"] <= 0 || got["proxy-b"] <= 0 {
			t.Fatalf("expected probes to succeed while connected, got %v", got)
		}

		// Live tunnel and logger must still work undisturbed after probe finishes.
		code, err = ProbeHTTP(probeURL, 2000, false)
		if err != nil || code != http.StatusNoContent {
			t.Fatalf("live ProbeHTTP after probe failed: code=%d err=%v", code, err)
		}
		if logger.count() < beforeLogs {
			t.Fatalf("logger count regressed: before=%d after=%d", beforeLogs, logger.count())
		}
	})

	t.Run("tunnel_starts_and_stops_mid_probe", func(t *testing.T) {
		var slowHits atomic.Int32
		slowSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			slowHits.Add(1)
			select {
			case <-time.After(250 * time.Millisecond):
				w.WriteHeader(http.StatusNoContent)
			case <-r.Context().Done():
			}
		}))
		defer slowSrv.Close()
		slowURL := slowSrv.URL + "/generate_204"

		// Pre-warm probeHost so startCore & StartProbe mirror real lifecycle cleanly.
		rec := newProbeCallbackRecorder()
		sess, err := StartProbe(probeCfg, "proxy-a proxy-b", slowURL, 3000, 2, rec)
		if err != nil {
			t.Fatalf("StartProbe mid-toggle: %v", err)
		}

		time.Sleep(50 * time.Millisecond)
		ep := channel.New(16, 1400, "")
		defer ep.Close()
		if err := startCore(liveCfg, 0, "proxy-live", testProtector{}, &testGuard{allow: true}, ep); err != nil {
			sess.Cancel()
			sess.Await()
			t.Fatalf("startCore mid-probe failed: %v", err)
		}

		code, err := ProbeHTTP(probeURL, 2000, false)
		if err != nil || code != http.StatusNoContent {
			_ = Stop()
			sess.Cancel()
			sess.Await()
			t.Fatalf("live ProbeHTTP mid-probe failed: code=%d err=%v", code, err)
		}

		if err := Stop(); err != nil {
			sess.Cancel()
			sess.Await()
			t.Fatalf("Stop mid-probe failed: %v", err)
		}

		sess.Await()
		got := rec.snapshot()
		if got["proxy-a"] <= 0 || got["proxy-b"] <= 0 {
			t.Fatalf("expected probes to finish cleanly across start/stop, got %v (slowHits=%d)", got, slowHits.Load())
		}
	})
}
