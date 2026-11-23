package yumi

import (
	"context"
	go_tls "crypto/tls"
	"errors"
	"fmt"
	"math/rand"
	"net"
	"net/http"
	"reflect"
	"runtime"
	"strconv"
	"sync"
	"time"

	"github.com/apernet/quic-go"
	"github.com/apernet/quic-go/http3"
	"github.com/apernet/quic-go/quicvarint"
	"github.com/xtls/xray-core/app/proxyman"
	"github.com/xtls/xray-core/common"
	v2net "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/net/cnc"
	cserial "github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/hysteria/congestion"
	"github.com/xtls/xray-core/transport/internet/hysteria/congestion/bbr"
	"github.com/xtls/xray-core/transport/internet/hysteria/udphop"
	"github.com/xtls/xray-core/transport/internet/stat"
	xtls "github.com/xtls/xray-core/transport/internet/tls"

	xhysteria "github.com/xtls/xray-core/transport/internet/hysteria"
)

const yumiHysteriaProbeProtocolName = "yumi-hysteria-probe"

func init() {
	common.Must(internet.RegisterProtocolConfigCreator(yumiHysteriaProbeProtocolName, func() interface{} {
		return &xhysteria.Config{UdpIdleTimeout: 60}
	}))
	common.Must(internet.RegisterTransportDialer(yumiHysteriaProbeProtocolName, dialYumiHysteriaProbe))
}

// rewriteHysteriaProbeOutbounds points Hysteria2 outbounds in an ephemeral probe document at
// [dialYumiHysteriaProbe] so each probe opens and immediately closes an isolated QUIC session
// instead of sharing Xray's global hysteria clientManager cache across distinct credentials.
func rewriteHysteriaProbeOutbounds(config *core.Config) error {
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
		if senderCfg.StreamSettings.ProtocolName == "hysteria" {
			senderCfg.StreamSettings.ProtocolName = yumiHysteriaProbeProtocolName
			changed = true
		}
		for _, ts := range senderCfg.StreamSettings.TransportSettings {
			if ts != nil && ts.ProtocolName == "hysteria" {
				ts.ProtocolName = yumiHysteriaProbeProtocolName
				changed = true
			}
		}
		if changed {
			ob.SenderSettings = cserial.ToTypedMessage(senderCfg)
		}
	}
	return nil
}

// dialYumiHysteriaProbe dials one fresh, uncached Hysteria2 TCP stream that honors ctx
// cancellation immediately and tears down its QUIC connection and UDP socket when the stream closes.
func dialYumiHysteriaProbe(
	ctx context.Context,
	dest v2net.Destination,
	streamSettings *internet.MemoryStreamConfig,
) (stat.Connection, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if streamSettings == nil {
		return nil, errors.New("missing stream settings")
	}
	tlsConfig := xtls.ConfigFromStreamSettings(streamSettings)
	if tlsConfig == nil {
		return nil, errors.New("tls config is nil")
	}
	hyCfg, ok := streamSettings.ProtocolSettings.(*xhysteria.Config)
	if !ok || hyCfg == nil {
		return nil, errors.New("missing hysteria protocol settings")
	}

	dest.Network = v2net.Network_UDP

	quicParams := streamSettings.QuicParams
	if quicParams == nil {
		quicParams = &internet.QuicParams{
			BbrProfile: string(bbr.ProfileStandard),
			UdpHop:     &internet.UdpHop{},
		}
	}

	quicConfig := &quic.Config{
		InitialStreamReceiveWindow:     quicParams.InitStreamReceiveWindow,
		MaxStreamReceiveWindow:         quicParams.MaxStreamReceiveWindow,
		InitialConnectionReceiveWindow: quicParams.InitConnReceiveWindow,
		MaxConnectionReceiveWindow:     quicParams.MaxConnReceiveWindow,
		MaxIdleTimeout:                 10 * time.Second,
		KeepAlivePeriod:                time.Duration(quicParams.KeepAlivePeriod) * time.Second,
		DisablePathMTUDiscovery: quicParams.DisablePathMtuDiscovery ||
			(runtime.GOOS != "linux" && runtime.GOOS != "windows" && runtime.GOOS != "darwin"),
		EnableDatagrams:          true,
		MaxDatagramFrameSize:     xhysteria.MaxDatagramFrameSize,
		OmitMaxDatagramFrameSize: time.Now().After(time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)),
		DisablePathManager:       true,
	}
	if quicParams.InitStreamReceiveWindow == 0 {
		quicConfig.InitialStreamReceiveWindow = 8388608
	}
	if quicParams.MaxStreamReceiveWindow == 0 {
		quicConfig.MaxStreamReceiveWindow = 8388608
	}
	if quicParams.InitConnReceiveWindow == 0 {
		quicConfig.InitialConnectionReceiveWindow = 8388608 * 5 / 2
	}
	if quicParams.MaxConnReceiveWindow == 0 {
		quicConfig.MaxConnectionReceiveWindow = 8388608 * 5 / 2
	}

	var hopIndex int
	if quicParams.UdpHop != nil && len(quicParams.UdpHop.Ports) > 0 {
		hopIndex = rand.Intn(len(quicParams.UdpHop.Ports))
		dest.Port = v2net.Port(quicParams.UdpHop.Ports[hopIndex])
	}

	raw, err := internet.DialSystem(ctx, dest, streamSettings.SocketSettings)
	if err != nil {
		return nil, fmt.Errorf("hysteria probe dial dest: %w", err)
	}

	var pktConn net.PacketConn
	var udpAddr *net.UDPAddr
	switch c := raw.(type) {
	case *internet.PacketConnWrapper:
		pktConn = c.PacketConn
		udpAddr = raw.RemoteAddr().(*net.UDPAddr)
	case *cnc.Connection:
		pktConn = &internet.FakePacketConn{Conn: c}
		tcpAddr := c.RemoteAddr().(*net.TCPAddr)
		udpAddr = &net.UDPAddr{IP: tcpAddr.IP, Port: tcpAddr.Port}
	default:
		_ = raw.Close()
		return nil, fmt.Errorf("unexpected hysteria probe conn type %s", reflect.TypeOf(raw))
	}

	if quicParams.UdpHop != nil && len(quicParams.UdpHop.Ports) > 0 {
		udpHopDialer := func(addr *net.UDPAddr) (net.PacketConn, error) {
			hopConn, err := internet.DialSystem(
				ctx,
				v2net.UDPDestination(v2net.IPAddress(addr.IP), v2net.Port(addr.Port)),
				streamSettings.SocketSettings,
			)
			if err != nil {
				return nil, err
			}
			switch hc := hopConn.(type) {
			case *internet.PacketConnWrapper:
				return hc.PacketConn, nil
			case *cnc.Connection:
				return &internet.FakePacketConn{Conn: hc}, nil
			default:
				_ = hopConn.Close()
				return nil, fmt.Errorf("unexpected udphop conn type %s", reflect.TypeOf(hopConn))
			}
		}
		pktConn = udphop.NewUDPHopPacketConn(
			udphop.ToAddrs(udpAddr.IP, quicParams.UdpHop.Ports),
			time.Duration(quicParams.UdpHop.IntervalMin)*time.Second,
			time.Duration(quicParams.UdpHop.IntervalMax)*time.Second,
			udpHopDialer,
			pktConn,
			hopIndex,
		)
	}

	if streamSettings.UdpmaskManager != nil {
		masked, err := streamSettings.UdpmaskManager.WrapPacketConnClient(pktConn)
		if err != nil {
			_ = pktConn.Close()
			return nil, fmt.Errorf("hysteria probe mask: %w", err)
		}
		pktConn = masked
	}

	tr := &quic.Transport{Conn: pktConn}

	var (
		dialMu  sync.Mutex
		qconn   *quic.Conn
		aborted bool
	)
	closeAll := func() {
		dialMu.Lock()
		aborted = true
		qc := qconn
		dialMu.Unlock()
		if qc != nil {
			_ = qc.CloseWithError(0x101, "")
		}
		_ = tr.Close()
		_ = pktConn.Close()
	}

	rt := &http3.Transport{
		TLSClientConfig: tlsConfig.GetTLSConfig(xtls.WithDestination(dest)),
		QUICConfig:      quicConfig,
		Dial: func(dialCtx context.Context, _ string, tlsCfg *go_tls.Config, cfg *quic.Config) (*quic.Conn, error) {
			qc, err := tr.DialEarly(dialCtx, udpAddr, tlsCfg, cfg)
			if err != nil {
				return nil, err
			}
			dialMu.Lock()
			if aborted {
				dialMu.Unlock()
				_ = qc.CloseWithError(0x101, "")
				return nil, context.Canceled
			}
			qconn = qc
			dialMu.Unlock()
			return qc, nil
		},
	}

	req, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		"https://"+xhysteria.URLHost+xhysteria.URLPath,
		nil,
	)
	if err != nil {
		closeAll()
		return nil, err
	}
	req.Header = http.Header{
		xhysteria.RequestHeaderAuth:   []string{hyCfg.Auth},
		xhysteria.CommonHeaderCCRX:    []string{strconv.FormatUint(quicParams.BrutalDown, 10)},
		xhysteria.CommonHeaderPadding: []string{xhysteria.AuthRequestPadding.String()},
	}

	stopCancel := context.AfterFunc(ctx, closeAll)
	resp, err := rt.RoundTrip(req)
	if err != nil {
		stopCancel()
		closeAll()
		return nil, err
	}
	_ = resp.Body.Close()

	if resp.StatusCode != xhysteria.StatusAuthOK {
		stopCancel()
		closeAll()
		return nil, fmt.Errorf("hysteria auth failed code %d", resp.StatusCode)
	}

	dialMu.Lock()
	qc := qconn
	dialMu.Unlock()
	if qc == nil {
		stopCancel()
		closeAll()
		return nil, errors.New("hysteria probe missing quic connection")
	}

	down, _ := strconv.ParseUint(resp.Header.Get(xhysteria.CommonHeaderCCRX), 10, 64)
	switch quicParams.Congestion {
	case "reno":
	case "bbr":
		congestion.UseBBR(qc, bbr.Profile(quicParams.BbrProfile))
	case "", "brutal":
		if quicParams.BrutalUp == 0 || down == 0 {
			congestion.UseBBR(qc, bbr.Profile(quicParams.BbrProfile))
		} else {
			congestion.UseBrutal(qc, min(quicParams.BrutalUp, down))
		}
	case "force-brutal":
		congestion.UseBrutal(qc, quicParams.BrutalUp)
	}

	stream, err := qc.OpenStreamSync(ctx)
	if err != nil {
		stopCancel()
		closeAll()
		return nil, err
	}

	if !stopCancel() {
		_ = stream.Close()
		closeAll()
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		return nil, context.Canceled
	}

	return &yumiHysteriaProbeConn{
		stream:     stream,
		qconn:      qc,
		tr:         tr,
		pktConn:    pktConn,
		local:      qc.LocalAddr(),
		remote:     qc.RemoteAddr(),
		firstWrite: true,
	}, nil
}

type yumiHysteriaProbeConn struct {
	stream  *quic.Stream
	qconn   *quic.Conn
	tr      *quic.Transport
	pktConn net.PacketConn
	local   net.Addr
	remote  net.Addr

	writeMu    sync.Mutex
	firstWrite bool
	closeOnce  sync.Once
}

func (c *yumiHysteriaProbeConn) Read(b []byte) (int, error) {
	return c.stream.Read(b)
}

func (c *yumiHysteriaProbeConn) Write(b []byte) (int, error) {
	c.writeMu.Lock()
	first := c.firstWrite
	if first {
		c.firstWrite = false
	}
	c.writeMu.Unlock()

	if first {
		prefix := quicvarint.Append(nil, xhysteria.FrameTypeTCPRequest)
		buf := make([]byte, len(prefix)+len(b))
		copy(buf, prefix)
		copy(buf[len(prefix):], b)
		if _, err := c.stream.Write(buf); err != nil {
			return 0, err
		}
		return len(b), nil
	}
	return c.stream.Write(b)
}

func (c *yumiHysteriaProbeConn) Close() error {
	var err error
	c.closeOnce.Do(func() {
		c.stream.CancelRead(0)
		err = c.stream.Close()
		_ = c.qconn.CloseWithError(0x100, "")
		_ = c.tr.Close()
		_ = c.pktConn.Close()
	})
	return err
}

func (c *yumiHysteriaProbeConn) LocalAddr() net.Addr  { return c.local }
func (c *yumiHysteriaProbeConn) RemoteAddr() net.Addr { return c.remote }

func (c *yumiHysteriaProbeConn) SetDeadline(t time.Time) error      { return c.stream.SetDeadline(t) }
func (c *yumiHysteriaProbeConn) SetReadDeadline(t time.Time) error  { return c.stream.SetReadDeadline(t) }
func (c *yumiHysteriaProbeConn) SetWriteDeadline(t time.Time) error { return c.stream.SetWriteDeadline(t) }
