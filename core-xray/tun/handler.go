package tun

import (
	"context"
	"net"
	"sync"

	"github.com/xtls/xray-core/app/proxyman"
	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/buf"
	c "github.com/xtls/xray-core/common/ctx"
	"github.com/xtls/xray-core/common/errors"
	"github.com/xtls/xray-core/common/log"
	v2net "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/inbound"
	"github.com/xtls/xray-core/features/policy"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/features/stats"
	xtun "github.com/xtls/xray-core/proxy/tun"
	"github.com/xtls/xray-core/transport"
	"github.com/xtls/xray-core/transport/internet/stat"
)

// GuardedHandler implements inbound.Handler for the guarded TUN stack.
type GuardedHandler struct {
	ctx             context.Context
	cancel          context.CancelFunc
	config          *GuardedConfig
	policyManager   policy.Manager
	dispatcher      routing.Dispatcher
	sniffingRequest session.SniffingRequest
	uplinkCounter   stats.Counter
	downlinkCounter stats.Counter

	mu    sync.Mutex
	stack *stackGVisor
}

var _ inbound.Handler = (*GuardedHandler)(nil)

func init() {
	common.Must(common.RegisterConfig((*GuardedConfig)(nil), func(ctx context.Context, rawCfg interface{}) (interface{}, error) {
		cfg, ok := rawCfg.(*GuardedConfig)
		if !ok || cfg == nil {
			return nil, errors.New("invalid guarded tun config")
		}
		cfgCopy := *cfg
		cfgCopy.Guard = NormalizeTrafficGuard(cfg.Guard)
		h := &GuardedHandler{config: &cfgCopy}
		if err := h.initSniffing(); err != nil {
			return nil, err
		}
		err := core.RequireFeatures(ctx, func(pm policy.Manager, dispatcher routing.Dispatcher) error {
			return h.init(ctx, pm, dispatcher)
		})
		return h, err
	}))
}

func (h *GuardedHandler) initSniffing() error {
	if h.config.ReceiverSettings == nil {
		return nil
	}
	msg, err := h.config.ReceiverSettings.GetInstance()
	if err != nil {
		return err
	}
	rc, ok := msg.(*proxyman.ReceiverConfig)
	if !ok || rc.SniffingSettings == nil {
		return nil
	}
	sr, err := proxyman.BuildSniffingRequest(rc.SniffingSettings)
	if err != nil {
		return err
	}
	h.sniffingRequest = sr
	return nil
}

func (h *GuardedHandler) init(ctx context.Context, pm policy.Manager, dispatcher routing.Dispatcher) error {
	h.ctx, h.cancel = context.WithCancel(core.ToBackgroundDetachedContext(ctx))
	h.policyManager = pm
	h.dispatcher = dispatcher

	tag := h.config.Tag
	if len(tag) > 0 && pm.ForSystem().Stats.InboundUplink {
		if sm, ok := core.MustFromContext(ctx).GetFeature(stats.ManagerType()).(stats.Manager); ok && sm != nil {
			if c, _ := sm.GetOrRegisterCounter("inbound>>>" + tag + ">>>traffic>>>uplink"); c != nil {
				h.uplinkCounter = c
			}
		}
	}
	if len(tag) > 0 && pm.ForSystem().Stats.InboundDownlink {
		if sm, ok := core.MustFromContext(ctx).GetFeature(stats.ManagerType()).(stats.Manager); ok && sm != nil {
			if c, _ := sm.GetOrRegisterCounter("inbound>>>" + tag + ">>>traffic>>>downlink"); c != nil {
				h.downlinkCounter = c
			}
		}
	}
	return nil
}

// Tag returns the configured inbound tag (typically "tun-in").
func (h *GuardedHandler) Tag() string {
	if h.config == nil {
		return ""
	}
	return h.config.Tag
}

// ReceiverSettings returns the preserved ReceiverSettings protobuf wrapper.
func (h *GuardedHandler) ReceiverSettings() *serial.TypedMessage {
	if h.config == nil {
		return nil
	}
	return h.config.ReceiverSettings
}

// ProxySettings returns the preserved ProxySettings protobuf wrapper.
func (h *GuardedHandler) ProxySettings() *serial.TypedMessage {
	if h.config == nil {
		return nil
	}
	return h.config.ProxySettings
}

// Start brings up the gVisor TUN stack.
func (h *GuardedHandler) Start() error {
	h.mu.Lock()
	defer h.mu.Unlock()

	if h.stack != nil {
		return nil
	}

	tunCfg := h.config.TunConfig
	if tunCfg == nil {
		tunCfg = &xtun.Config{}
	}
	mtu := tunCfg.GetMTU()
	if mtu == 0 {
		mtu = 1500
	}

	ep := h.config.Endpoint
	if ep == nil {
		var err error
		ep, err = NewEndpointFromFD(h.config.TunFD, mtu)
		if err != nil {
			return err
		}
	}

	idleTimeout := h.policyManager.ForLevel(tunCfg.GetUserLevel()).Timeouts.ConnectionIdle
	s, err := NewStack(h.ctx, StackOptions{
		Endpoint:    ep,
		IdleTimeout: idleTimeout,
		Guard:       NormalizeTrafficGuard(h.config.Guard),
	}, h.HandleConnection)
	if err != nil {
		return err
	}
	if err := s.Start(); err != nil {
		_ = s.Close()
		return err
	}
	h.stack = s
	errors.LogInfo(h.ctx, tunCfg.GetName(), " guarded tun up")
	return nil
}

// Close shuts down the gVisor stack cleanly without closing the caller-owned TunFD.
func (h *GuardedHandler) Close() error {
	h.mu.Lock()
	cancel := h.cancel
	s := h.stack
	h.stack = nil
	h.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	if s == nil {
		return nil
	}
	return s.Close()
}

// HandleConnection dispatches an accepted TCP or UDP flow to Xray's routing dispatcher.
func (h *GuardedHandler) HandleConnection(conn net.Conn, destination v2net.Destination) {
	defer conn.Close()

	ctx, cancel := context.WithCancel(h.ctx)
	defer cancel()
	ctx = c.ContextWithID(ctx, session.NewID())

	remote := conn.RemoteAddr()
	if remote == nil {
		errors.LogInfo(h.ctx, "dropped quickly closed connection")
		return
	}
	source := v2net.DestinationFromAddr(remote)
	if h.uplinkCounter != nil || h.downlinkCounter != nil {
		conn = &stat.CounterConnection{
			Connection:   conn,
			ReadCounter:  h.uplinkCounter,
			WriteCounter: h.downlinkCounter,
		}
	}

	var userLevel uint32
	if h.config.TunConfig != nil {
		userLevel = h.config.TunConfig.GetUserLevel()
	}

	inboundMeta := session.Inbound{
		Name:          "tun",
		Tag:           h.config.Tag,
		CanSpliceCopy: 3,
		Source:        source,
		User: &protocol.MemoryUser{
			Level: userLevel,
		},
	}

	ctx = session.ContextWithInbound(ctx, &inboundMeta)
	ctx = session.ContextWithContent(ctx, &session.Content{
		SniffingRequest: h.sniffingRequest,
	})
	ctx = session.SubContextFromMuxInbound(ctx)

	ctx = log.ContextWithAccessMessage(ctx, &log.AccessMessage{
		From:   inboundMeta.Source,
		To:     destination,
		Status: log.AccessAccepted,
		Reason: "",
	})
	errors.LogInfo(ctx, "processing from ", source, " to ", destination)

	link := &transport.Link{
		Reader: &buf.TimeoutWrapperReader{Reader: buf.NewReader(conn)},
		Writer: buf.NewWriter(conn),
	}
	if err := h.dispatcher.DispatchLink(ctx, destination, link); err != nil {
		errors.LogError(ctx, errors.New("connection closed").Base(err))
	}
}
