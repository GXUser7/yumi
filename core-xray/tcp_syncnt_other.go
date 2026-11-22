//go:build !linux && !android

package yumi

func setTcpSynRetries(uintptr) {}
