package tui

import (
	"encoding/base64"
	"os"
)

// Yank emits an OSC 52 escape sequence to /dev/tty, which modern terminals
// (iTerm2, Alacritty, kitty, tmux, WezTerm, recent xterm) translate into a
// clipboard copy — works over SSH, requires zero native dependencies.
//
// Silent no-op (returns the open error) when /dev/tty is unavailable, e.g.
// in CI. Callers should surface the error in the UI but not crash.
func Yank(text string) error {
	f, err := os.OpenFile("/dev/tty", os.O_WRONLY, 0)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = f.WriteString(buildOSC52(text))
	return err
}

func buildOSC52(text string) string {
	return "\x1b]52;c;" + base64.StdEncoding.EncodeToString([]byte(text)) + "\x07"
}
