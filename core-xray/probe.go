package yumi

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtls/xray-core/app/dispatcher"
	"github.com/xtls/xray-core/app/proxyman"
	"github.com/xtls/xray-core/common"
	v2net "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/net/cnc"
	cserial "github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/dns/localdns"
	"github.com/xtls/xray-core/features/outbound"
	"github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/transport"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/pipe"
)

const (
	defaultProbeURL          = "http://cp.cloudflare.com/generate_204"
	defaultProbeTimeout      = 5 * time.Second
	minProbeTimeout          = 500 * time.Millisecond
	defaultProbeConcurrency  = 12
	maxProbeConcurrencyLimit = 32
)

// yumiCompositeObm routes internet.DialSystem dialerProxy lookups for both active probe runs
// and the live VPN tunnel without letting either overwrite the other in xray-core's global obm.
type yumiCompositeObm struct {
	mu    sync.RWMutex
	live  outbound.Manager
	probe map[string]outbound.Handler
}

var (
	compositeObm = &yumiCompositeObm{
		probe: make(map[string]outbound.Handler),
	}
	probeHost   atomic.Pointer[core.Instance]
	probeRunSeq atomic.Uint64
)

func (m *yumiCompositeObm) Type() interface{} { return outbound.ManagerType() }
func (m *yumiCompositeObm) Start() error      { return nil }
func (m *yumiCompositeObm) Close() error      { return nil }

func (m *yumiCompositeObm) GetHandler(tag string) outbound.Handler {
	m.mu.RLock()
	if h := m.probe[tag]; h != nil {
		m.mu.RUnlock()
		return h
	}
	live := m.live
	m.mu.RUnlock()
	if live != nil {
		return live.GetHandler(tag)
	}
	return nil
}

func (m *yumiCompositeObm) GetDefaultHandler() outbound.Handler {
	m.mu.RLock()
	live := m.live
	m.mu.RUnlock()
	if live != nil {
		return live.GetDefaultHandler()
	}
	return nil
}

func (m *yumiCompositeObm) AddHandler(ctx context.Context, handler outbound.Handler) error {
	m.mu.RLock()
	live := m.live
	m.mu.RUnlock()
	if live != nil {
		return live.AddHandler(ctx, handler)
	}
	return nil
}

func (m *yumiCompositeObm) RemoveHandler(ctx context.Context, tag string) error {
	m.mu.RLock()
	live := m.live
	m.mu.RUnlock()
	if live != nil {
		return live.RemoveHandler(ctx, tag)
	}
	return nil
}

func (m *yumiCompositeObm) ListHandlers(ctx context.Context) []outbound.Handler {
	m.mu.RLock()
	live := m.live
	out := make([]outbound.Handler, 0, len(m.probe))
	for _, h := range m.probe {
		out = append(out, h)
	}
	m.mu.RUnlock()
	if live != nil {
		out = append(out, live.ListHandlers(ctx)...)
	}
	return out
}

func (m *yumiCompositeObm) Select(selectors []string) []string {
	m.mu.RLock()
	live := m.live
	m.mu.RUnlock()
	if sel, ok := live.(outbound.HandlerSelector); ok {
		return sel.Select(selectors)
	}
	return nil
}

func (m *yumiCompositeObm) setLive(live outbound.Manager) {
	m.mu.Lock()
	m.live = live
	m.mu.Unlock()
}

func (m *yumiCompositeObm) addProbe(tag string, h outbound.Handler) {
	m.mu.Lock()
	m.probe[tag] = h
	m.mu.Unlock()
}

func (m *yumiCompositeObm) removeProbe(tag string) {
	m.mu.Lock()
	delete(m.probe, tag)
	m.mu.Unlock()
}

// ensureProbeHostLocked creates the minimal persistent Xray host instance used solely to construct
// temporary outbound handlers. Deliberately omits app/log so global xlog handlers are never touched.
// Must be called with [mu] held.
func ensureProbeHostLocked() (*core.Instance, error) {
	if h := probeHost.Load(); h != nil {
		return h, nil
	}
	systemDialerOnce.Do(installSystemDialer)

	cfg := &core.Config{
		App: []*cserial.TypedMessage{
			cserial.ToTypedMessage(&dispatcher.Config{}),
			cserial.ToTypedMessage(&proxyman.InboundConfig{}),
			cserial.ToTypedMessage(&proxyman.OutboundConfig{}),
		},
	}
	h, err := core.New(cfg)
	if err != nil {
		return nil, err
	}
	if err := h.Start(); err != nil {
		_ = h.Close()
		return nil, err
	}
	internet.InitSystemDialer(localdns.New(), compositeObm)
	probeHost.Store(h)
	return h, nil
}

func ensureProbeHost() (*core.Instance, error) {
	if h := probeHost.Load(); h != nil {
		return h, nil
	}
	mu.Lock()
	defer mu.Unlock()
	return ensureProbeHostLocked()
}

// ProbeCallback delivers per-outbound delay results as each probe finishes.
type ProbeCallback interface {
	OnResult(tag string, delay int)
}

// ProbeSession controls a running StartProbe sweep.
type ProbeSession struct {
	cancel context.CancelFunc
	done   chan struct{}
}

// Cancel stops an in-flight probe sweep and suppresses further callbacks.
func (s *ProbeSession) Cancel() {
	if s != nil && s.cancel != nil {
		s.cancel()
	}
}

// Await blocks until all active probe workers and cleanup finish.
func (s *ProbeSession) Await() {
	if s != nil && s.done != nil {
		<-s.done
	}
}

type supportProbeHandler struct {
	tag     string
	handler outbound.Handler
}

// StartProbe measures each requested outbound tag inside configJSON using ephemeral handlers on an
// isolated host instance, streaming results to cb as probes complete without affecting any running
// VPN tunnel.
func StartProbe(
	configJSON string,
	tags string,
	probeURL string,
	timeoutMillis int,
	concurrency int,
	cb ProbeCallback,
) (*ProbeSession, error) {
	host, err := ensureProbeHost()
	if err != nil {
		return nil, err
	}

	cfg, err := serial.LoadJSONConfig(strings.NewReader(configJSON))
	if err != nil {
		return nil, err
	}
	if err := rewriteGrpcOutbounds(cfg); err != nil {
		return nil, err
	}
	if err := rewriteHysteriaProbeOutbounds(cfg); err != nil {
		return nil, err
	}

	rawFields := strings.Fields(tags)
	wanted := make([]string, 0, len(rawFields))
	wantedSet := make(map[string]bool, len(rawFields))
	for _, tag := range rawFields {
		if tag != "" && !wantedSet[tag] {
			wantedSet[tag] = true
			wanted = append(wanted, tag)
		}
	}

	if len(wanted) == 0 {
		done := make(chan struct{})
		close(done)
		return &ProbeSession{cancel: func() {}, done: done}, nil
	}

	probeURL = strings.TrimSpace(probeURL)
	if probeURL == "" {
		probeURL = defaultProbeURL
	}

	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout <= 0 {
		timeout = defaultProbeTimeout
	} else if timeout < minProbeTimeout {
		timeout = minProbeTimeout
	}

	if concurrency <= 0 {
		concurrency = defaultProbeConcurrency
	} else if concurrency > maxProbeConcurrencyLimit {
		concurrency = maxProbeConcurrencyLimit
	}
	if concurrency > len(wanted) {
		concurrency = len(wanted)
	}

	runPrefix := fmt.Sprintf("probe-%d-", probeRunSeq.Add(1))
	targetConfigs := make(map[string]*core.OutboundHandlerConfig, len(wanted))
	supportConfigs := make([]*core.OutboundHandlerConfig, 0, 4)

	for _, ob := range cfg.GetOutbound() {
		if ob == nil {
			continue
		}
		origTag := ob.GetTag()
		if origTag != "" && wantedSet[origTag] {
			if _, exists := targetConfigs[origTag]; !exists {
				targetConfigs[origTag] = ob
			}
		} else if origTag != "" {
			supportConfigs = append(supportConfigs, ob)
		}
	}

	supportTagMap := make(map[string]string, len(supportConfigs))
	supportHandlers := make([]supportProbeHandler, 0, len(supportConfigs))
	for _, ob := range supportConfigs {
		origTag := ob.GetTag()
		prefixedTag := runPrefix + origTag
		ob.Tag = prefixedTag
		rawObj, err := core.CreateObject(host, ob)
		if err != nil {
			continue
		}
		h, ok := rawObj.(outbound.Handler)
		if !ok {
			continue
		}
		if err := h.Start(); err != nil {
			_ = h.Close()
			continue
		}
		supportTagMap[origTag] = prefixedTag
		compositeObm.addProbe(prefixedTag, h)
		supportHandlers = append(supportHandlers, supportProbeHandler{
			tag:     prefixedTag,
			handler: h,
		})
	}

	if len(supportTagMap) > 0 {
		for _, ob := range targetConfigs {
			rewriteProbeDialerProxy(ob, supportTagMap)
		}
	}

	probeCtx, probeCancel := context.WithCancel(context.Background())
	done := make(chan struct{})

	jobs := make(chan string, len(wanted))
	for _, tag := range wanted {
		jobs <- tag
	}
	close(jobs)

	var wg sync.WaitGroup
	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for tag := range jobs {
				if probeCtx.Err() != nil {
					return
				}
				obCfg := targetConfigs[tag]
				if obCfg == nil {
					if probeCtx.Err() == nil && cb != nil {
						cb.OnResult(tag, -1)
					}
					continue
				}

				rawObj, err := core.CreateObject(host, obCfg)
				if err != nil {
					if probeCtx.Err() == nil && cb != nil {
						cb.OnResult(tag, -1)
					}
					continue
				}
				h, ok := rawObj.(outbound.Handler)
				if !ok {
					if probeCtx.Err() == nil && cb != nil {
						cb.OnResult(tag, -1)
					}
					continue
				}
				if err := h.Start(); err != nil {
					_ = h.Close()
					if probeCtx.Err() == nil && cb != nil {
						cb.OnResult(tag, -1)
					}
					continue
				}

				delay := measureHandler(probeCtx, h, tag, probeURL, timeout)
				_ = h.Close()

				if probeCtx.Err() == nil && cb != nil {
					cb.OnResult(tag, int(delay))
				}
			}
		}()
	}

	go func() {
		wg.Wait()
		for _, s := range supportHandlers {
			compositeObm.removeProbe(s.tag)
			_ = s.handler.Close()
		}
		closeGrpcConnsForGenCtx(probeCtx)
		probeCancel()
		close(done)
	}()

	return &ProbeSession{
		cancel: probeCancel,
		done:   done,
	}, nil
}

func rewriteProbeDialerProxy(ob *core.OutboundHandlerConfig, supportTagMap map[string]string) {
	if ob == nil || ob.SenderSettings == nil {
		return
	}
	msg, err := ob.SenderSettings.GetInstance()
	if err != nil {
		return
	}
	senderCfg, ok := msg.(*proxyman.SenderConfig)
	if !ok || senderCfg == nil || senderCfg.StreamSettings == nil || senderCfg.StreamSettings.SocketSettings == nil {
		return
	}
	dp := senderCfg.StreamSettings.SocketSettings.DialerProxy
	if mapped, found := supportTagMap[dp]; found {
		senderCfg.StreamSettings.SocketSettings.DialerProxy = mapped
		ob.SenderSettings = cserial.ToTypedMessage(senderCfg)
	}
}

func measureHandler(
	probeCtx context.Context,
	handler outbound.Handler,
	tag string,
	probeURL string,
	timeout time.Duration,
) int64 {
	reqCtx, cancel := context.WithTimeout(probeCtx, timeout)
	defer cancel()

	var connsMu sync.Mutex
	var conns []*deadlineConn

	client := &http.Client{
		Transport: &http.Transport{
			Proxy: func(*http.Request) (*url.URL, error) { return nil, nil },
			DialContext: func(_ context.Context, network, addr string) (net.Conn, error) {
				dest, err := v2net.ParseDestination(network + ":" + addr)
				if err != nil {
					return nil, err
				}
				connCtx, connCancel := context.WithCancel(reqCtx)
				content := &session.Content{SkipDNSResolve: true}
				dispatchCtx := session.ContextWithContent(connCtx, content)
				dispatchCtx = withMeasureProbeCtx(dispatchCtx)
				dispatchCtx = withGenCtxValue(dispatchCtx, probeCtx)
				dispatchCtx = session.ContextWithOutbounds(dispatchCtx, []*session.Outbound{{
					Target:         dest,
					OriginalTarget: dest,
					Tag:            tag,
				}})

				opts := pipe.OptionsFromContext(dispatchCtx)
				ur, uw := pipe.New(opts...)
				dr, dw := pipe.New(opts...)
				go handler.Dispatch(dispatchCtx, &transport.Link{Reader: ur, Writer: dw})
				raw := cnc.NewConnection(
					cnc.ConnectionInputMulti(uw),
					cnc.ConnectionOutputMulti(dr),
					cnc.ConnectionOnClose(common.ChainedClosable{uw, dw}),
				)
				dc := newDeadlineConn(raw, connCancel, timeout, timeout)
				connsMu.Lock()
				conns = append(conns, dc)
				connsMu.Unlock()
				return dc, nil
			},
			TLSHandshakeTimeout:   timeout,
			ResponseHeaderTimeout: timeout,
			DisableKeepAlives:     true,
		},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		Timeout:       timeout,
	}
	defer func() {
		client.CloseIdleConnections()
		connsMu.Lock()
		for _, dc := range conns {
			_ = dc.Close()
		}
		connsMu.Unlock()
	}()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, probeURL, nil)
	if err != nil {
		return -1
	}
	req.Header.Set("User-Agent", "Yumi")
	req.Header.Set("Connection", "close")

	started := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return -1
	}
	if resp.Body != nil {
		_, _ = io.CopyN(io.Discard, resp.Body, 8192)
		_ = resp.Body.Close()
	}
	elapsed := time.Since(started).Milliseconds()
	if elapsed <= 0 {
		elapsed = 1
	}
	return elapsed
}
