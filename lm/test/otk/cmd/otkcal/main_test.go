//go:build otk && unix

package main

import (
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

// Two goroutines reach restore(): main's normal path and the signal handler. An
// "if already done, return" flag would not have been enough — it is a test-and-set,
// and two callers can pass the test together and then both run `git apply -R`.
//
// This drives the race rather than describing it, and `go test -race` is what makes
// the drive meaningful.
func TestRestoreRunsExactlyOnce(t *testing.T) {
	s := &session{} // unarmed: doRestore returns immediately, but still counts
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); _ = s.restore() }()
	}
	wg.Wait()

	if s.restores != 1 {
		t.Fatalf("the restoration body ran %d times under 50 concurrent callers, want exactly 1", s.restores)
	}
}

// stopChildren must RETURN only once every child is reaped, because what follows it
// reverts the source and deletes the packaged output — and neither means anything
// while a build is still writing. `sleep 30` stands in for `mvn package`: if the
// sweep returned without waiting, this test would finish in milliseconds with a
// process still alive.
func TestStopChildrenReapsBeforeReturning(t *testing.T) {
	s := &session{}
	c, err := s.start(exec.Command("sleep", "30"))
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	pid := c.cmd.Process.Pid

	start := time.Now()
	s.stopChildren()
	if took := time.Since(start); took > 25*time.Second {
		t.Fatalf("stopChildren took %v — it escalated late or not at all", took)
	}

	select {
	case <-c.done:
	default:
		t.Fatal("stopChildren returned while a child was still unreaped")
	}
	// Signal 0 probes for existence without delivering anything.
	if err := syscall.Kill(pid, 0); err == nil {
		t.Fatalf("pid %d is still alive after stopChildren", pid)
	}
}

// Two callers reach stopChildren — the between-phases stop and restoration — and
// the second must WAIT for the first's reap, not observe an emptied list and
// conclude nothing is running. Without stopMu the second caller here returns in
// microseconds while `sleep 30` is still very much alive, and restoration would go
// on to revert the source and delete target underneath a running build.
func TestConcurrentStopChildrenBothWaitForTheReap(t *testing.T) {
	s := &session{}
	c, err := s.start(exec.Command("sleep", "30"))
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	pid := c.cmd.Process.Pid

	// Recorded per caller, then read after Wait, so each answer is that caller's own.
	aliveOnReturn := make([]bool, 2)
	var wg sync.WaitGroup
	for i := range aliveOnReturn {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			s.stopChildren()
			// Signal 0 probes for existence. A reaped child's pid is gone; a merely
			// signalled one is still there.
			aliveOnReturn[i] = syscall.Kill(pid, 0) == nil
		}(i)
	}
	wg.Wait()

	for i, alive := range aliveOnReturn {
		if alive {
			t.Errorf("stopChildren caller %d returned while pid %d was still alive — "+
				"an emptied list is not an empty one", i, pid)
		}
	}
}

// A child started after restoration began would be stopped by nothing — it would
// outlive the sweep and could recreate exactly what the sweep deleted. start()
// refuses rather than spawning an orphan.
func TestStartRefusesAfterTeardown(t *testing.T) {
	s := &session{}
	_ = s.restore() // unarmed, but it still marks the session torn down

	c, err := s.start(exec.Command("sleep", "30"))
	if err == nil {
		_ = c.cmd.Process.Kill()
		t.Fatal("start must refuse once the session is being torn down")
	}
	if !strings.Contains(err.Error(), "torn down") {
		t.Errorf("the refusal must say why, got: %v", err)
	}
}

// The same hole on the other writer, and the one that ends a run with faulted source
// on disk: restoration captures `applied` and sets tornDown together, so a patch
// applied after that read is a patch nothing will ever revert.
//
// The session is armed with a patch path that does not exist, which is what makes the
// assertion sharp: if applyFault did not check tornDown it would reach `git apply`
// and fail with a git error about a missing file. Only the refusal says "torn down",
// so the message distinguishes "refused before touching anything" from "tried and
// failed".
func TestApplyFaultRefusesAfterTeardown(t *testing.T) {
	s := &session{}
	s.arm(t.TempDir(), filepath.Join(t.TempDir(), "never-written.patch"))
	_ = s.restore() // its own verdict is irrelevant here; it marks the session torn down

	err := s.applyFault()
	if err == nil {
		t.Fatal("applyFault must refuse once the session is being torn down")
	}
	if !strings.Contains(err.Error(), "torn down") {
		t.Fatalf("applyFault must refuse BEFORE reaching git, got: %v", err)
	}

	s.mu.Lock()
	applied := s.applied
	s.mu.Unlock()
	if applied {
		t.Fatal("a refused applyFault must not record the fault as applied")
	}
}

// A panic is the one exit that returns nowhere: it unwinds past main's
// `runErr := calibrate(s)` without ever reaching the restore() on the next line. The
// deferred recovery must therefore restore AND re-raise — restoring silently would
// trade a faulted tree for a swallowed failure, which is the same bargain in the
// other direction.
func TestRestoreOnPanicRestoresThenRepanics(t *testing.T) {
	s := &session{}
	var raised any
	func() {
		// Registered FIRST, so it runs LAST: it catches what restoreOnPanic re-raises.
		defer func() { raised = recover() }()
		defer restoreOnPanic(s)
		panic("mvn went sideways")
	}()

	if raised == nil {
		t.Fatal("the panic was swallowed — a clean tree and a silent failure is not the trade")
	}
	if got, ok := raised.(string); !ok || got != "mvn went sideways" {
		t.Errorf("the re-raised value must be the original panic, got: %v", raised)
	}
	if s.restores != 1 {
		t.Fatalf("restoration ran %d time(s) on the panic path, want exactly 1", s.restores)
	}
}

// The readiness signal is the hub's own startup line, the same one otk-gold.yml
// greps for. Parsing it here means the runner waits on the hub rather than on a
// sleep, and a boot failure surfaces as a timeout with the log attached.
func TestHubReady(t *testing.T) {
	for _, c := range []struct {
		name string
		log  string
		want bool
	}{
		{"started", "2026-09-19 INFO  [io.quarkus] lumnik-hub started in 4.213s. Listening on: http://0.0.0.0:8080", true},
		{"integer seconds are still a match", "lumnik-hub started in 12.0s.", true},
		{"still booting", "2026-09-19 INFO  [io.quarkus] Installed features: [cdi, jdbc-postgresql]", false},
		{"empty", "", false},
		{"a failure is not readiness", "ERROR: Failed to start application", false},
	} {
		t.Run(c.name, func(t *testing.T) {
			if got := hubReady(c.log); got != c.want {
				t.Fatalf("hubReady(%q) = %v, want %v", c.log, got, c.want)
			}
		})
	}
}
