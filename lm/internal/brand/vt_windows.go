//go:build windows

package brand

import (
	"os"
	"sync"

	"golang.org/x/sys/windows"
)

var (
	vtOnce sync.Once
	vtOK   bool
)

// vtEnabled switches the Windows console into VT mode so ANSI colour is
// interpreted rather than printed — conhost boots WITHOUT it, and the first
// real Windows run showed the wordmark as raw ←[38;5;214m escapes. A
// redirected stdout (no console) keeps colour, matching the Unix pipe
// behaviour of ColorsEnabled.
func vtEnabled() bool {
	vtOnce.Do(func() {
		h := windows.Handle(os.Stdout.Fd())
		var mode uint32
		if err := windows.GetConsoleMode(h, &mode); err != nil {
			vtOK = true // not a console: escapes ride through, as on Unix
			return
		}
		if mode&windows.ENABLE_VIRTUAL_TERMINAL_PROCESSING != 0 {
			vtOK = true
			return
		}
		vtOK = windows.SetConsoleMode(h, mode|windows.ENABLE_VIRTUAL_TERMINAL_PROCESSING) == nil
	})
	return vtOK
}
