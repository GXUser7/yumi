//go:build !android && !linux

package tun

import (
	"errors"

	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// NewEndpointFromFD is only supported on Android and Linux. Non-Unix platforms inject a
// stack.LinkEndpoint (such as gVisor channel.Endpoint) via GuardedConfig.Endpoint for testing.
func NewEndpointFromFD(tunFD int, mtu uint32) (stack.LinkEndpoint, error) {
	return nil, errors.New("tun file descriptor endpoint is only supported on android/linux")
}
