package resources

import (
	"sync"

	"github.com/rivo/tview"
)

// offLoop runs a page's hub fetch OFF the tview event loop — the goroutine that also reads every
// key — and lands the render through QueueUpdate. :runs learned it first: a blocking RunList on
// the loop, re-triggered by every live event, starved input and froze the TUI under a burst
// (rehearsal carnet #9). The live ticker's pages made the same call on the loop every few seconds
// (#891).
//
// At most one fetch is in flight per page. A refresh asked meanwhile only marks the page dirty,
// which buys exactly one more round once the current one lands: a burst costs two fetches, and
// the last request's state is never left unrendered.
type offLoop struct {
	mu       sync.Mutex
	fetching bool
	dirty    bool
}

// run calls fetch off the loop and queues the render it returns. fetch runs again for a dirty
// round, so it must read the page's question (filters, toggles) afresh each time. A page built
// without an app — a bare page in a test — runs both inline.
func (o *offLoop) run(app *tview.Application, fetch func() (render func())) {
	if app == nil {
		fetch()()
		return
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.fetching {
		o.dirty = true
		return
	}
	o.fetching = true
	go func() {
		for {
			QueueUpdate(app, fetch())
			// Check and release under ONE lock: a refresh landing between a dirty check and a
			// separate release would raise dirty on a flight that is already gone.
			o.mu.Lock()
			again := o.dirty
			o.dirty, o.fetching = false, again
			o.mu.Unlock()
			if !again {
				return
			}
		}
	}()
}

// background runs one hub call off the event loop and lands the follow-up it returns (a toast, an
// error, a Refresh) back on it — the mutating twin of offLoop.run, without its single flight: each
// key press is its own action. An action used to wait for the hub on the loop, so a long purge or a
// hub that stopped answering held every key with it (#1126). A bare page in a test (no app) runs
// both inline.
//
// A follow-up that comes back while $EDITOR holds the terminal waits for the editor to close
// (QueueUpdateAfterResume): QueueUpdate would drop it, and with it the release of an inFlight key
// (#1147).
func background(app *tview.Application, work func() (then func())) {
	if app == nil {
		if then := work(); then != nil {
			then()
		}
		return
	}
	go func() {
		if then := work(); then != nil { // a call with nothing to say afterwards lands nothing
			QueueUpdateAfterResume(app, then)
		}
	}()
}

// inFlight is a page's guard on its own actions (#1141, every page since #1147). background lets
// every press through, so a quick double ^P sent the same toggle twice and a double e opened the
// editor twice. While the action a key started is still out — the hub has not answered, or its
// follow-up has not landed — the same key on the same page is dropped, and the page says so. A
// gesture that asks first (a confirmation, a prompt, the editor) checks busy before asking.
type inFlight struct {
	mu      sync.Mutex
	running map[string]bool
}

// busy reports whether key's action is still out, and tells tell when it is.
func (f *inFlight) busy(key string, tell func(string)) bool {
	f.mu.Lock()
	out := f.running[key]
	f.mu.Unlock()
	if out {
		say(tell, stillInProgress(key))
	}
	return out
}

// stillInProgress is what a page says of a dropped press of key.
func stillInProgress(key string) string { return key + ": still in progress — waiting for the hub" }

// background is the package's background for the action key starts, unless that action is still
// out: then the press is dropped and tell hears it. The key is freed on the loop, right before the
// follow-up lands, so a press read after the follow-up starts a new action.
func (f *inFlight) background(app *tview.Application, key string, tell func(string), work func() (then func())) {
	if f.busy(key, tell) { // keys are read on the one loop: no other press claims key before the next line
		return
	}
	f.mu.Lock()
	if f.running == nil {
		f.running = map[string]bool{}
	}
	f.running[key] = true
	f.mu.Unlock()
	background(app, func() func() {
		then := work()
		return func() {
			f.mu.Lock()
			delete(f.running, key)
			f.mu.Unlock()
			if then != nil {
				then()
			}
		}
	})
}

// Background is background for the App's own flows outside a page (onboarding, #1117).
func Background(app *tview.Application, work func() (then func())) { background(app, work) }
