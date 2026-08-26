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
	"errors"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"

	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/core"
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

// Start brings the core up on the given configuration.
//
// tunFd is the descriptor from VpnService.Builder.establish(). Ownership stays with the app: the
// core reads and writes it but never closes it, and closing it here would take the tunnel down
// under a Stop that was meant to be followed by another Start.
func Start(configJSON string, tunFd int, p Protector) error {
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
