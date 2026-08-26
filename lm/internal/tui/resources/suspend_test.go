package resources

import "testing"

func TestDeferIfSuspended_notSuspended_runsNow(t *testing.T) {
	EditorSuspended.Store(false)
	deferredAfterResume = nil
	if DeferIfSuspended(func() {}) {
		t.Fatal("with no editor active, DeferIfSuspended must return false (caller runs f now)")
	}
	if len(deferredAfterResume) != 0 {
		t.Fatal("nothing should be queued when not suspended")
	}
}

func TestDeferIfSuspended_suspended_queuesThenDrains(t *testing.T) {
	EditorSuspended.Store(true)
	deferredAfterResume = nil
	ran := 0
	if !DeferIfSuspended(func() { ran++ }) {
		t.Fatal("with an editor active, DeferIfSuspended must return true (deferred)")
	}
	if ran != 0 {
		t.Fatal("must NOT run while suspended")
	}
	// Simulate SuspendEditor finishing: clear the flag, drain.
	EditorSuspended.Store(false)
	drainDeferred()
	if ran != 1 {
		t.Fatalf("deferred callback must run once after resume, ran=%d", ran)
	}
	drainDeferred()
	if ran != 1 {
		t.Fatalf("drain must not re-run, ran=%d", ran)
	}
}
