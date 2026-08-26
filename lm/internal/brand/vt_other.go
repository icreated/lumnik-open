//go:build !windows

package brand

// vtEnabled: ANSI passthrough needs no console switch outside Windows.
func vtEnabled() bool { return true }
