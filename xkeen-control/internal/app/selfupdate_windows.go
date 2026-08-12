//go:build windows

package app

import "syscall"

func detachedProcessAttributes() *syscall.SysProcAttr { return nil }
