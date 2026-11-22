//go:build linux || android

package yumi

import "syscall"

// tcpSynRetries caps TCP SYN retransmits at 1 (~3s instead of ~16s) so dropped SYNs fail fast
// and Xray/app retries can open a fresh source port promptly on cellular/DPI networks.
const tcpSynRetries = 1

func setTcpSynRetries(fd uintptr) {
	_ = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_TCP, syscall.TCP_SYNCNT, tcpSynRetries)
}
