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
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	xlog "github.com/xtls/xray-core/common/log"
	v2net "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/features/stats"
	"github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/transport/internet"

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
	// RegisterDialerController appends to a package-level slice in the core and offers no way to
	// remove an entry again (transport/internet/system_dialer.go:206). Registering per Start would
	// therefore stack one controller per connection the user has ever made. So the controller is
	// installed once for the life of the process and reads whichever protector is current.
	protectorOnce sync.Once
	protectorMu   sync.RWMutex
	protector     Protector
)

func installProtector() {
	_ = internet.RegisterDialerController(func(network, address string, c syscall.RawConn) error {
		protectorMu.RLock()
		p := protector
		protectorMu.RUnlock()
		if p == nil {
			return nil
		}
		refused := false
		// The fd is only valid inside Control; taking it out and calling later protects a number
		// that may by then belong to something else entirely.
		if err := c.Control(func(fd uintptr) {
			refused = !p.Protect(int(fd))
		}); err != nil {
			return err
		}
		if refused {
			return errors.New("VpnService.protect refused the socket")
		}
		return nil
	})
}

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
	return overrider.SetOverrideTarget(BalancerTag, tag)
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
	mu.Lock()
	running := instance
	mu.Unlock()
	if running == nil {
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
			results <- result{tag: tag, delay: measureOne(running, tag, probeURL, timeout)}
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
func measureOne(running *core.Instance, tag string, probeURL string, timeout time.Duration) int64 {
	// SkipDNSResolve leaves the name for the *server* to resolve, which is the whole point: the
	// probe has to succeed on a phone whose own resolver is dead, or a broken resolver would read
	// as every server in the subscription being broken at once.
	content := new(session.Content)
	content.SkipDNSResolve = true
	ctx := session.ContextWithContent(context.Background(), content)
	ctx = session.SetForcedOutboundTagToContext(ctx, tag)

	client := &http.Client{
		Transport: &http.Transport{
			Proxy: func(*http.Request) (*url.URL, error) { return nil, nil },
			DialContext: func(_ context.Context, network, addr string) (net.Conn, error) {
				dest, err := v2net.ParseDestination(network + ":" + addr)
				if err != nil {
					return nil, err
				}
				// core.Dial is the exported, stable entry point; it puts the instance into the
				// context the dispatcher needs while leaving the forced tag above in place.
				return core.Dial(ctx, running, dest)
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

	request, err := http.NewRequest(http.MethodGet, probeURL, nil)
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

// Start brings the core up on the given configuration.
//
// tunFd is the descriptor from VpnService.Builder.establish(). Ownership stays with the app: the
// core reads and writes it but never closes it, and closing it here would take the tunnel down
// under a Stop that was meant to be followed by another Start.
//
// pinnedTag is the node the balancer must be pointed at before this call returns, and pinning it
// here rather than from Kotlin is not tidiness. An unpinned balancer picks for itself, so a core
// that starts and is pinned a moment later routes whatever it managed to dispatch in between
// through a server nobody chose. Empty leaves the balancer to its own strategy, which is only
// right when the configuration has no balancer at all.
func Start(configJSON string, tunFd int, pinnedTag string, p Protector) error {
	mu.Lock()
	defer mu.Unlock()

	if instance != nil {
		return errors.New("core is already running")
	}
	if tunFd <= 0 {
		return errors.New("refusing to start without a tunnel descriptor")
	}

	protectorOnce.Do(installProtector)
	protectorMu.Lock()
	protector = p
	protectorMu.Unlock()

	// The TUN inbound reads this while it is being constructed, which happens inside core.New —
	// so it has to be set before the config is turned into an instance, not before Start.
	//
	// The normalized spelling is used rather than the literal "xray.tun.fd": NewEnvFlag looks both
	// up (common/platform/platform.go:36-39), and a variable name containing dots is the kind of
	// thing that works until something in the chain decides otherwise.
	os.Setenv(platform.NormalizeEnvName(platform.TunFdKey), strconv.Itoa(tunFd))

	config, err := serial.LoadJSONConfig(strings.NewReader(configJSON))
	if err != nil {
		return err
	}

	// Read off the built configuration rather than passed in from Kotlin. The list has to describe
	// what the core actually holds, and the only way to be sure of that is to read what the core
	// was handed — a list assembled separately drifts the first time one of the two is edited.
	tags := make(map[string]bool)
	for _, outbound := range config.GetOutbound() {
		if strings.HasPrefix(outbound.GetTag(), NodeTagPrefix) {
			tags[outbound.GetTag()] = true
		}
	}

	started, err := core.New(config)
	if err != nil {
		return err
	}
	if err := started.Start(); err != nil {
		// Otherwise a core that failed halfway through Start stays half-alive with its listeners
		// bound, and the next attempt fails on the address instead of on the real reason.
		_ = started.Close()
		return err
	}

	// After Start, not before: the core's own `app/log` registers a handler while the instance is
	// coming up (`log.RegisterHandler` keeps exactly one), so installing this any earlier means
	// installing it and then losing it. The cost is that whatever the core says while starting
	// goes to its own logger; the app is not listening yet anyway.
	xlog.RegisterHandler(bridgeHandler{})

	tagsMu.Lock()
	nodeTags = tags
	tagsMu.Unlock()

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

	instance = started
	return nil
}

// Stop tears the core down. Safe to call when nothing is running, because Android will call it
// that way — onDestroy fires whether or not the service ever got as far as a tunnel.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()

	if instance == nil {
		return nil
	}
	err := instance.Close()
	instance = nil

	protectorMu.Lock()
	protector = nil
	protectorMu.Unlock()

	// Cleared with the core, so a [SelectOutbound] arriving after the tunnel went down is refused
	// on the tag rather than on the missing router — the first says which server, the second does
	// not, and the app puts the message in its journal either way.
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
//
// Routing rules written as `geoip:ru` or `geosite:category-ads` are resolved by reading those files
// at config-parse time, and when they are missing the whole configuration is rejected rather than
// the individual rule being skipped. So this must be set before Start, and the caller has to have
// put the files there.
func SetAssetPath(dir string) {
	os.Setenv(platform.NormalizeEnvName(platform.AssetLocation), dir)
}

// Version reports the core this .aar was built from, so a phone in the field can be matched to a
// revision without trusting the file name of whatever was installed on it.
func Version() string {
	return core.Version()
}
