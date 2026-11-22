package yumi

import (
	"context"
	"errors"
	"net"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/xtls/xray-core/app/proxyman"
	"github.com/xtls/xray-core/common"
	xctx "github.com/xtls/xray-core/common/ctx"
	v2net "github.com/xtls/xray-core/common/net"
	cserial "github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/common/utils"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/transport/internet"
	xgrpc "github.com/xtls/xray-core/transport/internet/grpc"
	"github.com/xtls/xray-core/transport/internet/grpc/encoding"
	"github.com/xtls/xray-core/transport/internet/reality"
	"github.com/xtls/xray-core/transport/internet/stat"
	xtls "github.com/xtls/xray-core/transport/internet/tls"
	"google.golang.org/grpc"
	"google.golang.org/grpc/backoff"
	"google.golang.org/grpc/connectivity"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/keepalive"
)

const yumiGrpcProtocolName = "yumi-grpc"

type yumiMeasureCtxKey struct{}

func withMeasureProbeCtx(ctx context.Context) context.Context {
	return context.WithValue(ctx, yumiMeasureCtxKey{}, true)
}

func isMeasureProbeCtx(ctx context.Context) bool {
	v, _ := ctx.Value(yumiMeasureCtxKey{}).(bool)
	return v
}

type yumiGrpcKey struct {
	dest   v2net.Destination
	stream *internet.MemoryStreamConfig
}

type yumiGrpcEntry struct {
	key           yumiGrpcKey
	conn          *grpc.ClientConn
	genCtx        context.Context
	outboundTag   string
	activeStreams int
	dialing       int
	closed        bool
}

var (
	yumiGrpcMu  sync.Mutex
	yumiGrpcMap = make(map[yumiGrpcKey]*yumiGrpcEntry)
)

func init() {
	common.Must(internet.RegisterProtocolConfigCreator(yumiGrpcProtocolName, func() interface{} {
		return new(xgrpc.Config)
	}))
	common.Must(internet.RegisterTransportDialer(yumiGrpcProtocolName, dialYumiGRPC))
}

// rewriteGrpcOutbounds routes every "grpc" outbound stream through yumi's managed gRPC transport
// dialer so idle/failed ClientConns are closed on failed probes, outbound switches, and Stop().
func rewriteGrpcOutbounds(config *core.Config) error {
	if config == nil {
		return nil
	}
	for _, ob := range config.GetOutbound() {
		if ob == nil || ob.SenderSettings == nil {
			continue
		}
		msg, err := ob.SenderSettings.GetInstance()
		if err != nil {
			return err
		}
		senderCfg, ok := msg.(*proxyman.SenderConfig)
		if !ok || senderCfg == nil || senderCfg.StreamSettings == nil {
			continue
		}
		changed := false
		if senderCfg.StreamSettings.ProtocolName == "grpc" {
			senderCfg.StreamSettings.ProtocolName = yumiGrpcProtocolName
			changed = true
		}
		for _, ts := range senderCfg.StreamSettings.TransportSettings {
			if ts != nil && ts.ProtocolName == "grpc" {
				ts.ProtocolName = yumiGrpcProtocolName
				changed = true
			}
		}
		if changed {
			ob.SenderSettings = cserial.ToTypedMessage(senderCfg)
		}
	}
	return nil
}

func yumiGrpcServiceName(c *xgrpc.Config) string {
	if !strings.HasPrefix(c.ServiceName, "/") {
		return url.PathEscape(c.ServiceName)
	}
	lastIndex := strings.LastIndex(c.ServiceName, "/")
	if lastIndex < 1 {
		lastIndex = 1
	}
	rawServiceName := c.ServiceName[1:lastIndex]
	parts := strings.Split(rawServiceName, "/")
	for i := range parts {
		parts[i] = url.PathEscape(parts[i])
	}
	return strings.Join(parts, "/")
}

func yumiGrpcTunStreamName(c *xgrpc.Config) string {
	if !strings.HasPrefix(c.ServiceName, "/") {
		return "Tun"
	}
	endingPath := c.ServiceName[strings.LastIndex(c.ServiceName, "/")+1:]
	return url.PathEscape(strings.Split(endingPath, "|")[0])
}

func yumiGrpcTunMultiStreamName(c *xgrpc.Config) string {
	if !strings.HasPrefix(c.ServiceName, "/") {
		return "TunMulti"
	}
	endingPath := c.ServiceName[strings.LastIndex(c.ServiceName, "/")+1:]
	streamNames := strings.Split(endingPath, "|")
	if len(streamNames) == 1 {
		return url.PathEscape(streamNames[0])
	}
	return url.PathEscape(streamNames[1])
}

func outboundTagFromContext(ctx context.Context) string {
	outbounds := session.OutboundsFromContext(ctx)
	for i := len(outbounds) - 1; i >= 0; i-- {
		if outbounds[i] != nil && outbounds[i].Tag != "" {
			return outbounds[i].Tag
		}
	}
	return session.GetForcedOutboundTagFromContext(ctx)
}

func resolveGrpcUserAgent(ua string) string {
	switch ua {
	case "chrome", "":
		return utils.ChromeUA
	case "firefox":
		return utils.FirefoxUA
	case "edge":
		return utils.MSEdgeUA
	case "golang":
		return ""
	default:
		return ua
	}
}

func acquireYumiGrpcEntry(
	ctx context.Context,
	genCtx context.Context,
	dest v2net.Destination,
	streamSettings *internet.MemoryStreamConfig,
	grpcSettings *xgrpc.Config,
	outboundTag string,
) (*yumiGrpcEntry, error) {
	key := yumiGrpcKey{dest: dest, stream: streamSettings}
	var staleConn *grpc.ClientConn

	yumiGrpcMu.Lock()
	if existing, found := yumiGrpcMap[key]; found {
		if !existing.closed && existing.genCtx == genCtx && existing.conn.GetState() != connectivity.Shutdown {
			existing.dialing++
			if existing.outboundTag == "" && outboundTag != "" {
				existing.outboundTag = outboundTag
			}
			yumiGrpcMu.Unlock()
			return existing, nil
		}
		if !existing.closed {
			existing.closed = true
			staleConn = existing.conn
		}
		delete(yumiGrpcMap, key)
	}
	yumiGrpcMu.Unlock()

	if staleConn != nil {
		_ = staleConn.Close()
	}

	tlsConfig := xtls.ConfigFromStreamSettings(streamSettings)
	realityConfig := reality.ConfigFromStreamSettings(streamSettings)
	sockopt := streamSettings.SocketSettings

	dialOptions := []grpc.DialOption{
		grpc.WithConnectParams(grpc.ConnectParams{
			Backoff: backoff.Config{
				BaseDelay:  500 * time.Millisecond,
				Multiplier: 1.5,
				Jitter:     0.2,
				MaxDelay:   19 * time.Second,
			},
			MinConnectTimeout: 5 * time.Second,
		}),
		grpc.WithContextDialer(func(gctx context.Context, s string) (net.Conn, error) {
			if err := gctx.Err(); err != nil {
				return nil, err
			}
			if genCtx != nil {
				if err := genCtx.Err(); err != nil {
					return nil, err
				}
				var cancel context.CancelFunc
				gctx, cancel = context.WithCancel(gctx)
				defer cancel()
				stop := context.AfterFunc(genCtx, cancel)
				defer stop()
				gctx = withGenCtxValue(gctx, genCtx)
			}

			rawHost, rawPort, err := net.SplitHostPort(s)
			if err != nil {
				return nil, err
			}
			if rawPort == "" {
				rawPort = "443"
			}
			port, err := v2net.PortFromString(rawPort)
			if err != nil {
				return nil, err
			}
			address := v2net.ParseAddress(rawHost)

			gctx = xctx.ContextWithID(gctx, xctx.IDFromContext(ctx))
			gctx = session.ContextWithOutbounds(gctx, session.OutboundsFromContext(ctx))
			gctx = session.ContextWithTimeoutOnly(gctx, true)

			c, err := internet.DialSystem(gctx, v2net.TCPDestination(address, port), sockopt)
			if err != nil {
				return nil, err
			}
			if streamSettings.TcpmaskManager != nil {
				newConn, err := streamSettings.TcpmaskManager.WrapConnClient(c)
				if err != nil {
					_ = c.Close()
					return nil, err
				}
				c = newConn
			}
			if tlsConfig != nil {
				config := tlsConfig.GetTLSConfig(xtls.WithDestination(dest))
				if fingerprint := xtls.GetFingerprint(tlsConfig.Fingerprint); fingerprint != nil {
					return xtls.UClient(c, config, fingerprint), nil
				}
				return xtls.Client(c, config), nil
			}
			if realityConfig != nil {
				return reality.UClient(c, realityConfig, gctx, dest)
			}
			return c, nil
		}),
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	}

	authority := ""
	if grpcSettings.Authority != "" {
		authority = grpcSettings.Authority
	} else if tlsConfig != nil && tlsConfig.ServerName != "" {
		authority = tlsConfig.ServerName
	} else if realityConfig == nil && dest.Address.Family().IsDomain() {
		authority = dest.Address.Domain()
	}
	dialOptions = append(dialOptions, grpc.WithAuthority(authority))

	if grpcSettings.IdleTimeout > 0 || grpcSettings.HealthCheckTimeout > 0 || grpcSettings.PermitWithoutStream {
		dialOptions = append(dialOptions, grpc.WithKeepaliveParams(keepalive.ClientParameters{
			Time:                time.Second * time.Duration(grpcSettings.IdleTimeout),
			Timeout:             time.Second * time.Duration(grpcSettings.HealthCheckTimeout),
			PermitWithoutStream: grpcSettings.PermitWithoutStream,
		}))
	}
	if grpcSettings.InitialWindowsSize > 0 {
		dialOptions = append(dialOptions, grpc.WithInitialWindowSize(grpcSettings.InitialWindowsSize))
	}
	if ua := resolveGrpcUserAgent(grpcSettings.UserAgent); ua != "" {
		dialOptions = append(dialOptions, grpc.WithUserAgent(ua))
	}

	var grpcDestHost string
	if dest.Address.Family().IsDomain() {
		grpcDestHost = dest.Address.Domain()
	} else {
		grpcDestHost = dest.Address.IP().String()
	}

	conn, err := grpc.NewClient(
		"passthrough:///"+net.JoinHostPort(grpcDestHost, dest.Port.String()),
		dialOptions...,
	)
	if err != nil {
		return nil, err
	}

	entry := &yumiGrpcEntry{
		key:         key,
		conn:        conn,
		genCtx:      genCtx,
		outboundTag: outboundTag,
		dialing:     1,
	}

	yumiGrpcMu.Lock()
	if genCtx != nil && genCtx.Err() != nil {
		yumiGrpcMu.Unlock()
		_ = conn.Close()
		return nil, genCtx.Err()
	}
	if raceEntry, found := yumiGrpcMap[key]; found && !raceEntry.closed && raceEntry.genCtx == genCtx && raceEntry.conn.GetState() != connectivity.Shutdown {
		raceEntry.dialing++
		if raceEntry.outboundTag == "" && outboundTag != "" {
			raceEntry.outboundTag = outboundTag
		}
		yumiGrpcMu.Unlock()
		_ = conn.Close()
		return raceEntry, nil
	}
	yumiGrpcMap[key] = entry
	yumiGrpcMu.Unlock()

	conn.Connect()
	return entry, nil
}

func releaseFailedGrpcDial(entry *yumiGrpcEntry) {
	var toClose *grpc.ClientConn
	yumiGrpcMu.Lock()
	if entry.dialing > 0 {
		entry.dialing--
	}
	if !entry.closed && entry.activeStreams == 0 && entry.dialing == 0 {
		entry.closed = true
		delete(yumiGrpcMap, entry.key)
		toClose = entry.conn
	}
	yumiGrpcMu.Unlock()
	if toClose != nil {
		_ = toClose.Close()
	}
}

func dialYumiGRPC(
	ctx context.Context,
	dest v2net.Destination,
	streamSettings *internet.MemoryStreamConfig,
) (stat.Connection, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	genCtx := genCtxForDial(ctx)
	if genCtx != nil {
		if err := genCtx.Err(); err != nil {
			return nil, err
		}
	}
	grpcSettings, ok := streamSettings.ProtocolSettings.(*xgrpc.Config)
	if !ok || grpcSettings == nil {
		return nil, errors.New("missing gRPC protocol settings")
	}

	outboundTag := outboundTagFromContext(ctx)
	if strings.HasPrefix(outboundTag, NodeTagPrefix) && !isMeasureProbeCtx(ctx) {
		if pinned := currentPinnedTag(); pinned != "" && outboundTag != pinned {
			return nil, errors.New("outbound switched from " + outboundTag + " to " + pinned)
		}
	}
	entry, err := acquireYumiGrpcEntry(ctx, genCtx, dest, streamSettings, grpcSettings, outboundTag)
	if err != nil {
		return nil, err
	}

	streamCtx, streamCancel := context.WithCancel(ctx)
	var stopGen func() bool
	if genCtx != nil {
		stopGen = context.AfterFunc(genCtx, streamCancel)
	}

	cleanupFailed := func() {
		if stopGen != nil {
			stopGen()
		}
		streamCancel()
		releaseFailedGrpcDial(entry)
	}

	client := encoding.NewGRPCServiceClient(entry.conn).(encoding.GRPCServiceClientX)
	if grpcSettings.MultiMode {
		grpcService, err := client.TunMultiCustomName(
			streamCtx,
			yumiGrpcServiceName(grpcSettings),
			yumiGrpcTunMultiStreamName(grpcSettings),
		)
		if err != nil {
			cleanupFailed()
			return nil, err
		}
		yumiGrpcMu.Lock()
		if entry.dialing > 0 {
			entry.dialing--
		}
		if entry.closed || (genCtx != nil && genCtx.Err() != nil) {
			yumiGrpcMu.Unlock()
			_ = grpcService.CloseSend()
			if stopGen != nil {
				stopGen()
			}
			streamCancel()
			return nil, errors.New("gRPC tunnel closed during dial")
		}
		entry.activeStreams++
		yumiGrpcMu.Unlock()

		return stat.Connection(encoding.NewMultiHunkConn(grpcService, makeStreamCloseFunc(entry, stopGen, streamCancel), nil)), nil
	}

	grpcService, err := client.TunCustomName(
		streamCtx,
		yumiGrpcServiceName(grpcSettings),
		yumiGrpcTunStreamName(grpcSettings),
	)
	if err != nil {
		cleanupFailed()
		return nil, err
	}
	yumiGrpcMu.Lock()
	if entry.dialing > 0 {
		entry.dialing--
	}
	if entry.closed || (genCtx != nil && genCtx.Err() != nil) {
		yumiGrpcMu.Unlock()
		_ = grpcService.CloseSend()
		if stopGen != nil {
			stopGen()
		}
		streamCancel()
		return nil, errors.New("gRPC tunnel closed during dial")
	}
	entry.activeStreams++
	yumiGrpcMu.Unlock()

	return stat.Connection(encoding.NewHunkConn(grpcService, makeStreamCloseFunc(entry, stopGen, streamCancel), nil)), nil
}

func makeStreamCloseFunc(entry *yumiGrpcEntry, stopGen func() bool, streamCancel context.CancelFunc) context.CancelFunc {
	var once sync.Once
	return func() {
		once.Do(func() {
			if stopGen != nil {
				stopGen()
			}
			streamCancel()
			var toClose *grpc.ClientConn
			yumiGrpcMu.Lock()
			if entry.activeStreams > 0 {
				entry.activeStreams--
			}
			if !entry.closed && entry.activeStreams == 0 && entry.dialing == 0 && entry.outboundTag != "" {
				if pinned := currentPinnedTag(); pinned != "" && entry.outboundTag != pinned {
					entry.closed = true
					delete(yumiGrpcMap, entry.key)
					toClose = entry.conn
				}
			}
			yumiGrpcMu.Unlock()
			if toClose != nil {
				_ = toClose.Close()
			}
		})
	}
}

func closeAllYumiGrpcConns() {
	yumiGrpcMu.Lock()
	var toClose []*grpc.ClientConn
	for key, entry := range yumiGrpcMap {
		if !entry.closed {
			entry.closed = true
			toClose = append(toClose, entry.conn)
		}
		delete(yumiGrpcMap, key)
	}
	yumiGrpcMu.Unlock()

	for _, conn := range toClose {
		if conn != nil {
			_ = conn.Close()
		}
	}
}

func evictOtherYumiGrpcConns(keepTag string) {
	yumiGrpcMu.Lock()
	var toClose []*grpc.ClientConn
	for key, entry := range yumiGrpcMap {
		if entry.outboundTag != "" && entry.outboundTag != keepTag {
			if !entry.closed {
				entry.closed = true
				toClose = append(toClose, entry.conn)
			}
			delete(yumiGrpcMap, key)
		}
	}
	yumiGrpcMu.Unlock()

	for _, conn := range toClose {
		if conn != nil {
			_ = conn.Close()
		}
	}
}

func yumiGrpcConnCount() int {
	yumiGrpcMu.Lock()
	defer yumiGrpcMu.Unlock()
	return len(yumiGrpcMap)
}
