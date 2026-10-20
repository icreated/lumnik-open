package resources

import (
	"sync"
	"sync/atomic"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"
)

// EditorSuspended is true while the TUI is suspended for an external editor.
// Background tickers must not QueueUpdateDraw against a suspended screen.
var EditorSuspended atomic.Bool

// deferredAfterResume holds callbacks that must not run while an editor session
// owns the terminal (they would race the screen swap on resume). SuspendEditor
// runs them once the fresh screen is live.
var (
	deferMu             sync.Mutex
	deferredAfterResume []func()
)

// DeferIfSuspended queues f to run after the current editor session finishes and
// returns true; if no editor is active, it returns false and the caller runs f
// now. This keeps a screen-touching async action (e.g. raising the auth-lost
// modal) from firing mid-swap — it runs once the fresh screen is engaged.
func DeferIfSuspended(f func()) bool {
	if !EditorSuspended.Load() {
		return false
	}
	deferMu.Lock()
	deferredAfterResume = append(deferredAfterResume, f)
	deferMu.Unlock()
	// If the session ended between the load and the lock, drain now so f is never
	// stranded (SuspendEditor's own drain may have already run).
	if !EditorSuspended.Load() {
		drainDeferred()
	}
	return true
}

func drainDeferred() {
	deferMu.Lock()
	pending := deferredAfterResume
	deferredAfterResume = nil
	deferMu.Unlock()
	for _, f := range pending {
		f()
	}
}

// SuspendEditor suspends the TUI around f, then swaps in a FRESH tcell screen
// instead of resuming the suspended one. On macOS the resumed screen comes back
// with dead input (tcell#480 class — reproduced live in the TUI rehearsal,
// carnet 2026-07-06 stumble #1); a fresh screen sidesteps the driver state.
// Best-effort: if a fresh screen cannot be created, the failure is reported
// through showError (display may be unstable).
func SuspendEditor(app *tview.Application, showError func(string), f func()) {
	EditorSuspended.Store(true)
	// Panic-safe: always clear the flag and drain the deferred actions, even if
	// app.Suspend or the swap panics — otherwise QueueUpdate would drop every
	// async update forever and DeferIfSuspended would defer indefinitely.
	defer func() {
		EditorSuspended.Store(false)
		drainDeferred()
	}()
	app.Suspend(f)
	// Swap AFTER the suspension: a swap inside it left TWO live screens — tview's
	// epilogue resumed the old one while the run loop picked the new one, and the
	// zombie stole every other keystroke (rehearsal carnet #4: sluggish arrows,
	// silent keys, eventual freeze). Here the resumed-but-dead screen is Fini'd by
	// SetScreen immediately, and the run loop engages the fresh one.
	ns, err := tcell.NewScreen()
	if err != nil {
		if showError != nil {
			showError("editor screen refresh failed (" + err.Error() + ") — display may be unstable")
		}
		return
	}
	app.SetScreen(ns)
	// SetScreen (Run already in progress) hands ns to tview's event goroutine,
	// which sets a.screen = ns and only THEN calls ns.Init(). Until Init finishes,
	// ns has a nil tty: any draw() that lands on it dereferences nil (SIGSEGV in
	// tScreen.resize) or wedges the swap — the ':q crashes/freezes lm' regression,
	// caught live via a stack trace. The defer above clears EditorSuspended (which
	// gates async draws) the instant we return, so we must not return until ns is
	// live: a background ticker/toast draw, or the run loop's own post-resume draw,
	// would otherwise race Init. ns.Size() stays (0,0) until the goroutine's
	// Init+first-draw runs; wait for that.
	//
	// Wall-clock deadline (robust to sleep-granularity jitter under load), 2s — far
	// beyond the sub-millisecond Init seen in practice. Falling through is safe: by
	// the deadline Init is long complete, so a lingering (0,0) means a *sizeless*
	// tty (pipe/headless), not a nil tty — the crash window is already past. We
	// don't showError here: the EditorSuspended gate we still hold would drop it,
	// and drawing on a maybe-unready screen is exactly what this wait avoids.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if w, _ := ns.Size(); w > 0 {
			return
		}
		time.Sleep(500 * time.Microsecond)
	}
}

// QueueUpdate is the ONLY sanctioned path for async UI updates: it drops the
// update while an editor session holds the terminal (a QueueUpdateDraw against
// a suspended screen is part of the freeze recipe this package exists to kill).
func QueueUpdate(app *tview.Application, f func()) {
	if EditorSuspended.Load() {
		return
	}
	app.QueueUpdateDraw(f)
}

// QueueUpdateAfterResume is QueueUpdate for an update that must not be lost — a follow-up that
// releases a guard or carries the answer to a gesture: while an editor session holds the terminal
// it waits for the session to end instead of being dropped. The deferred delivery is drained on the
// event loop (SuspendEditor's defer), and tview's QueueUpdate blocks until the loop runs f: queued
// from the loop itself it would wait forever, so it is queued from a goroutine — as the auth-lost
// modal is (#1155 review).
func QueueUpdateAfterResume(app *tview.Application, f func()) {
	if !DeferIfSuspended(func() { go QueueUpdate(app, f) }) {
		QueueUpdate(app, f)
	}
}
