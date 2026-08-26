package runner

import (
	"bytes"
	"errors"
	"os"
	"os/exec"
	"strings"
	"time"
)

// Result is everything the oracle needs to judge one lm invocation.
type Result struct {
	Stdout   string
	Stderr   string
	Exit     int
	Started  bool // false if the process could not be launched at all
	Panicked bool // true if output shows a Go panic / unhandled stack trace
	TimedOut bool
	Duration time.Duration
}

// runTimeout is a var so tests can override it without rebuilding.
var runTimeout = 30 * time.Second

// killWait is the maximum time to wait for a process to die after Kill().
// A second bound here ensures Run() always returns even if Kill() is ignored
// (pathological OS state, kernel bug, etc.).
const killWait = 5 * time.Second

// Run executes a command with extra env (KEY=VALUE) and captures its result.
// It never lets a misbehaving child crash the harness.
func Run(env []string, name string, args ...string) Result {
	start := time.Now()
	cmd := exec.Command(name, args...)
	cmd.Env = append(os.Environ(), env...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	done := make(chan error, 1)
	if err := cmd.Start(); err != nil {
		return Result{Exit: 127, Stderr: err.Error(), Duration: time.Since(start)}
	}
	go func() { done <- cmd.Wait() }()

	// A single timer we stop on the happy path, instead of time.After leaving a
	// pending timer armed for the full timeout on every fast command (matters as
	// the OTK battery scales to N≫1).
	timer := time.NewTimer(runTimeout)
	defer timer.Stop()

	var (
		exit     int
		timedOut bool
		err      error
	)
	select {
	case err = <-done:
	case <-timer.C:
		_ = cmd.Process.Kill()
		// Bounded wait: if the process doesn't exit within killWait after
		// receiving SIGKILL (pathological OS state), abandon it rather than
		// blocking forever. done is buffered so the goroutine can still drain.
		kw := time.NewTimer(killWait)
		defer kw.Stop()
		select {
		case <-done:
		case <-kw.C:
		}
		timedOut = true
		err = errors.New("timeout")
	}
	if err != nil {
		var ee *exec.ExitError
		if errors.As(err, &ee) {
			exit = ee.ExitCode()
		} else {
			exit = 1
		}
	}
	combined := stdout.String() + stderr.String()
	panicked := strings.Contains(combined, "panic:") ||
		strings.Contains(combined, "goroutine ") ||
		strings.Contains(combined, "runtime error:")
	return Result{
		Stdout:   stdout.String(),
		Stderr:   stderr.String(),
		Exit:     exit,
		Started:  true,
		Panicked: panicked,
		TimedOut: timedOut,
		Duration: time.Since(start),
	}
}
