package resources

import "github.com/rivo/tview"

// lazyDetail is a describe panel's detail that needs its own hub call. SelectedDescribe runs on the
// event loop on every arrow key, so it never waits for the hub: it asks get, which answers what was
// fetched and otherwise starts ONE fetch per key off the loop. When that lands, landed (the page's
// onSelect) redraws the open panel.
//
// What it fetches is fixed for the hub's life (a hook binding, a connector type's schema), so an
// answer is kept. A failure is kept too, said in the panel — retrying on every redraw would loop
// against a hub that is down — until retryFailed (the page's refresh, r).
//
// Every method runs on the loop (get from SelectedDescribe, the store from background's follow-up),
// so the map needs no lock.
type lazyDetail struct {
	entries map[string]*lazyEntry // present = asked
}

type lazyEntry struct {
	text         string
	done, failed bool
}

// get returns key's detail and true once fetched. fetch runs off the loop and returns the panel text
// (already escaped) or an error.
func (l *lazyDetail) get(app *tview.Application, key string, fetch func() (string, error), landed func()) (string, bool) {
	if l.entries == nil {
		l.entries = map[string]*lazyEntry{}
	}
	e, asked := l.entries[key]
	if !asked {
		e = &lazyEntry{}
		l.entries[key] = e
		background(app, func() func() {
			text, err := fetch()
			return func() {
				if err != nil {
					text, e.failed = "[red]"+tview.Escape(err.Error())+"[white]  (r retries)", true
				}
				e.text, e.done = text, true
				// A bare page in a test fetched inline: get reads the answer below, and a landed that
				// re-describes would re-enter it.
				if landed != nil && app != nil {
					landed()
				}
			}
		})
	}
	return e.text, e.done
}

// retryFailed forgets the failed fetches: the next look asks the hub again.
func (l *lazyDetail) retryFailed() {
	for key, e := range l.entries {
		if e.failed {
			delete(l.entries, key)
		}
	}
}
