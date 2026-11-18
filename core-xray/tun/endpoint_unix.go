//go:build android || linux

package tun

import (
	"errors"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// NewEndpointFromFD wraps an OS-owned TUN file descriptor into a gVisor LinkEndpoint.
// Caller retains ownership of tunFD; this function never closes tunFD on error or Close().
func NewEndpointFromFD(tunFD int, mtu uint32) (stack.LinkEndpoint, error) {
	if tunFD <= 0 {
		return nil, errors.New("invalid tun file descriptor")
	}
	if mtu == 0 {
		mtu = 1500
	}
	if err := unix.SetNonblock(tunFD, true); err != nil {
		return nil, err
	}
	return fdbased.New(&fdbased.Options{
		FDs:               []int{tunFD},
		MTU:               mtu,
		RXChecksumOffload: true,
	})
}
