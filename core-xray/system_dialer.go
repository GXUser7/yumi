package yumi

import (
	"context"
	"errors"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"

	v2net "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/transport/internet"
)

const (
	// yumiTCPConnectTimeout caps raw TCP connect dials at 8s instead of upstream Xray's 16s so dead
	// nodes and fragment retry loops fail promptly on Android.
	yumiTCPConnectTimeout = 8 * time.Second
	maxTrackedInstances   = 32
)

// yumiGenCtxKey preserves the owning core generation context across context.WithoutCancel inside
// Xray's dialerProxy redirect().
type yumiGenCtxKey struct{}

var (
	systemDialerOnce sync.Once

	coreLifecycleMu    sync.RWMutex
	activeCoreCtx      context.Context
	activeCoreCancel   context.CancelFunc
	activeCoreInstance *core.Instance
	activePinnedTag    string
	yumiInstances      = make(map[*core.Instance]context.Context)
	yumiInstanceOrder  []*core.Instance
)

func installSystemDialer() {
	internet.UseAlternativeSystemDialer(&yumiSystemDialer{})
}

func beginCoreLifecycle() (context.Context, context.CancelFunc) {
	ctx, cancel := context.WithCancel(context.Background())
	coreLifecycleMu.Lock()
	if activeCoreCancel != nil {
		activeCoreCancel()
	}
	activeCoreCtx = ctx
	activeCoreCancel = cancel
	activeCoreInstance = nil
	activePinnedTag = ""
	coreLifecycleMu.Unlock()
	return ctx, cancel
}

func bindCoreInstance(inst *core.Instance, genCtx context.Context, pinnedTag string) {
	if inst == nil || genCtx == nil {
		return
	}
	coreLifecycleMu.Lock()
	activeCoreInstance = inst
	activePinnedTag = pinnedTag
	yumiInstances[inst] = genCtx
	yumiInstanceOrder = append(yumiInstanceOrder, inst)
	if len(yumiInstanceOrder) > maxTrackedInstances {
		drop := len(yumiInstanceOrder) - maxTrackedInstances
		for i := 0; i < drop; i++ {
			delete(yumiInstances, yumiInstanceOrder[i])
			yumiInstanceOrder[i] = nil
		}
		yumiInstanceOrder = append([]*core.Instance(nil), yumiInstanceOrder[drop:]...)
	}
	coreLifecycleMu.Unlock()
}

func setCorePinnedTag(tag string) {
	coreLifecycleMu.Lock()
	activePinnedTag = tag
	coreLifecycleMu.Unlock()
}

func currentPinnedTag() string {
	coreLifecycleMu.RLock()
	tag := activePinnedTag
	coreLifecycleMu.RUnlock()
	return tag
}

func cancelCoreLifecycle() context.Context {
	coreLifecycleMu.Lock()
	ctx := activeCoreCtx
	cancel := activeCoreCancel
	activeCoreCtx = nil
	activeCoreCancel = nil
	activeCoreInstance = nil
	activePinnedTag = ""
	coreLifecycleMu.Unlock()
	if cancel != nil {
		cancel()
	}
	return ctx
}

func isActiveCoreCtx(ctx context.Context) bool {
	if ctx == nil {
		return false
	}
	coreLifecycleMu.RLock()
	active := activeCoreCtx == ctx
	coreLifecycleMu.RUnlock()
	return active
}

func withGenCtxValue(ctx context.Context, genCtx context.Context) context.Context {
	if genCtx == nil {
		return ctx
	}
	return context.WithValue(ctx, yumiGenCtxKey{}, genCtx)
}

func genCtxForDial(ctx context.Context) context.Context {
	if gc, ok := ctx.Value(yumiGenCtxKey{}).(context.Context); ok && gc != nil {
		return gc
	}
	if inst := core.FromContext(ctx); inst != nil {
		coreLifecycleMu.RLock()
		gc := yumiInstances[inst]
		coreLifecycleMu.RUnlock()
		// Instances not started via yumi (e.g. standalone core.New tests) return nil so their
		// lifecycle is untouched.
		return gc
	}
	coreLifecycleMu.RLock()
	gc := activeCoreCtx
	coreLifecycleMu.RUnlock()
	return gc
}

type yumiSystemDialer struct{}

func (*yumiSystemDialer) DestIpAddress() v2net.IP {
	return nil
}

func resolveLocalAddr(network v2net.Network, src v2net.Address) net.Addr {
	if src == nil || !src.Family().IsIP() {
		return nil
	}
	ip := src.IP()
	if len(ip) == 0 || ip.IsUnspecified() {
		return nil
	}
	if network == v2net.Network_TCP {
		return &net.TCPAddr{IP: ip, Port: 0}
	}
	return &net.UDPAddr{IP: ip, Port: 0}
}

func protectControl(network, address string, c syscall.RawConn) error {
	protectorMu.RLock()
	p := protector
	protectorMu.RUnlock()
	isTCP := strings.HasPrefix(network, "tcp")
	if p != nil || isTCP {
		refused := false
		// The fd is only valid inside Control; calling Protect later would target a recycled fd.
		if err := c.Control(func(fd uintptr) {
			if p != nil {
				refused = !p.Protect(int(fd))
			}
			if !refused && isTCP {
				setTcpSynRetries(fd)
			}
		}); err != nil {
			return err
		}
		if refused {
			return errors.New("VpnService.protect refused the socket")
		}
	}

	internet.ControllersLock.Lock()
	controllers := append([]func(network, address string, c syscall.RawConn) error(nil), internet.Controllers...)
	internet.ControllersLock.Unlock()
	for _, ctl := range controllers {
		if err := ctl(network, address, c); err != nil {
			return err
		}
	}
	return nil
}

func (*yumiSystemDialer) Dial(
	ctx context.Context,
	src v2net.Address,
	dest v2net.Destination,
	sockopt *internet.SocketConfig,
) (net.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	dialCtx := ctx
	if genCtx := genCtxForDial(ctx); genCtx != nil {
		if err := genCtx.Err(); err != nil {
			return nil, err
		}
		var cancel context.CancelFunc
		dialCtx, cancel = context.WithCancel(ctx)
		defer cancel()
		stop := context.AfterFunc(genCtx, cancel)
		defer stop()
	}

	if dest.Network == v2net.Network_UDP {
		destAddr, err := net.ResolveUDPAddr("udp", dest.NetAddr())
		if err != nil {
			return nil, err
		}
		srcAddr := resolveLocalAddr(v2net.Network_UDP, src)
		if srcAddr == nil {
			if destAddr.IP != nil && destAddr.IP.To4() == nil {
				srcAddr = &net.UDPAddr{IP: net.IPv6zero, Port: 0}
			} else {
				srcAddr = &net.UDPAddr{IP: net.IPv4zero, Port: 0}
			}
		}
		lc := net.ListenConfig{Control: protectControl}
		packetConn, err := lc.ListenPacket(dialCtx, srcAddr.Network(), srcAddr.String())
		if err != nil {
			return nil, err
		}
		return &internet.PacketConnWrapper{
			PacketConn: packetConn,
			Dest:       destAddr,
		}, nil
	}

	keepAliveConfig := net.KeepAliveConfig{
		Enable:   true,
		Idle:     45 * time.Second,
		Interval: 45 * time.Second,
		Count:    -1,
	}
	keepAlive := time.Duration(0)
	if sockopt != nil {
		if sockopt.TcpKeepAliveIdle*sockopt.TcpKeepAliveInterval < 0 {
			return nil, errors.New("invalid TcpKeepAliveIdle or TcpKeepAliveInterval value")
		}
		if sockopt.TcpKeepAliveIdle < 0 || sockopt.TcpKeepAliveInterval < 0 {
			keepAlive = -1
			keepAliveConfig.Enable = false
		}
		if sockopt.TcpKeepAliveIdle > 0 {
			keepAliveConfig.Idle = time.Duration(sockopt.TcpKeepAliveIdle) * time.Second
		}
		if sockopt.TcpKeepAliveInterval > 0 {
			keepAliveConfig.Interval = time.Duration(sockopt.TcpKeepAliveInterval) * time.Second
		}
	}

	dialer := &net.Dialer{
		Timeout:         yumiTCPConnectTimeout,
		LocalAddr:       resolveLocalAddr(dest.Network, src),
		KeepAlive:       keepAlive,
		KeepAliveConfig: keepAliveConfig,
		Control:         protectControl,
	}
	if sockopt != nil && sockopt.TcpMptcp {
		dialer.SetMultipathTCP(true)
	}
	return dialer.DialContext(dialCtx, dest.Network.SystemString(), dest.NetAddr())
}
