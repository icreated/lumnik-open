package cli

import (
	"fmt"
	"os"
	"os/signal"
	"runtime"
	"syscall"

	"github.com/spf13/cobra"
	"golang.org/x/term"

	"github.com/icreated/lumnik-open/lm/internal/textsafe"
)

// eofGesture names the key that ends a keyboard read, in the shell the reader is actually in.
// Same reasoning as shellArg/quoteFor in manifest.go: PowerShell is where the CLI is typed on
// Windows (docs/deploy-windows.md), Ctrl+D does nothing there, and a message naming the wrong
// key reads exactly like a hang — which is the one thing these notices exist to prevent.
//
// It lives here, with the other terminal seams, so that every other file in the package can be
// checked for a hard-coded gesture — see TestOnlyTerminalGoNamesTheEofKey.
func eofGesture() string { return eofGestureFor(runtime.GOOS) }

// eofGestureFor is eofGesture with the platform passed in, so both gestures are stated as a
// table rather than inferred from whatever host runs the test.
func eofGestureFor(goos string) string {
	if goos == "windows" {
		return "Ctrl+Z then Enter"
	}
	return "Ctrl+D"
}

// terminalIn returns the keyboard behind the command's input, or false when there is none.
//
// It hands back the *os.File rather than a descriptor because a prompt is written to the terminal
// itself, not to a stream — see promptSecret. A test drives SetIn with something that is not an
// *os.File, so it always takes the other road, which is why the keyboard half is covered by
// test/pty/no-echo-secrets.sh instead.
func terminalIn(cmd *cobra.Command) (*os.File, bool) {
	f, ok := cmd.InOrStdin().(*os.File)
	if !ok || !term.IsTerminal(int(f.Fd())) {
		return nil, false
	}
	return f, true
}

// promptSecret asks for one line at the keyboard with the echo off.
//
// The prompt goes to the TERMINAL, not to stderr. Routing it as a stream reproduced the very
// defect this seam exists to remove: `lm source jdbc test … 2>/dev/null` at a keyboard turned the
// echo off, wrote the prompt into /dev/null and blocked in silence — measured. A question asked of
// whoever is at the keyboard belongs to their terminal, which is where `sudo` and `ssh` put it.
// Diagnostics stay on stderr, where a redirect is meant to catch them.
//
// The label carries a value the caller typed (`--user`, a secret's name), so it goes out through
// textsafe like every other rendered value — a terminal is exactly where an escape sequence would
// be interpreted.
func promptSecret(cmd *cobra.Command, tty *os.File, label string) (string, error) {
	fmt.Fprintf(textsafe.NewWriter(tty), "%s: ", label)
	// ReadPassword swallows the Enter, so the cursor is left mid-line until we end it ourselves.
	defer fmt.Fprintln(tty)
	stopWatching := restoreTerminalOnSignal(tty)
	defer stopWatching()
	b, err := term.ReadPassword(int(tty.Fd()))
	return string(b), err
}

// restoreTerminalOnSignal puts the terminal back if the process is interrupted while a prompt
// owns it, and returns the function that stops watching.
//
// term.ReadPassword clears ECHO and restores it from a DEFERRED call, which a signal never
// reaches: the process dies first. Measured through a pseudo-terminal before this existed —
// `stty -a` reported `echo` before the prompt and `-echo` after a Ctrl+C — leaving the caller
// typing blind until `stty sane`. x/term names the gap itself: GetState is documented as the way
// to "restore the terminal after a signal".
func restoreTerminalOnSignal(tty *os.File) func() {
	fd := int(tty.Fd())
	state, err := term.GetState(fd)
	if err != nil {
		return func() {} // a terminal we cannot read: nothing to put back
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	done := make(chan struct{})
	go func() {
		select {
		case s := <-sig:
			_ = term.Restore(fd, state)
			fmt.Fprintln(tty) // end the prompt's line, so the shell's own prompt starts clean
			// Leave the status an uncaught signal would have left: 128 + the signal number.
			n, ok := s.(syscall.Signal)
			if !ok {
				n = syscall.SIGINT
			}
			os.Exit(128 + int(n))
		case <-done:
		}
	}()
	return func() { signal.Stop(sig); close(done) }
}
