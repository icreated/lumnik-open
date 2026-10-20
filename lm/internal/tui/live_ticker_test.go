package tui

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/rivo/tview"
)

// runningApp starts the real tview event loop on a simulation screen. The loop is the goroutine
// that also reads every key, so what these tests ask is what a user feels: does a closure queued
// on it run, or is the loop stuck behind a hub call?
func runningApp(t *testing.T, server string) *App {
	t.Helper()
	a, _ := runningAppOn(t, server)
	return a
}

// runningAppOn is runningApp, with the screen it draws on.
func runningAppOn(t *testing.T, server string) (*App, tcell.SimulationScreen) {
	t.Helper()
	a := newApp(client.New(config.Context{Server: server}))
	sim := tcell.NewSimulationScreen("UTF-8")
	a.tv.SetScreen(sim)
	sim.SetSize(120, 40)
	stopped := make(chan struct{})
	go func() {
		_ = a.tv.Run()
		close(stopped)
	}()
	t.Cleanup(func() {
		a.tv.Stop()
		<-stopped
	})
	return a, sim
}

// onLoop runs f on the event loop and reports whether the loop took it within d.
func onLoop(a *App, d time.Duration, f func()) bool {
	done := make(chan struct{})
	go a.tv.QueueUpdate(func() {
		f()
		close(done)
	})
	select {
	case <-done:
		return true
	case <-time.After(d):
		return false
	}
}

// waitOnLoop polls cond on the event loop until it holds, for up to five seconds.
func waitOnLoop(t *testing.T, a *App, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var ok bool
		if onLoop(a, time.Second, func() { ok = cond() }) && ok {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

// The pages liveTicker refreshes while they are in front. Its tick used to queue switchTo on the
// loop, which ran each page's list call right there: against a slow hub every key waited on it,
// and against one that never answers, `q` with them (#891).
var liveTickerPages = []string{"sources", "inbox", "hooks", "processes", "modules", "migrations", "dlq", "endpoints"}

func TestLiveTick_neverHoldsTheEventLoopOnTheHub(t *testing.T) {
	for _, page := range liveTickerPages {
		t.Run(page, func(t *testing.T) {
			hit := make(chan string, 8)
			release := make(chan struct{})
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				select {
				case hit <- r.URL.Path:
				default:
				}
				select { // a hub that accepted the request and has not answered yet
				case <-release:
				case <-r.Context().Done():
				}
				_, _ = w.Write([]byte(`[]`))
			}))
			t.Cleanup(srv.Close)
			a := runningApp(t, srv.URL)
			t.Cleanup(func() { close(release) }) // runs first: unblock the hub, then stop the loop

			if !onLoop(a, 2*time.Second, func() {
				a.endpointsPage.SetFilter("c1", "") // :endpoints lists one connector's, as Enter on :sources leaves it
				a.pages.SwitchToPage(page)
			}) {
				t.Fatal("the event loop never started")
			}
			go a.refreshIfActive(page) // exactly what the ticker goroutine does
			select {
			case <-hit:
			case <-time.After(5 * time.Second):
				t.Fatalf("the tick never asked the hub for :%s", page)
			}
			if !onLoop(a, 2*time.Second, func() {}) {
				t.Fatalf("the event loop is stuck behind the :%s hub call — so is every key", page)
			}
		})
	}
}

// Arriving on :conflicts, :outputs or :scopes listed them on the event loop: `lm conflicts list`
// takes ~3 s on a real hub, and the whole screen froze that long — clock, keys, palette (#1171).
func TestArrival_neverHoldsTheEventLoopOnTheHub(t *testing.T) {
	for page, path := range map[string]string{
		"conflicts": "/api/entities/conflicts",
		"outputs":   "/api/platform/deliveries",
		"scopes":    "/api/platform/rag/scopes",
	} {
		t.Run(page, func(t *testing.T) {
			hit := make(chan struct{}, 8)
			release := make(chan struct{})
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == path {
					select {
					case hit <- struct{}{}:
					default:
					}
					select { // the hub accepted the list and has not answered yet
					case <-release:
					case <-r.Context().Done():
					}
				}
				_, _ = w.Write([]byte(`[]`))
			}))
			t.Cleanup(srv.Close)
			a := runningApp(t, srv.URL)
			t.Cleanup(func() { close(release) }) // runs first: unblock the hub, then stop the loop

			go a.tv.QueueUpdate(func() { a.switchTo(page) }) // no wait on it: it may be the one holding the loop
			select {
			case <-hit:
			case <-time.After(5 * time.Second):
				t.Fatalf("arriving on :%s never asked the hub", page)
			}
			if !onLoop(a, 2*time.Second, func() {}) {
				t.Fatalf("the event loop is stuck behind the :%s list — so is every key", page)
			}
		})
	}
}

// The describe panel lives in rootFlex, not in pages, so the front page still answers "sources"
// with the panel open and the tick fired anyway: through switchTo, it closed the panel and
// rewrote the footer every 5 s (#891). The tick now refreshes the page in place — and the open
// panel follows the fresh row instead of freezing on the old one.
func TestLiveTick_keepsTheDescribePanelOpenAndTheFooterError(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		status := "Running"
		if calls.Add(1) > 1 {
			status = "Completed"
		}
		_, _ = fmt.Fprintf(w, `[{"id":"c1","tenantId":1,"name":"ad_user","typeId":"csv-file","enabled":true,"status":%q}]`, status)
	}))
	t.Cleanup(srv.Close)
	a := runningApp(t, srv.URL)
	table := a.sourcesPage.View().(*tview.Table)

	onLoop(a, 2*time.Second, func() { a.switchTo("sources") })
	waitOnLoop(t, a, "the first list to render", func() bool { return table.GetRowCount() > 1 })
	onLoop(a, 2*time.Second, func() {
		table.Select(1, 0)
		a.toggleDescribe()
		a.showError("hub unreachable")
	})

	go a.refreshIfActive("sources") // the tick
	waitOnLoop(t, a, "the tick's list to land", func() bool {
		return calls.Load() >= 2 && (!a.describeOpen || strings.Contains(a.describe.bodyText(), "Completed"))
	})

	var open bool
	var body, footer string
	if !onLoop(a, 2*time.Second, func() {
		open, body, footer = a.describeOpen, a.describe.bodyText(), a.footer.tv.GetText(true)
	}) {
		t.Fatal("the event loop is stuck")
	}
	if !open {
		t.Fatal("the live tick closed the describe panel")
	}
	if !strings.Contains(body, "Completed") {
		t.Errorf("the open panel must follow the refreshed row, got:\n%s", body)
	}
	if !strings.Contains(footer, "ERROR: hub unreachable") {
		t.Errorf("the live tick overwrote the footer error, footer now %q", footer)
	}
}

// The describe panel got 40% of the screen and never the keys: a long body — a failed run's reason,
// a connector's config — was cut at the fold (#1173). Tab gives it the keys to scroll; Tab or Esc
// gives them back with the panel still open. The live tick re-describes the same row in place, and
// must not throw the reader back to the top.
func TestDescribe_TabScrollsThePanelAndGivesTheKeysBack(t *testing.T) {
	var calls atomic.Int32
	cfg := make([]string, 80)
	for i := range cfg {
		cfg[i] = fmt.Sprintf(`"key%02d":"value"`, i)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = fmt.Fprintf(w, `[{"id":"c1","tenantId":1,"name":"erp","typeId":"csv-file","enabled":true,"config":{%s}}]`,
			strings.Join(cfg, ","))
	}))
	t.Cleanup(srv.Close)
	a := runningApp(t, srv.URL)
	table := a.sourcesPage.View().(*tview.Table)
	onLoop(a, 2*time.Second, func() { a.switchTo("sources") })
	waitOnLoop(t, a, "the list", func() bool { return table.GetRowCount() > 1 })
	onLoop(a, 2*time.Second, func() {
		table.Select(1, 0)
		a.toggleDescribe()
	})
	press := func(k tcell.Key) { a.tv.QueueEvent(tcell.NewEventKey(k, 0, tcell.ModNone)) }
	scrolled := func() int { row, _ := a.describe.body.GetScrollOffset(); return row }

	press(tcell.KeyTab)
	waitOnLoop(t, a, "Tab to give the panel the keys", func() bool { return a.tv.GetFocus() == a.describe.body })
	press(tcell.KeyPgDn)
	waitOnLoop(t, a, "PgDn to scroll the panel", func() bool { return scrolled() > 0 })

	go a.refreshIfActive("sources") // the tick
	waitOnLoop(t, a, "the tick's list", func() bool { return calls.Load() >= 2 })
	waitOnLoop(t, a, "the loop to drain", func() bool { return true })
	var at int
	onLoop(a, time.Second, func() { at = scrolled() })
	if at == 0 {
		t.Error("the live tick re-described the same row and scrolled the panel back to the top")
	}

	press(tcell.KeyTab)
	waitOnLoop(t, a, "Tab to give the keys back", func() bool { return a.tv.GetFocus() == table && a.describeOpen })
	press(tcell.KeyTab)
	waitOnLoop(t, a, "Tab into the panel again", func() bool { return a.tv.GetFocus() == a.describe.body })
	press(tcell.KeyEscape)
	waitOnLoop(t, a, "Esc to give the keys back, the panel open", func() bool {
		return a.tv.GetFocus() == table && a.describeOpen
	})
}

// Kept open across ticks, the panel must not outlive its row: when the refreshed list fails and
// :inbox draws the error instead of the rows, the panel closes rather than present a decision
// that is no longer on screen.
func TestLiveTick_closesTheDescribePanelWhenItsRowIsGone(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) > 1 {
			http.Error(w, "down", http.StatusInternalServerError)
			return
		}
		_, _ = w.Write([]byte(`[{"id":1,"message":"x"}]`))
	}))
	t.Cleanup(srv.Close)
	a := runningApp(t, srv.URL)
	table := a.inboxPage.View().(*tview.Table)

	onLoop(a, 2*time.Second, func() { a.switchTo("inbox") })
	waitOnLoop(t, a, "the first list to render", func() bool { return table.GetRowCount() > 1 })
	onLoop(a, 2*time.Second, func() {
		table.Select(1, 0)
		a.toggleDescribe()
	})

	go a.refreshIfActive("inbox") // the tick
	waitOnLoop(t, a, "the failed list to land", func() bool { return table.GetRowCount() == 1 })

	var open bool
	if !onLoop(a, 2*time.Second, func() { open = a.describeOpen }) {
		t.Fatal("the event loop is stuck")
	}
	if open {
		t.Error("the describe panel outlived its row: the list failed and the row is gone")
	}
}

// Every connector.run.* event refreshed :runs whichever page was in front — the expensive RunList
// of #892, twice per endpoint run, for a page nobody was looking at. switchTo("runs") refetches on
// arrival, so a hidden :runs has nothing to keep fresh; in front, an event still refreshes it.
func TestRunEvent_refreshesRunsOnlyInFront(t *testing.T) {
	runsHit := make(chan struct{}, 8)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/platform/runs" {
			select {
			case runsHit <- struct{}{}:
			default:
			}
		}
		_, _ = w.Write([]byte(`[]`))
	}))
	t.Cleanup(srv.Close)
	a := runningApp(t, srv.URL)
	event := client.SSEEvent{Data: `{"topic":"connector.run.completed"}`}

	onLoop(a, 2*time.Second, func() { a.pages.SwitchToPage("sources") })
	a.dispatchEvent(event)
	select {
	case <-runsHit:
		t.Fatal("a run event fetched :runs while it was hidden")
	case <-time.After(300 * time.Millisecond):
	}

	onLoop(a, 2*time.Second, func() { a.switchTo("runs") })
	select {
	case <-runsHit:
	case <-time.After(5 * time.Second):
		t.Fatal("arriving on :runs must refetch it")
	}

	a.dispatchEvent(event)
	select {
	case <-runsHit:
	case <-time.After(5 * time.Second):
		t.Fatal("a run event must still refresh :runs in front")
	}
}

// A run ending is when a connector's status changes and dead letters appear: the page in front
// hears it at once instead of waiting for its tick (#1109).
func TestRunEvent_refreshesThePageInFront(t *testing.T) {
	for page, path := range map[string]string{"sources": "/api/platform/connectors", "dlq": "/api/platform/dlq"} {
		t.Run(page, func(t *testing.T) {
			hit := make(chan string, 8)
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				select {
				case hit <- r.URL.Path:
				default:
				}
				_, _ = w.Write([]byte(`[]`))
			}))
			t.Cleanup(srv.Close)
			a := runningApp(t, srv.URL)
			if !onLoop(a, 2*time.Second, func() { a.pages.SwitchToPage(page) }) {
				t.Fatal("the event loop never started")
			}
			go a.dispatchEvent(client.SSEEvent{Data: `{"topic":"connector.run.completed"}`})
			deadline := time.After(5 * time.Second)
			for {
				select {
				case p := <-hit:
					if strings.HasPrefix(p, path) {
						return
					}
				case <-deadline:
					t.Fatalf("a run event never refreshed :%s", page)
				}
			}
		})
	}
}

// An open confirmation owns the keyboard: q must not quit, : must not open the palette (#1110).
func TestConfirm_globalKeysDoNotFireWhileOpen(t *testing.T) {
	a := runningApp(t, "http://127.0.0.1:0")
	if !onLoop(a, 2*time.Second, func() { a.confirmAs("Delete connector \"x\"?", "Delete", func() {}) }) {
		t.Fatal("the event loop never started")
	}
	var focus any
	onLoop(a, time.Second, func() { focus = a.tv.GetFocus() })
	ev := tcell.NewEventKey(tcell.KeyRune, 'q', tcell.ModNone)
	var passed *tcell.EventKey
	onLoop(a, time.Second, func() { passed = a.tv.GetInputCapture()(ev) })
	if passed == nil {
		t.Fatalf("q was swallowed by the global keys while the confirmation is open (focus %T)", focus)
	}
}

// A mutating action waits for the hub off the event loop: an endpoint toggle (^P) the hub accepted
// and has not answered yet must not hold the keys (#1126).
func TestMutation_neverHoldsTheEventLoopOnTheHub(t *testing.T) {
	hit := make(chan string, 8)
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet {
			_, _ = w.Write([]byte(`[{"id":"e1","name":"orders","enabled":true}]`))
			return
		}
		select {
		case hit <- r.URL.Path:
		default:
		}
		select {
		case <-release:
		case <-r.Context().Done():
		}
		_, _ = w.Write([]byte(`{"deleted":1}`))
	}))
	t.Cleanup(srv.Close)
	a := runningApp(t, srv.URL)
	t.Cleanup(func() { close(release) })

	if !onLoop(a, 2*time.Second, func() {
		a.endpointsPage.SetFilter("c1", "")
		a.switchTo("endpoints")
	}) {
		t.Fatal("the event loop never started")
	}
	waitOnLoop(t, a, "the endpoints to render", func() bool {
		return a.endpointsPage.View().(*tview.Table).GetRowCount() > 1
	})
	onLoop(a, time.Second, func() {
		a.endpointsPage.View().(*tview.Table).Select(1, 0)
		a.tv.QueueEvent(tcell.NewEventKey(tcell.KeyCtrlP, 0, tcell.ModNone))
	})
	select {
	case <-hit:
	case <-time.After(5 * time.Second):
		t.Fatal("^P never reached the hub")
	}
	if !onLoop(a, 2*time.Second, func() {}) {
		t.Fatal("the event loop is stuck behind the endpoint toggle — so is every key")
	}
}
