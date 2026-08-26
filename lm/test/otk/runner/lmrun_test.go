package runner

import (
	"os/exec"
	"testing"
	"time"
)

// Uses a stand-in shell to test capture mechanics without requiring lm or a hub.
// Looked up via PATH (and skipped if absent) so the test stays portable.
func TestRunCapturesExitAndStderr(t *testing.T) {
	sh, err := exec.LookPath("sh")
	if err != nil {
		t.Skip("sh not found in PATH; skipping shell-based capture test")
	}
	r := Run(nil, sh, "-c", "echo out; echo err 1>&2; exit 3")
	if r.Exit != 3 {
		t.Errorf("exit got %d want 3", r.Exit)
	}
	if r.Stdout != "out\n" {
		t.Errorf("stdout got %q", r.Stdout)
	}
	if r.Stderr != "err\n" {
		t.Errorf("stderr got %q", r.Stderr)
	}
	if r.Panicked {
		t.Errorf("should not be marked panicked on clean exit")
	}
	if !r.Started {
		t.Errorf("a process that ran must be marked Started")
	}
}

func TestRunMarksMissingBinary(t *testing.T) {
	r := Run(nil, "/no/such/binary/xyz")
	if r.Exit == 0 {
		t.Errorf("missing binary must be non-zero exit")
	}
	if r.Started {
		t.Errorf("a binary that could not be launched must NOT be marked Started")
	}
}

// TestRunTimedOut verifies the runner kills and returns when a process outlives
// the deadline. runTimeout must be a var (not const) so this test can set a tiny
// value; failing to compile is the RED step that drives that change.
func TestRunTimedOut(t *testing.T) {
	sh, err := exec.LookPath("sh")
	if err != nil {
		t.Skip("sh not found in PATH; skipping timeout test")
	}
	old := runTimeout
	runTimeout = 50 * time.Millisecond
	defer func() { runTimeout = old }()

	r := Run(nil, sh, "-c", "sleep 60")
	if !r.TimedOut {
		t.Errorf("expected TimedOut=true for a process that outlives the timeout")
	}
	if !r.Started {
		t.Errorf("a process that launched before timing out must be marked Started")
	}
}
