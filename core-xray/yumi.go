// Package yumi is the Android binding for Xray-core.
//
// It exists because Xray, unlike sing-box, ships no mobile binding of its own: there is no libbox
// equivalent to hand an .aar to Gradle. What crosses into Kotlin is deliberately narrow — gomobile
// can only carry a handful of types across the boundary, and every method here is one more thing to
// keep working through core upgrades.
//
// The division of labour is the opposite of sing-box's. libbox inverts control: it calls back into
// the app to open the tunnel, enumerate interfaces and resolve names, so the app implements a
// sixty-method PlatformInterface. Xray does none of that. The app builds the tunnel itself with
// VpnService.Builder, keeps the fd, and hands the number here. Everything the app used to answer
// questions about, it now simply decides.
package yumi

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/GXUser7/yumi/core-xray/tun"
	xlog "github.com/xtls/xray-core/common/log"
	v2net "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	xdns "github.com/xtls/xray-core/features/dns"
	"github.com/xtls/xray-core/features/inbound"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/features/stats"
	"github.com/xtls/xray-core/infra/conf/serial"
	xtun "github.com/xtls/xray-core/proxy/tun"
	"gvisor.dev/gvisor/pkg/tcpip/stack"

	// Registers every protocol, transport and app with the core's factory tables. Without this
	// blank import the library links and starts, and then refuses every outbound in the config
	// with "unknown protocol" — a failure that reads as a bad config rather than a missing import.
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	// Guards the instance against a stop racing a start. Both come from Android lifecycle
	// callbacks on different threads, and the losing order leaks a running core with no handle.
	mu       sync.Mutex
	instance *core.Instance
)

// Protector is implemented on the Kotlin side by the VpnService.
//
// Every socket the core opens has to be excluded from the tunnel the core itself is serving, or
// its traffic re-enters that tunnel and the connection deadlocks with no error anywhere.
type Protector interface {
	// Protect excludes a socket from the VPN. False means VpnService.protect refused, which is
	// fatal for that connection — returning it lets the core fail the dial instead of hanging.
	Protect(fd int) bool
}

var (
	// yumiSystemDialer is installed once via internet.UseAlternativeSystemDialer so it can read
	// whichever Protector is active on the current core generation.
	protectorMu sync.RWMutex
	protector   Protector
)

// Logger is implemented on the Kotlin side.
//
// Xray writes its log through a single process-wide handler rather than to a stream something can
// subscribe to, so this is the only way the app sees anything the core has to say.
type Logger interface {
	// Log delivers one line. Levels follow the core's own numbering, which is not the app's and
	// not syslog's: 0 unknown, 1 error, 2 warning, 3 info, 4 debug
	// (`common/log/log.pb.go:26-32`). Mapping them is the caller's job.
	Log(level int, message string)
}

var (
	loggerMu sync.RWMutex
	logger   Logger
)

// SetLogger names where the core's output goes. Null detaches it.
//
// Safe to call at any time: the handler installed in [Start] reads this through the lock rather
// than capturing whatever was set when the core came up.
func SetLogger(l Logger) {
	loggerMu.Lock()
	logger = l
	loggerMu.Unlock()
}

type bridgeHandler struct{}

func (bridgeHandler) Handle(msg xlog.Message) {
	loggerMu.RLock()
	l := logger
	loggerMu.RUnlock()
	if l == nil {
		return
	}
	// Only GeneralMessage carries a severity; access and DNS records do not, and calling them
	// info is closer than calling them errors.
	level := int(xlog.Severity_Info)
	if general, ok := msg.(*xlog.GeneralMessage); ok {
		level = int(general.Severity)
	}
	l.Log(level, msg.String())
}

// Tags this bridge and the configuration have to agree on.
//
// Every proxy node becomes an outbound of its own, tagged NodeTagPrefix + the node's id, and a
// balancer tagged BalancerTag selects among them with `selector: ["proxy-"]` — matched by prefix
// in app/proxyman/outbound/outbound.go:177. Routing rules name the balancer rather than an
// outbound, and that indirection is the whole reason [SelectOutbound] can move the tunnel without
// rebuilding it.
//
// Spelled out on both sides rather than derived. A mismatch is silent in both directions: the
// counters stay at zero and the balancer finds no candidates, and neither says why.
const (
	BalancerTag   = "proxy"
	NodeTagPrefix = "proxy-"
)

// The node tags the running configuration declared.
//
// Kept because the core will not check them for us. Router.SetOverrideTarget stores whatever
// string it is handed (app/router/balancing.go:153-158) and Balancer.PickOutbound returns it
// verbatim without consulting the candidate list (balancing.go:104-106) — so a tag that no longer
// exists is not refused. It becomes an outbound nobody can dial, every new connection dies, and
// nothing in the log ties that to the switch that caused it. libbox refused the equivalent call,
// and the app is written expecting to be told no.
var (
	tagsMu   sync.RWMutex
	nodeTags map[string]bool
)

// Uplink and Downlink report bytes moved through the proxy since the core came up.
//
// Summed across every node outbound rather than read from the active one. The balancer moves
// traffic between them, and a counter read from the current target alone would walk backwards on
// every switch — which is the same session-counter flicker sing-box produced when two cores wrote
// into one total. Direct and block outbounds fall outside the prefix and are not counted.
//
// Zero when the core is down, when the configuration did not ask for statistics, or before the
// first byte — none of which are distinguishable here, and none of which the caller could act on
// differently anyway.
func Uplink() int64   { return trafficSum("uplink") }
func Downlink() int64 { return trafficSum("downlink") }

func trafficSum(direction string) int64 {
	manager := statsManager()
	if manager == nil {
		return 0
	}
	prefix := "outbound>>>" + NodeTagPrefix
	suffix := ">>>traffic>>>" + direction
	var total int64
	manager.VisitCounters(func(name string, c stats.Counter) bool {
		if strings.HasPrefix(name, prefix) && strings.HasSuffix(name, suffix) {
			total += c.Value()
		}
		return true
	})
	return total
}

func statsManager() stats.Manager {
	mu.Lock()
	running := instance
	mu.Unlock()
	if running == nil {
		return nil
	}
	manager, ok := running.GetFeature(stats.ManagerType()).(stats.Manager)
	if !ok {
		return nil
	}
	return manager
}

// SelectOutbound points the balancer at one node, moving the tunnel without rebuilding it.
//
// This is the Xray answer to libbox's selectOutbound, and the reason the configuration puts every
// node under a balancer instead of naming the one server the user picked. The TUN, the DNS cache
// and every connection to a server that is staying up are left alone; only new connections go
// somewhere else.
//
// An unknown tag is refused here rather than passed on, for the reason recorded on [nodeTags].
func SelectOutbound(tag string) error {
	tagsMu.RLock()
	known := nodeTags[tag]
	tagsMu.RUnlock()
	if !known {
		return errors.New("no outbound tagged " + tag + " in the running configuration")
	}

	overrider, err := balancerOverrider()
	if err != nil {
		return err
	}
	if err := overrider.SetOverrideTarget(BalancerTag, tag); err != nil {
		return err
	}
	setCorePinnedTag(tag)
	evictOtherYumiGrpcConns(tag)
	return nil
}

// ActiveOutbound reports the node the balancer is pinned to. Empty when nothing is running, or
// when the balancer is left to choose for itself — the override carries no expiry
// (app/router/balancing_override.go:23-25), so once set it holds until it is set again.
func ActiveOutbound() string {
	overrider, err := balancerOverrider()
	if err != nil {
		return ""
	}
	target, err := overrider.GetOverrideTarget(BalancerTag)
	if err != nil {
		return ""
	}
	return target
}

func balancerOverrider() (routing.BalancerOverrider, error) {
	mu.Lock()
	running := instance
	mu.Unlock()
	if running == nil {
		return nil, errors.New("core is not running")
	}
	return overriderFor(running)
}

func overriderFor(running *core.Instance) (routing.BalancerOverrider, error) {
	router, ok := running.GetFeature(routing.RouterType()).(routing.Router)
	if !ok || router == nil {
		return nil, errors.New("the running configuration has no router")
	}
	overrider, ok := router.(routing.BalancerOverrider)
	if !ok {
		return nil, errors.New("this router cannot be overridden")
	}
	return overrider, nil
}

// StatsReport says why the counters are what they are.
//
// Exists because zero is indistinguishable from broken: counters that were never registered, a
// statistics app the configuration never asked for, and a tunnel that has genuinely moved nothing
// all read the same from Kotlin. Only ever called from a log line.
func StatsReport() string {
	manager := statsManager()
	if manager == nil {
		if !IsRunning() {
			return "core is not running"
		}
		return "no stats manager: the configuration has no \"stats\" section"
	}
	counted := 0
	manager.VisitCounters(func(name string, _ stats.Counter) bool {
		if strings.HasPrefix(name, "outbound>>>"+NodeTagPrefix) {
			counted++
		}
		return true
	})
	if counted == 0 {
		return fmt.Sprintf(
			"no counters under %q: the policy did not ask for them, or the outbounds carry other tags",
			NodeTagPrefix,
		)
	}
	return fmt.Sprintf(
		"ok: %d counters, up=%d down=%d, pinned to %q",
		counted, Uplink(), Downlink(), ActiveOutbound(),
	)
}

// MeasureOutbounds times a small HTTP request through each named outbound, in parallel.
//
// This is the replacement for libbox's `urlTest`, and the app leans on it for the one number that
// describes the failure it exists to escape: a server whose port answers a handshake cheerfully
// while nothing crosses it. A direct probe from the phone cannot see that. This one dials the probe
// URL *through* the outbound, so a server that accepts connections and carries nothing scores no
// delay at all.
//
// Three ways of doing this were weighed and two rejected. Xray's own `app/observatory` is a
// background poller: at a useful interval it measures every server every few seconds and eats the
// battery, and at a frugal one its numbers are ten minutes stale exactly when an outage makes them
// matter. Reusing the app's loopback SOCKS inbound would mean pointing the balancer at each
// candidate in turn — moving the user's live traffic onto untested servers to find out whether they
// work. Measuring on demand, through a forced outbound tag, is what `urlTest` did.
//
// tags are whitespace-separated; node tags contain no whitespace by construction. The result is JSON,
// `{"proxy-a": 143, "proxy-b": -1}`, with -1 for "asked and did not answer" — which is a different
// fact from "not measured", and the app depends on being able to tell them apart.
func MeasureOutbounds(tags string, probeURL string, timeoutMillis int) string {
	running, genCtx, err := runningInstanceWithCtx()
	if err != nil {
		return "{}"
	}

	wanted := make([]string, 0, 8)
	tagsMu.RLock()
	for _, tag := range strings.Fields(tags) {
		// Silently skipped rather than reported as dead: a tag that is not in the running
		// configuration was never measured, and calling that a failure would tell the watchdog a
		// working server is broken.
		if tag != "" && nodeTags[tag] {
			wanted = append(wanted, tag)
		}
	}
	tagsMu.RUnlock()
	if len(wanted) == 0 {
		return "{}"
	}

	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout <= 0 {
		timeout = 5 * time.Second
	}

	type result struct {
		tag   string
		delay int64
	}
	results := make(chan result, len(wanted))
	for _, tag := range wanted {
		go func(tag string) {
			results <- result{tag: tag, delay: measureOne(genCtx, running, tag, probeURL, timeout)}
		}(tag)
	}

	delays := make(map[string]int64, len(wanted))
	for range wanted {
		r := <-results
		delays[r.tag] = r.delay
	}
	encoded, err := json.Marshal(delays)
	if err != nil {
		return "{}"
	}
	return string(encoded)
}

// measureOne returns the round trip in milliseconds, or -1 when the outbound did not deliver.
func measureOne(
	genCtx context.Context,
	running *core.Instance,
	tag string,
	probeURL string,
	timeout time.Duration,
) int64 {
	reqCtx, cancel := context.WithTimeout(genCtx, timeout)
	defer cancel()

	// SkipDNSResolve leaves the name for the *server* to resolve, which is the whole point: the
	// probe has to succeed on a phone whose own resolver is dead, or a broken resolver would read
	// as every server in the subscription being broken at once.
	content := new(session.Content)
	content.SkipDNSResolve = true

	client := &http.Client{
		Transport: &http.Transport{
			Proxy: func(*http.Request) (*url.URL, error) { return nil, nil },
			DialContext: func(_ context.Context, network, addr string) (net.Conn, error) {
				dest, err := v2net.ParseDestination(network + ":" + addr)
				if err != nil {
					return nil, err
				}
				dialCtx := withMeasureProbeCtx(session.ContextWithContent(reqCtx, content))
				dialCtx = session.SetForcedOutboundTagToContext(dialCtx, tag)
				// core.Dial is the exported, stable entry point; it puts the instance into the
				// context the dispatcher needs while leaving the forced tag and reqCtx deadline intact.
				return core.Dial(dialCtx, running, dest)
			},
			TLSHandshakeTimeout: timeout,
			DisableKeepAlives:   true,
		},
		// A redirect would measure somebody else's server. The probe endpoints answer 204 with no
		// body, so there is nothing to follow anyway.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		Timeout:       timeout,
	}
	defer client.CloseIdleConnections()

	request, err := http.NewRequestWithContext(reqCtx, http.MethodGet, probeURL, nil)
	if err != nil {
		return -1
	}
	started := time.Now()
	response, err := client.Do(request)
	if err != nil {
		return -1
	}
	if response.Body != nil {
		response.Body.Close()
	}
	elapsed := time.Since(started).Milliseconds()
	// Zero would read as "instant" upstream, and nothing is instant through a proxy.
	if elapsed <= 0 {
		elapsed = 1
	}
	return elapsed
}

// TrafficGuard vets inbound connections entering the TUN interface before they are forwarded.
//
// Implementations on the Kotlin/Java side MUST catch all Throwables and fail-closed (return false)
// because gobind does not clear pending JNI exceptions on bool-returning interface callbacks.
type TrafficGuard interface {
	AllowConnection(proto int, srcIP []byte, srcPort int, dstIP []byte, dstPort int) bool
}

// Start brings the core up on the given configuration without a per-connection TrafficGuard.
//
// tunFd is the descriptor from VpnService.Builder.establish(). Ownership stays with the app: the
// core reads and writes it but never closes it, and closing it here would take the tunnel down
// under a Stop that was meant to be followed by another Start.
//
// pinnedTag is the node the balancer must be pointed at before this call returns.
func Start(configJSON string, tunFd int, pinnedTag string, p Protector) error {
	return startCore(configJSON, tunFd, pinnedTag, p, nil, nil)
}

// StartWithGuard brings the core up on the given configuration with a guarded TUN inbound.
//
// It intercepts the "tun-in" inbound from configJSON, removes it before core.New(config),
// pins pinnedTag before starting the core, and attaches a guarded core-xray/tun handler
// that enforces guard on TCP SYNs (pre-SYN-ACK), UDP 5-tuples (including DNS), and ICMP echo.
func StartWithGuard(configJSON string, tunFd int, pinnedTag string, p Protector, guard TrafficGuard) error {
	return startCore(configJSON, tunFd, pinnedTag, p, guard, nil)
}

func startCore(
	configJSON string,
	tunFd int,
	pinnedTag string,
	p Protector,
	guard TrafficGuard,
	epOverride stack.LinkEndpoint,
) error {
	mu.Lock()
	defer mu.Unlock()

	if instance != nil {
		return errors.New("core is already running")
	}
	if epOverride == nil && tunFd <= 0 {
		return errors.New("refusing to start without a tunnel descriptor")
	}

	systemDialerOnce.Do(installSystemDialer)
	protectorMu.Lock()
	protector = p
	protectorMu.Unlock()

	success := false
	defer func() {
		if !success {
			cancelCoreLifecycle()
			closeAllYumiGrpcConns()
			protectorMu.Lock()
			protector = nil
			protectorMu.Unlock()
		}
	}()

	config, err := serial.LoadJSONConfig(strings.NewReader(configJSON))
	if err != nil {
		return err
	}
	if err := rewriteGrpcOutbounds(config); err != nil {
		return err
	}

	var tunInbound *core.InboundHandlerConfig
	tunCount := 0
	remainingInbounds := make([]*core.InboundHandlerConfig, 0, len(config.Inbound))
	for _, ic := range config.Inbound {
		if ic == nil {
			continue
		}
		isTun := ic.GetTag() == "tun-in" ||
			(ic.GetProxySettings() != nil && ic.GetProxySettings().GetType() == "xray.proxy.tun.Config")
		if isTun {
			tunCount++
			tunInbound = ic
		} else {
			remainingInbounds = append(remainingInbounds, ic)
		}
	}
	if tunCount == 0 {
		return errors.New("configuration has no tun-in inbound")
	}
	if tunCount > 1 {
		return errors.New("configuration has multiple tun-in inbounds")
	}
	config.Inbound = remainingInbounds

	var tunCfg *xtun.Config
	if ps := tunInbound.GetProxySettings(); ps != nil {
		msg, err := ps.GetInstance()
		if err != nil {
			return err
		}
		var ok bool
		tunCfg, ok = msg.(*xtun.Config)
		if !ok {
			return errors.New("unexpected proxy settings type for tun-in inbound")
		}
	}
	if tunCfg == nil {
		tunCfg = &xtun.Config{}
	}

	// Avoid Go's typed-nil interface trap when passing TrafficGuard into tun.GuardedConfig.
	tunGuard := tun.NormalizeTrafficGuard(guard)

	// Read off the built configuration rather than passed in from Kotlin.
	tags := make(map[string]bool)
	for _, outbound := range config.GetOutbound() {
		if strings.HasPrefix(outbound.GetTag(), NodeTagPrefix) {
			tags[outbound.GetTag()] = true
		}
	}

	genCtx, _ := beginCoreLifecycle()
	started, err := core.New(config)
	if err != nil {
		return err
	}
	bindCoreInstance(started, genCtx, pinnedTag)

	rawTun, err := core.CreateObject(started, &tun.GuardedConfig{
		Tag:              tunInbound.GetTag(),
		ReceiverSettings: tunInbound.GetReceiverSettings(),
		ProxySettings:    tunInbound.GetProxySettings(),
		TunConfig:        tunCfg,
		TunFD:            tunFd,
		Endpoint:         epOverride,
		Guard:            tunGuard,
	})
	if err != nil {
		_ = started.Close()
		return err
	}
	tunHandler, ok := rawTun.(inbound.Handler)
	if !ok {
		_ = started.Close()
		return errors.New("guarded tun object does not implement inbound.Handler")
	}

	if pinnedTag != "" {
		if !tags[pinnedTag] {
			_ = started.Close()
			return errors.New("no outbound tagged " + pinnedTag + " in this configuration")
		}
		overrider, err := overriderFor(started)
		if err != nil {
			_ = started.Close()
			return err
		}
		if err := overrider.SetOverrideTarget(BalancerTag, pinnedTag); err != nil {
			_ = started.Close()
			return err
		}
	}

	if err := started.Start(); err != nil {
		_ = started.Close()
		return err
	}

	// After Start, not before: the core's own `app/log` registers a handler while the instance is
	// coming up (`log.RegisterHandler` keeps exactly one). Installing here ensures TUN startup
	// logs also flow into Android's bridgeHandler.
	xlog.RegisterHandler(bridgeHandler{})

	ibm, ok := started.GetFeature(inbound.ManagerType()).(inbound.Manager)
	if !ok || ibm == nil {
		_ = started.Close()
		return errors.New("the running configuration has no inbound manager")
	}
	if err := ibm.AddHandler(context.Background(), tunHandler); err != nil {
		_ = started.Close()
		return err
	}

	tagsMu.Lock()
	nodeTags = tags
	tagsMu.Unlock()

	instance = started
	success = true
	return nil
}

// Stop tears the core down. Safe to call when nothing is running, because Android will call it
// that way — onDestroy fires whether or not the service ever got as far as a tunnel.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()

	cancelCoreLifecycle()
	closeAllYumiGrpcConns()

	if instance == nil {
		protectorMu.Lock()
		protector = nil
		protectorMu.Unlock()
		return nil
	}
	err := instance.Close()
	instance = nil

	protectorMu.Lock()
	protector = nil
	protectorMu.Unlock()

	// Cleared with the core, so a [SelectOutbound] arriving after the tunnel went down is refused
	// on the tag rather than on the missing router.
	tagsMu.Lock()
	nodeTags = nil
	tagsMu.Unlock()

	return err
}

// IsRunning reports whether a core is up, so the service can tell a real tunnel from a stale
// notification without keeping a second copy of the same state in Kotlin.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return instance != nil
}

// SetAssetPath names the directory holding geoip.dat and geosite.dat.
func SetAssetPath(dir string) {
	os.Setenv(platform.NormalizeEnvName(platform.AssetLocation), dir)
}

// Version reports the core this .aar was built from.
func Version() string {
	return core.Version()
}

// --- In-process core.Dial bridge (replaces probe-in loopback SOCKS proxy) ---

func runningInstanceWithCtx() (*core.Instance, context.Context, error) {
	mu.Lock()
	running := instance
	mu.Unlock()
	if running == nil {
		return nil, nil, errors.New("core is not running")
	}
	coreLifecycleMu.RLock()
	genCtx := activeCoreCtx
	coreLifecycleMu.RUnlock()
	if genCtx == nil {
		genCtx = context.Background()
	}
	return running, genCtx, nil
}

// activeOutboundTag returns the balancer's pinned target, falling back to the first
// balancer candidate when unpinned, or "" if the config has no balancer.
func activeOutboundTag(running *core.Instance) string {
	router, ok := running.GetFeature(routing.RouterType()).(routing.Router)
	if !ok || router == nil {
		return ""
	}
	if overrider, ok := router.(routing.BalancerOverrider); ok {
		if target, err := overrider.GetOverrideTarget(BalancerTag); err == nil && target != "" {
			return target
		}
	}
	if pt, ok := router.(routing.BalancerPrincipleTarget); ok {
		if candidates, err := pt.GetPrincipleTarget(BalancerTag); err == nil && len(candidates) > 0 {
			return candidates[0]
		}
	}
	return ""
}

// dialActive dials addr ("host:port") in-process through the running core's active outbound.
// connCtx must remain alive for the lifetime of the returned net.Conn and is cancelled on Close.
func dialActive(
	connCtx context.Context,
	connCancel context.CancelFunc,
	dialCtx context.Context,
	running *core.Instance,
	network string,
	addr string,
	resolveDNS bool,
) (net.Conn, error) {
	if network == "" {
		network = "tcp"
	}
	tag := activeOutboundTag(running)
	content := &session.Content{SkipDNSResolve: true}
	ctx := session.ContextWithContent(connCtx, content)
	if tag != "" {
		ctx = session.SetForcedOutboundTagToContext(ctx, tag)
	}

	dialAddr := addr
	if resolveDNS {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, err
		}
		if net.ParseIP(host) == nil {
			dc, ok := running.GetFeature(xdns.ClientType()).(xdns.Client)
			if !ok || dc == nil {
				return nil, errors.New("no dns client in running configuration")
			}
			type dnsResult struct {
				ips []net.IP
				err error
			}
			ch := make(chan dnsResult, 1)
			go func() {
				ips, _, err := dc.LookupIP(host, xdns.IPOption{IPv4Enable: true, IPv6Enable: true})
				ch <- dnsResult{ips: ips, err: err}
			}()
			var ips []net.IP
			select {
			case <-dialCtx.Done():
				return nil, dialCtx.Err()
			case res := <-ch:
				if res.err != nil {
					return nil, res.err
				}
				ips = res.ips
			}
			chosen := pickResolvedIP(ips)
			if chosen == nil {
				return nil, errors.New("dns lookup returned no usable IP addresses")
			}
			dialAddr = net.JoinHostPort(chosen.String(), port)
		}
	}

	dest, err := v2net.ParseDestination(network + ":" + dialAddr)
	if err != nil {
		return nil, err
	}
	stop := context.AfterFunc(dialCtx, func() {
		connCancel()
	})
	raw, err := core.Dial(ctx, running, dest)
	if !stop() {
		if raw != nil {
			_ = raw.Close()
		}
		if dErr := dialCtx.Err(); dErr != nil {
			return nil, dErr
		}
	}
	return raw, err
}

func pickResolvedIP(ips []net.IP) net.IP {
	for _, ip := range ips {
		if v4 := ip.To4(); v4 != nil {
			return v4
		}
	}
	for _, ip := range ips {
		if len(ip) > 0 {
			return ip
		}
	}
	return nil
}

// deadlineConn wraps cnc.Connection (whose Set*Deadline methods are no-ops) to provide genuine
// per-operation idle timeouts and explicit net.Conn deadlines backed by timers + cancellation.
type deadlineConn struct {
	net.Conn
	cancel context.CancelFunc

	mu            sync.Mutex
	readDeadline  time.Time
	writeDeadline time.Time
	readTimer     *time.Timer
	writeTimer    *time.Timer
	idleRead      time.Duration
	idleWrite     time.Duration
	closed        atomic.Bool
	timedOut      atomic.Bool
}

func newDeadlineConn(raw net.Conn, cancel context.CancelFunc, idleRead, idleWrite time.Duration) *deadlineConn {
	return &deadlineConn{
		Conn:      raw,
		cancel:    cancel,
		idleRead:  idleRead,
		idleWrite: idleWrite,
	}
}

func (d *deadlineConn) setIdleTimeouts(idleRead, idleWrite time.Duration) {
	d.mu.Lock()
	d.idleRead = idleRead
	d.idleWrite = idleWrite
	d.mu.Unlock()
}

func (d *deadlineConn) onTimeout() {
	if d.closed.Load() {
		return
	}
	d.timedOut.Store(true)
	d.cancel()
	_ = d.Conn.Close()
}

func (d *deadlineConn) Read(b []byte) (int, error) {
	if d.timedOut.Load() {
		return 0, os.ErrDeadlineExceeded
	}
	d.mu.Lock()
	var opTimer *time.Timer
	if d.readDeadline.IsZero() && d.idleRead > 0 {
		opTimer = time.AfterFunc(d.idleRead, d.onTimeout)
	}
	d.mu.Unlock()

	n, err := d.Conn.Read(b)
	if opTimer != nil {
		opTimer.Stop()
	}
	if err != nil && d.timedOut.Load() {
		return n, os.ErrDeadlineExceeded
	}
	return n, err
}

func (d *deadlineConn) Write(b []byte) (int, error) {
	if d.timedOut.Load() {
		return 0, os.ErrDeadlineExceeded
	}
	d.mu.Lock()
	var opTimer *time.Timer
	if d.writeDeadline.IsZero() && d.idleWrite > 0 {
		opTimer = time.AfterFunc(d.idleWrite, d.onTimeout)
	}
	d.mu.Unlock()

	n, err := d.Conn.Write(b)
	if opTimer != nil {
		opTimer.Stop()
	}
	if err != nil && d.timedOut.Load() {
		return n, os.ErrDeadlineExceeded
	}
	return n, err
}

func (d *deadlineConn) SetDeadline(t time.Time) error {
	_ = d.SetReadDeadline(t)
	return d.SetWriteDeadline(t)
}

func (d *deadlineConn) SetReadDeadline(t time.Time) error {
	d.mu.Lock()
	if d.readTimer != nil {
		d.readTimer.Stop()
		d.readTimer = nil
	}
	d.readDeadline = t
	if !t.IsZero() && !d.closed.Load() {
		dur := time.Until(t)
		if dur <= 0 {
			d.mu.Unlock()
			d.onTimeout()
			return nil
		}
		d.readTimer = time.AfterFunc(dur, d.onTimeout)
	}
	d.mu.Unlock()
	return nil
}

func (d *deadlineConn) SetWriteDeadline(t time.Time) error {
	d.mu.Lock()
	if d.writeTimer != nil {
		d.writeTimer.Stop()
		d.writeTimer = nil
	}
	d.writeDeadline = t
	if !t.IsZero() && !d.closed.Load() {
		dur := time.Until(t)
		if dur <= 0 {
			d.mu.Unlock()
			d.onTimeout()
			return nil
		}
		d.writeTimer = time.AfterFunc(dur, d.onTimeout)
	}
	d.mu.Unlock()
	return nil
}

func (d *deadlineConn) Close() error {
	d.closed.Store(true)
	d.mu.Lock()
	if d.readTimer != nil {
		d.readTimer.Stop()
		d.readTimer = nil
	}
	if d.writeTimer != nil {
		d.writeTimer.Stop()
		d.writeTimer = nil
	}
	d.mu.Unlock()
	d.cancel()
	return d.Conn.Close()
}

// ProbeHTTP performs a single-hop non-redirecting HTTP GET through the running core's active outbound
// and returns the HTTP status code. When resolveDNS is true, hostnames are resolved through Xray's DNS client.
func ProbeHTTP(targetURL string, timeoutMillis int, resolveDNS bool) (int, error) {
	running, genCtx, err := runningInstanceWithCtx()
	if err != nil {
		return 0, err
	}

	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout <= 0 {
		timeout = 8 * time.Second
	}

	reqCtx, cancel := context.WithTimeout(genCtx, timeout)
	defer cancel()

	var connsMu sync.Mutex
	var conns []*deadlineConn

	tr := &http.Transport{
		Proxy: func(*http.Request) (*url.URL, error) { return nil, nil },
		DialContext: func(_ context.Context, network, addr string) (net.Conn, error) {
			connCtx, connCancel := context.WithCancel(reqCtx)
			raw, err := dialActive(connCtx, connCancel, reqCtx, running, network, addr, resolveDNS)
			if err != nil {
				connCancel()
				return nil, err
			}
			dc := newDeadlineConn(raw, connCancel, timeout, timeout)
			connsMu.Lock()
			conns = append(conns, dc)
			connsMu.Unlock()
			return dc, nil
		},
		TLSHandshakeTimeout:   timeout,
		ResponseHeaderTimeout: timeout,
		DisableKeepAlives:     true,
	}
	client := &http.Client{
		Transport:     tr,
		Timeout:       timeout,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	defer func() {
		client.CloseIdleConnections()
		connsMu.Lock()
		for _, dc := range conns {
			_ = dc.Close()
		}
		connsMu.Unlock()
	}()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, targetURL, nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("User-Agent", "Yumi")
	req.Header.Set("Connection", "close")

	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	if resp.Body != nil {
		_, _ = io.CopyN(io.Discard, resp.Body, 8192)
		_ = resp.Body.Close()
	}
	return resp.StatusCode, nil
}

// HTTPResponse represents a single-hop streaming HTTP response obtained via OpenHTTP.
type HTTPResponse struct {
	StatusCode      int
	ContentLength   int64
	ContentEncoding string
	Location        string
	HeadersJSON     string

	headers http.Header
	body    io.ReadCloser
	cancel  context.CancelFunc
	conn    *deadlineConn
	mu      sync.Mutex
	closed  bool
}

// OpenHTTP performs a single-hop non-redirecting HTTP request through the running core's active outbound.
// Callers must invoke Close() when finished reading the body.
func OpenHTTP(
	method string,
	targetURL string,
	headersJSON string,
	connectTimeoutMillis int,
	readTimeoutMillis int,
	resolveDNS bool,
) (*HTTPResponse, error) {
	running, genCtx, err := runningInstanceWithCtx()
	if err != nil {
		return nil, err
	}

	method = strings.TrimSpace(method)
	if method == "" {
		method = http.MethodGet
	}

	var parsedHeaders map[string]string
	if strings.TrimSpace(headersJSON) != "" {
		if err := json.Unmarshal([]byte(headersJSON), &parsedHeaders); err != nil {
			return nil, fmt.Errorf("invalid headersJSON: %w", err)
		}
	}

	connectTimeout := time.Duration(connectTimeoutMillis) * time.Millisecond
	if connectTimeout <= 0 {
		connectTimeout = 15 * time.Second
	}
	readTimeout := time.Duration(readTimeoutMillis) * time.Millisecond
	if readTimeout <= 0 {
		readTimeout = 30 * time.Second
	}

	connCtx, connCancel := context.WithCancel(genCtx)

	var dcMu sync.Mutex
	var activeDC *deadlineConn

	tr := &http.Transport{
		Proxy: func(*http.Request) (*url.URL, error) { return nil, nil },
		DialContext: func(_ context.Context, network, addr string) (net.Conn, error) {
			dCtx, dCancel := context.WithTimeout(connCtx, connectTimeout)
			defer dCancel()
			raw, err := dialActive(connCtx, connCancel, dCtx, running, network, addr, resolveDNS)
			if err != nil {
				return nil, err
			}
			dc := newDeadlineConn(raw, connCancel, connectTimeout, connectTimeout)
			dcMu.Lock()
			activeDC = dc
			dcMu.Unlock()
			return dc, nil
		},
		TLSHandshakeTimeout:   connectTimeout,
		ResponseHeaderTimeout: connectTimeout,
		DisableKeepAlives:     true,
	}

	req, err := http.NewRequestWithContext(connCtx, method, targetURL, nil)
	if err != nil {
		connCancel()
		return nil, err
	}
	for k, v := range parsedHeaders {
		if strings.EqualFold(k, "Host") {
			req.Host = v
		}
		req.Header.Set(k, v)
	}

	client := &http.Client{
		Transport:     tr,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	resp, err := client.Do(req)
	if err != nil {
		dcMu.Lock()
		dc := activeDC
		dcMu.Unlock()
		if dc != nil {
			_ = dc.Close()
		}
		connCancel()
		return nil, err
	}

	dcMu.Lock()
	dc := activeDC
	dcMu.Unlock()
	if dc != nil {
		dc.setIdleTimeouts(readTimeout, readTimeout)
	}

	flatHeaders := make(map[string]string, len(resp.Header))
	for k, vals := range resp.Header {
		flatHeaders[strings.ToLower(k)] = strings.Join(vals, ", ")
	}
	encodedHeaders, err := json.Marshal(flatHeaders)
	if err != nil {
		encodedHeaders = []byte("{}")
	}

	return &HTTPResponse{
		StatusCode:      resp.StatusCode,
		ContentLength:   resp.ContentLength,
		ContentEncoding: resp.Header.Get("Content-Encoding"),
		Location:        resp.Header.Get("Location"),
		HeadersJSON:     string(encodedHeaders),
		headers:         resp.Header,
		body:            resp.Body,
		cancel:          connCancel,
		conn:            dc,
	}, nil
}

// Header returns the case-insensitive response header value, or "" if absent.
func (r *HTTPResponse) Header(name string) string {
	if r == nil || r.headers == nil {
		return ""
	}
	return r.headers.Get(name)
}

// Read reads up to maxBytes from the response body, returning (nil, nil) on EOF.
func (r *HTTPResponse) Read(maxBytes int) ([]byte, error) {
	if r == nil {
		return nil, errors.New("nil HTTPResponse")
	}
	if maxBytes <= 0 {
		maxBytes = 65536
	} else if maxBytes > 1<<20 {
		maxBytes = 1 << 20
	}

	r.mu.Lock()
	closed := r.closed
	body := r.body
	r.mu.Unlock()
	if closed || body == nil {
		return nil, nil
	}

	buf := make([]byte, maxBytes)
	n, err := body.Read(buf)
	if n > 0 {
		return buf[:n], nil
	}
	if errors.Is(err, io.EOF) {
		return nil, nil
	}
	return nil, err
}

// ReadAll reads the remaining response body up to maxBytes (all bytes if maxBytes <= 0).
func (r *HTTPResponse) ReadAll(maxBytes int64) ([]byte, error) {
	if r == nil {
		return nil, errors.New("nil HTTPResponse")
	}
	r.mu.Lock()
	closed := r.closed
	body := r.body
	r.mu.Unlock()
	if closed || body == nil {
		return nil, nil
	}
	if maxBytes > 0 {
		return io.ReadAll(io.LimitReader(body, maxBytes))
	}
	return io.ReadAll(body)
}

// Close closes the response body and underlying connection.
func (r *HTTPResponse) Close() error {
	if r == nil {
		return nil
	}
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return nil
	}
	r.closed = true
	body := r.body
	dc := r.conn
	cancel := r.cancel
	r.mu.Unlock()

	var err error
	if body != nil {
		err = body.Close()
	}
	if dc != nil {
		_ = dc.Close()
	}
	if cancel != nil {
		cancel()
	}
	return err
}

// TunnelConn wraps a bidirectional stream dialled in-process through Xray's active outbound.
type TunnelConn struct {
	conn    net.Conn
	rawConn *deadlineConn
	cancel  context.CancelFunc

	mu     sync.Mutex
	closed bool
}

// DialTunnel opens a raw TCP or UDP stream through the running core's active outbound.
func DialTunnel(network string, addr string, timeoutMillis int) (*TunnelConn, error) {
	running, genCtx, err := runningInstanceWithCtx()
	if err != nil {
		return nil, err
	}
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout <= 0 {
		timeout = 6 * time.Second
	}

	connCtx, cancel := context.WithCancel(genCtx)
	dialCtx, dialCancel := context.WithTimeout(connCtx, timeout)
	defer dialCancel()

	raw, err := dialActive(connCtx, cancel, dialCtx, running, network, addr, false)
	if err != nil {
		cancel()
		return nil, err
	}
	dc := newDeadlineConn(raw, cancel, 0, 0)
	return &TunnelConn{
		conn:    dc,
		rawConn: dc,
		cancel:  cancel,
	}, nil
}

// DialTunnelTLS opens a TLS-over-TCP stream through the running core's active outbound.
func DialTunnelTLS(host string, port int, serverName string, timeoutMillis int) (*TunnelConn, error) {
	running, genCtx, err := runningInstanceWithCtx()
	if err != nil {
		return nil, err
	}
	if host == "" {
		return nil, errors.New("host must not be empty")
	}
	if port <= 0 || port > 65535 {
		return nil, errors.New("invalid port")
	}

	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout <= 0 {
		timeout = 6 * time.Second
	}

	connCtx, cancel := context.WithCancel(genCtx)
	dialCtx, dialCancel := context.WithTimeout(connCtx, timeout)
	defer dialCancel()

	addr := net.JoinHostPort(host, strconv.Itoa(port))
	raw, err := dialActive(connCtx, cancel, dialCtx, running, "tcp", addr, false)
	if err != nil {
		cancel()
		return nil, err
	}
	dc := newDeadlineConn(raw, cancel, 0, 0)

	sni := serverName
	if sni == "" {
		sni = host
	}
	_ = dc.SetDeadline(time.Now().Add(timeout))
	tlsConn := tls.Client(dc, &tls.Config{
		ServerName: sni,
		MinVersion: tls.VersionTLS12,
	})
	if err := tlsConn.HandshakeContext(dialCtx); err != nil {
		_ = dc.Close()
		return nil, err
	}
	_ = dc.SetDeadline(time.Time{})

	return &TunnelConn{
		conn:    tlsConn,
		rawConn: dc,
		cancel:  cancel,
	}, nil
}

// SetReadTimeout sets the per-operation idle read timeout in milliseconds (<= 0 disables).
func (c *TunnelConn) SetReadTimeout(timeoutMillis int) {
	if c == nil || c.rawConn == nil {
		return
	}
	d := time.Duration(timeoutMillis) * time.Millisecond
	if d < 0 {
		d = 0
	}
	c.rawConn.mu.Lock()
	c.rawConn.idleRead = d
	c.rawConn.mu.Unlock()
}

// SetWriteTimeout sets the per-operation idle write timeout in milliseconds (<= 0 disables).
func (c *TunnelConn) SetWriteTimeout(timeoutMillis int) {
	if c == nil || c.rawConn == nil {
		return
	}
	d := time.Duration(timeoutMillis) * time.Millisecond
	if d < 0 {
		d = 0
	}
	c.rawConn.mu.Lock()
	c.rawConn.idleWrite = d
	c.rawConn.mu.Unlock()
}

// Read reads up to maxBytes from the stream, returning (nil, nil) on EOF.
func (c *TunnelConn) Read(maxBytes int) ([]byte, error) {
	if c == nil || c.conn == nil {
		return nil, errors.New("nil TunnelConn")
	}
	if maxBytes <= 0 {
		maxBytes = 65536
	} else if maxBytes > 1<<20 {
		maxBytes = 1 << 20
	}
	buf := make([]byte, maxBytes)
	n, err := c.conn.Read(buf)
	if n > 0 {
		return buf[:n], nil
	}
	if errors.Is(err, io.EOF) {
		return nil, nil
	}
	return nil, err
}

// Write writes data to the stream.
func (c *TunnelConn) Write(data []byte) (int, error) {
	if c == nil || c.conn == nil {
		return 0, errors.New("nil TunnelConn")
	}
	return c.conn.Write(data)
}

// Close closes the underlying transport immediately without waiting on TLS close-notify.
func (c *TunnelConn) Close() error {
	if c == nil {
		return nil
	}
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	raw := c.rawConn
	cancel := c.cancel
	c.mu.Unlock()

	var err error
	if raw != nil {
		err = raw.Close()
	} else if c.conn != nil {
		err = c.conn.Close()
	}
	if cancel != nil {
		cancel()
	}
	return err
}
