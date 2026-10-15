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
