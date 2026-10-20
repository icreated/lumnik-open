package resources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/rivo/tview"
)

// heldPress is one page key pressed twice while the hub still holds the first request (#1141,
// #1147).
type heldPress struct {
	held  string            // "METHOD /path?query" the hub holds until the test lets it go
	lists map[string]string // the hub's answer to a GET, by path; anything else is answered {}
	// open builds the page on the loop and returns its press, run on the loop too. tell is where
	// the page says a press was dropped; asked is called by every question the gesture asks (a
	// confirmation, a prompt, the editor), so a dropped press can be seen asking nothing.
	open func(app *tview.Application, c *client.Client, tell func(string), asked func()) (press func())
}

// run asserts the double press sends once: the second press is dropped and says the first is in
// progress, without asking anything, and once the hub answers the key sends again.
func (h heldPress) run(t *testing.T) {
	t.Helper()
	t.Parallel() // each press has its own app and hub; no test that sets EditorSuspended is parallel
	var sent, questions atomic.Int32
	arrived, release := make(chan struct{}, 8), make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method+" "+r.URL.RequestURI() == h.held {
			sent.Add(1)
			select {
			case arrived <- struct{}{}:
			default:
			}
			<-release // the hub holds the request
		}
		if body, ok := h.lists[r.URL.Path]; ok && r.Method == http.MethodGet {
			_, _ = w.Write([]byte(body))
			return
		}
		_, _ = w.Write([]byte(`{}`))
	}))
	t.Cleanup(srv.Close)

	app := runningSimApp(t)
	var mu sync.Mutex
	var said []string // everything the page told, read under mu: follow-ups tell from the loop
	tell := func(m string) { mu.Lock(); said = append(said, m); mu.Unlock() }
	inProgress := func() bool {
		mu.Lock()
		defer mu.Unlock()
		for _, m := range said {
			if strings.Contains(m, "in progress") {
				return true
			}
		}
		return false
	}
	onLoop := func(f func()) { // keys are read on the loop: press them there
		done := make(chan struct{})
		QueueUpdate(app, func() { f(); close(done) })
		<-done
	}
	var press func()
	onLoop(func() {
		press = h.open(app, client.New(config.Context{Server: srv.URL}), tell, func() { questions.Add(1) })
	})

	var once sync.Once
	free := func() { once.Do(func() { close(release) }) }
	t.Cleanup(free) // runs first: a failed test must not leave srv.Close waiting on a held request

	onLoop(press)
	select {
	case <-arrived:
	case <-time.After(5 * time.Second):
		t.Fatalf("the first press never reached %s", h.held)
	}
	asked := questions.Load()
	onLoop(press) // the hub still holds the first
	deadline := time.Now().Add(5 * time.Second)
	for !inProgress() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if !inProgress() {
		mu.Lock() // copy, then fail unlocked: a follow-up still tells from the loop during cleanup
		got := append([]string(nil), said...)
		mu.Unlock()
		t.Fatalf("the dropped press did not say the first is in progress; the page said %q", got)
	}
	if n := questions.Load(); n != asked {
		t.Errorf("the dropped press asked %d question(s): a press that would be dropped asks nothing", n-asked)
	}
	free()
	select {
	case <-arrived:
		t.Fatalf("a second press reached %s while the first was held", h.held)
	case <-time.After(300 * time.Millisecond):
	}
	if n := sent.Load(); n != 1 {
		t.Fatalf("%d requests reached the hub, want 1", n)
	}

	// the first has landed: the key is free again
	deadline = time.Now().Add(5 * time.Second)
	for sent.Load() < 2 && time.Now().Before(deadline) {
		onLoop(press)
		time.Sleep(50 * time.Millisecond)
	}
	if sent.Load() < 2 {
		t.Fatal("after the hub answered, the key never sent again")
	}
}

// runningSimApp is a tview application running on a simulation screen, stopped at cleanup.
func runningSimApp(t *testing.T) *tview.Application {
	app := tview.NewApplication()
	app.SetScreen(tcell.NewSimulationScreen("UTF-8"))
	stopped := make(chan struct{})
	go func() { _ = app.Run(); close(stopped) }()
	t.Cleanup(func() { app.Stop(); <-stopped })
	return app
}

// onLoopWithin runs f on the loop and reports whether it ran within 2s: a loop held by the hub never
// runs it. app.QueueUpdate, not QueueUpdate: that one drops under an open editor.
func onLoopWithin(app *tview.Application, f func()) bool {
	done := make(chan struct{})
	go app.QueueUpdate(func() { f(); close(done) })
	select {
	case <-done:
		return true
	case <-time.After(2 * time.Second):
		return false
	}
}

// deferredHeld waits up to 5s for a delivery held for the editor's close, and reports whether one is.
func deferredHeld() bool {
	held := func() bool { deferMu.Lock(); defer deferMu.Unlock(); return len(deferredAfterResume) > 0 }
	deadline := time.Now().Add(5 * time.Second)
	for !held() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	return held()
}

// pressOf is a press of k (r for a rune) on a page's key handler.
func pressOf(handle func(*tcell.EventKey) *tcell.EventKey, k tcell.Key, r rune) func() {
	return func() { handle(tcell.NewEventKey(k, r, tcell.ModNone)) }
}

// confirmAsYes answers every confirmation yes, and counts it.
func confirmAsYes(asked func()) ConfirmAs { return func(_, _ string, y func()) { asked(); y() } }

// promptWith types the same line into every prompt, and counts it.
func promptWith(asked func(), line string) Prompt {
	return func(_ string, _ bool, done func(string)) { asked(); done(line) }
}

// A double press of any page action sent through the background — a direct key, a key behind a
// confirmation, a key behind a prompt — sends it once while the hub holds the first (#1147).
func TestEveryPage_aDoublePressWhileTheHubHoldsTheFirstSendsItOnce(t *testing.T) {
	connectors := map[string]string{"/api/platform/connectors": `[{"id":"c1","name":"ad_user","typeId":"csv-file","enabled":true}]`}
	for name, c := range map[string]heldPress{
		"processes ^P": {held: "PUT /api/processes/gold-arrive/enabled",
			lists: map[string]string{"/api/processes": `[{"name":"gold-arrive","enabled":true}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), _ func()) func() {
				p := NewManifests(nil, ProcessesSource(cl), tell, nil)
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyCtrlP, 0)
			}},
		"processes e": {held: "GET /api/processes/gold-arrive/manifest",
			lists: map[string]string{"/api/processes": `[{"name":"gold-arrive","enabled":true}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), asked func()) func() {
				p := NewManifests(nil, ProcessesSource(cl), tell, nil)
				p.SetOnEdit(func(string, []byte) { asked() })
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyRune, 'e')
			}},
		"sources ^R": {held: "POST /api/platform/connectors/c1/run", lists: connectors,
			open: func(app *tview.Application, cl *client.Client, tell func(string), _ func()) func() {
				p := NewSources(nil, cl, nil, tell)
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyCtrlR, 0)
			}},
		"view a": {held: "POST /api/views/apply",
			open: func(app *tview.Application, cl *client.Client, tell func(string), _ func()) func() {
				v := NewViewPage(nil, cl, nil, nil, nil, nil, tell, nil)
				v.OpenManifest("clients", []byte("kind: View\n")) // no app yet: rendered inline
				v.app = app
				return pressOf(v.handleListKey, tcell.KeyRune, 'a')
			}},
		"sources e": {held: "GET /api/platform/connectors/c1", lists: connectors,
			open: func(app *tview.Application, cl *client.Client, tell func(string), asked func()) func() {
				p := NewSources(nil, cl, nil, tell)
				p.suspend = func(func()) { asked() } // the editor: counted, never run
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyRune, 'e')
			}},
		"sources y": {held: "GET /api/platform/connectors/c1", lists: connectors,
			open: func(app *tview.Application, cl *client.Client, tell func(string), _ func()) func() {
				p := NewSources(nil, cl, nil, tell)
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return func() { p.YankLater(func(string) {}) } // y is the App's key: it calls YankLater
			}},
		"sources Del": {held: "DELETE /api/platform/connectors/c1?dryRun=true", lists: connectors, // the question waits on it (#1218)
			open: func(app *tview.Application, cl *client.Client, tell func(string), asked func()) func() {
				p := NewSources(nil, cl, nil, tell)
				p.SetConfirm(confirmAsYes(asked))
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyDelete, 0)
			}},
		"endpoints C": {held: "POST /api/platform/connectors/c1/endpoints/e1/reset-cursor",
			lists: map[string]string{"/api/platform/connectors/c1/endpoints": `[{"id":"e1","connectorId":"c1","name":"orders","enabled":true}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), asked func()) func() {
				p := NewEndpoints(nil, cl, tell, nil)
				p.SetConfirm(confirmAsYes(asked))
				p.connectorID = "c1"
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyRune, 'C')
			}},
		"dlq ^D": {held: "POST /api/platform/dlq/d1/discard",
			lists: map[string]string{"/api/platform/dlq": `[{"id":"d1","connectorId":"c1"}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), _ func()) func() {
				p := NewDLQ(nil, cl, tell)
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyCtrlD, 0)
			}},
		"runs c": {held: "POST /api/platform/runs/r1/cancel",
			lists: map[string]string{"/api/platform/runs": `[{"id":"r1","connectorId":"c1","status":"RUNNING"}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), _ func()) func() {
				p := NewRuns(nil, cl, tell)
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyRune, 'c')
			}},
		"secrets e": {held: "PUT /api/platform/secrets/db-pass",
			lists: map[string]string{"/api/platform/secrets": `[{"id":"s1","name":"db-pass"}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), asked func()) func() {
				p := NewSecrets(nil, cl, tell, nil)
				p.SetAsk(promptWith(asked, "hunter2"))
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyRune, 'e')
			}},
		"entities Del": {held: "DELETE /api/entities/Customer",
			lists: map[string]string{"/api/entities": `[{"name":"Customer"}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), asked func()) func() {
				p := NewEntities(nil, cl, tell, nil)
				p.SetConfirm(confirmAsYes(asked))
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyDelete, 0)
			}},
		"entities ^R": {held: "POST /api/entities/Customer/resolve",
			open: func(app *tview.Application, cl *client.Client, tell func(string), _ func()) func() {
				p := NewEntities(nil, cl, tell, nil)
				p.entity = "Customer"
				p.app = app
				return pressOf(p.handleKey, tcell.KeyCtrlR, 0)
			}},
		"outputs ^R": {held: "POST /api/platform/deliveries/7/replay",
			lists: map[string]string{"/api/platform/deliveries": `[{"id":7,"succeeded":false}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), _ func()) func() {
				p := NewOutputs(nil, cl, nil, tell)
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyCtrlR, 0)
			}},
		"exports R": {held: "POST /api/exports/monthly/runs",
			lists: map[string]string{"/api/exports": `[{"name":"monthly"}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), _ func()) func() {
				p := NewExports(nil, cl, tell, nil)
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyRune, 'R')
			}},
		"search Del": {held: "DELETE /api/search-indexes/orders",
			lists: map[string]string{"/api/platform/search/index": `[{"name":"orders"}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), asked func()) func() {
				p := NewSearch(nil, cl, nil, tell, nil)
				p.SetConfirm(confirmAsYes(asked))
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyDelete, 0)
			}},
		"integrations T": {held: "POST /api/platform/integrations/3/test",
			lists: map[string]string{"/api/platform/integrations": `[{"id":3,"name":"crm","targetUrl":"https://crm.example/hook"}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), asked func()) func() {
				p := NewIntegrations(nil, cl, tell, nil)
				p.SetAsk(promptWith(asked, "org.created"))
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyRune, 'T')
			}},
		"notify Del": {held: "DELETE /api/notifications/4",
			lists: map[string]string{"/api/notifications": `[{"id":4,"eventPattern":"decision.*","recipient":"ops@example.com"}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), asked func()) func() {
				p := NewNotify(nil, cl, tell, nil)
				p.SetConfirm(confirmAsYes(asked))
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyDelete, 0)
			}},
		"rag R": {held: "POST /api/platform/rag/reindex",
			lists: map[string]string{"/api/platform/modules": ragActive}, // the refresh after R keeps R open
			open: func(app *tview.Application, cl *client.Client, tell func(string), asked func()) func() {
				p := NewRag(app, cl, nil, tell)
				p.SetConfirm(confirmAsYes(asked))
				return pressOf(p.handleKey, tcell.KeyRune, 'R')
			}},
		"inbox a": {held: "POST /api/inbox/9/ack",
			lists: map[string]string{"/api/inbox": `[{"id":9,"message":"late order"}]`},
			open: func(app *tview.Application, cl *client.Client, tell func(string), _ func()) func() {
				p := NewInbox(nil, cl, nil, tell)
				p.Refresh()
				p.app = app
				p.table.Select(1, 0)
				return pressOf(p.handleKey, tcell.KeyRune, 'a')
			}},
		"ambiguous ^R": {held: "POST /api/entities/Customer/resolve",
			open: func(app *tview.Application, cl *client.Client, tell func(string), _ func()) func() {
				p := NewAmbiguous(app, cl, nil, tell)
				p.active.entity = "Customer"
				return pressOf(p.handleKey, tcell.KeyCtrlR, 0)
			}},
		"new e": {held: "POST /api/processes/apply",
			open: func(app *tview.Application, cl *client.Client, tell func(string), asked func()) func() {
				p := NewNewKind(cl, func(f func()) { f() }, nil, tell, nil, nil)
				p.SetApp(app)
				// an applied turn empties the buffer: the editor writes the manifest anew each time
				p.editFn = func(string, string) (string, bool, error) {
					asked()
					return "kind: Process\nname: gold-arrive\n", true, nil
				}
				p.current = p.kinds[0]
				return pressOf(p.handleIssuesKey, tcell.KeyRune, 'e')
			}},
	} {
		t.Run(name, c.run)
	}
}

// QueueUpdate drops an update while $EDITOR holds the terminal, and a dropped follow-up took its
// key's release with it: after e on :sources, a ^R landing under the editor would have said
// "in progress" for the rest of the session. The follow-up now lands once the editor closes.
func TestInFlight_aFollowUpUnderAnOpenEditorLandsOnceItCloses(t *testing.T) {
	app := runningSimApp(t)
	EditorSuspended.Store(true)
	t.Cleanup(func() { EditorSuspended.Store(false) })

	var f inFlight
	landed := make(chan struct{})
	f.background(app, "^R", nil, func() func() { return func() { close(landed) } })
	if !deferredHeld() {
		t.Fatal("the follow-up was dropped under the editor instead of held for its close")
	}
	// SuspendEditor drains in its defer, ON the event loop: drain there too. A deferred follow-up that
	// blocked on the loop from the loop froze lm ui for good (#1155 review).
	go app.QueueUpdate(func() { EditorSuspended.Store(false); drainDeferred() })
	select {
	case <-landed:
	case <-time.After(5 * time.Second):
		t.Fatal("the follow-up never landed after the editor closed")
	}
	if f.busy("^R", nil) {
		t.Error("the key is still in progress after its follow-up landed")
	}
}
