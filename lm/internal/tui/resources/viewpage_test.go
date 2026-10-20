package resources

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/rivo/tview"
)

// The view page delivered through QueueUpdate, which drops an update while $EDITOR holds the
// terminal (#1158). Its deliveries are queued off the loop (generate's spawn); one queued under an
// open editor now lands once the editor closes — drained on the loop, as SuspendEditor drains.
func TestViewPage_anUpdateUnderAnOpenEditorLandsOnceItCloses(t *testing.T) {
	app := runningSimApp(t)
	EditorSuspended.Store(true)
	t.Cleanup(func() { EditorSuspended.Store(false) })

	v := NewViewPage(app, nil, nil, nil, nil, nil, nil, nil)
	landed := make(chan struct{})
	go v.enqueue(func() { close(landed) })
	if !deferredHeld() {
		t.Fatal("the update was dropped under the editor instead of held for its close")
	}
	onLoopWithin(app, func() { EditorSuspended.Store(false); drainDeferred() })
	select {
	case <-landed:
	case <-time.After(5 * time.Second):
		t.Fatal("the update never landed after the editor closed")
	}
}

// :view rendered, drilled into and applied on the event loop, "a single quick round-trip" — but a
// render runs a query over an entity that nothing client-side bounds, and the screen froze with it.
// Each of the three calls now waits for the hub off the loop (#1171).
func TestViewPage_hubCallsNeverHoldTheEventLoop(t *testing.T) {
	for held, press := range map[string]func(v *ViewPage) func(){
		"/api/views/render": nil, // the render itself: OpenManifest
		"/api/views/detail": func(v *ViewPage) func() { return pressOf(v.handleListKey, tcell.KeyEnter, 0) },
		"/api/views/apply":  func(v *ViewPage) func() { return pressOf(v.handleListKey, tcell.KeyRune, 'a') },
	} {
		t.Run(held, func(t *testing.T) {
			arrived, release := make(chan struct{}, 8), make(chan struct{})
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == held {
					arrived <- struct{}{}
					<-release // the hub holds the call
				}
				switch r.URL.Path {
				case "/api/views/render":
					_, _ = w.Write([]byte(`{"title":"Clients","columns":[{"name":"code","label":"Code"}],"rows":[{"code":"C-1"}]}`))
				case "/api/views/detail":
					_, _ = w.Write([]byte(`{"title":"Clients","key":"C-1","fields":[]}`))
				default:
					_, _ = w.Write([]byte(`{"name":"clients"}`))
				}
			}))
			t.Cleanup(srv.Close)
			app := runningSimApp(t)
			var once sync.Once
			free := func() { once.Do(func() { close(release) }) }
			t.Cleanup(free) // runs first: a failed test must not leave srv.Close waiting on a held request

			var v *ViewPage
			onLoopWithin(app, func() {
				v = NewViewPage(app, client.New(config.Context{Server: srv.URL}), nil, nil, nil, nil, nil, nil)
			})
			open := func() { v.OpenManifest("clients", []byte("kind: View\n")) }
			if press == nil {
				go app.QueueUpdate(open) // no wait on it: it may be the one holding the loop
			} else {
				onLoopWithin(app, open)
				deadline := time.Now().Add(5 * time.Second)
				listed := false
				for !listed && time.Now().Before(deadline) {
					onLoopWithin(app, func() { listed = v.state == viewList })
				}
				if !listed {
					t.Fatal("the view never rendered")
				}
				p := press(v)
				go app.QueueUpdate(func() { v.list.Select(1, 0); p() })
			}
			select {
			case <-arrived:
			case <-time.After(5 * time.Second):
				t.Fatalf("%s never reached the hub", held)
			}
			if !onLoopWithin(app, func() {}) {
				t.Fatalf("the loop is held while the hub holds %s", held)
			}
		})
	}
}

// A render is "latest wins", not "drop the second press": :views Enter on a second view runs
// switchTo("view") — a Refresh re-rendering the view on screen — then OpenManifest of the new one.
// The new one was dropped as a double press, and the old view stayed (#1179 review). An answer
// that lands after a newer render was asked is ignored, whichever answers first.
func TestViewPage_theLatestRenderWins(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		title, delay := "A", 150*time.Millisecond // the older render answers last
		switch {
		case r.URL.Query().Get("filter") != "":
			title, delay = "A filtered", 20*time.Millisecond
		case strings.Contains(string(body), "view-b"):
			title, delay = "B", 20*time.Millisecond
		}
		time.Sleep(delay)
		_, _ = fmt.Fprintf(w, `{"title":%q,"columns":[{"name":"code","label":"Code"}],"rows":[{"code":"C-1"}]}`, title)
	}))
	t.Cleanup(srv.Close)
	app := runningSimApp(t)
	var v *ViewPage
	onLoopWithin(app, func() {
		v = NewViewPage(app, client.New(config.Context{Server: srv.URL}), nil, nil, nil, nil, nil, nil)
	})
	shows := func(title string) {
		t.Helper()
		var got string
		deadline := time.Now().Add(5 * time.Second)
		for time.Now().Before(deadline) {
			onLoopWithin(app, func() {
				if v.result != nil && v.state == viewList {
					got = v.result.Title
				}
			})
			if got == title {
				time.Sleep(300 * time.Millisecond) // the older, slower answer has landed by now
				onLoopWithin(app, func() { got = v.result.Title })
				if got != title {
					t.Fatalf("a stale render overwrote %q with %q", title, got)
				}
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
		t.Fatalf("the page shows %q, want %q", got, title)
	}

	onLoopWithin(app, func() { v.OpenManifest("a", []byte("kind: View\nname: view-a\n")) })
	shows("A")

	onLoopWithin(app, func() { // :views Enter on a second view
		v.Refresh()
		v.OpenManifest("b", []byte("kind: View\nname: view-b\n"))
	})
	shows("B")
	var path string
	onLoopWithin(app, func() { path = v.path })
	if path != "hub:b" {
		t.Errorf("path = %q, want hub:b", path)
	}

	onLoopWithin(app, func() { v.OpenManifest("a", []byte("kind: View\nname: view-a\n")) })
	shows("A")
	onLoopWithin(app, func() { // r, then a filter before r answers
		pressOf(v.handleListKey, tcell.KeyRune, 'r')()
		v.render(v.manifest, append(slices.Clone(v.filters), "code=C-1"), nil)
	})
	shows("A filtered")
	var filters []string
	onLoopWithin(app, func() { filters = v.filters })
	if !slices.Equal(filters, []string{"code=C-1"}) {
		t.Errorf("filters = %q, want the filter the latest render asked", filters)
	}
}

func tviewTableWithOneRow() *tview.Table {
	tb := tview.NewTable().SetSelectable(true, false)
	tb.SetCell(0, 0, tview.NewTableCell("CODE"))
	tb.SetCell(1, 0, tview.NewTableCell("x"))
	tb.Select(1, 0)
	return tb
}

func TestDetailBody(t *testing.T) {
	d := client.ViewDetailResult{
		Title: "Clients", Key: "C-200",
		Fields: []client.DetailField{
			{Name: "customer_code", Label: "Code", Value: "C-200"},
			{Name: "name", Label: "Name", Value: "Marie"},
		},
		ConflictLedger: true,
		Conflicts: []client.DetailConflict{
			{Column: "name", Sources: []client.DetailSource{
				{Src: "cust_siebel", Value: client.StrPtr("Marie")}, {Src: "t_cust_csv", Value: client.StrPtr("MARIE A.")}}},
		},
	}
	out := detailBody(d)
	for _, want := range []string{"C-200", "Marie", "name", "cust_siebel", "MARIE A."} {
		if !strings.Contains(out, want) {
			t.Errorf("detailBody missing %q:\n%s", want, out)
		}
	}
	d.Conflicts = nil
	if out := detailBody(d); !strings.Contains(out, "no conflicts") {
		t.Errorf("clean ledger must say so:\n%s", out)
	}
}

// Server data containing tview color tags must render literally, not as markup.
func TestDetailBodyEscapesColorTags(t *testing.T) {
	d := client.ViewDetailResult{Title: "T", Key: "K",
		Fields: []client.DetailField{{Name: "n", Label: "N", Value: "[red]evil"}}}
	out := detailBody(d)
	if strings.Contains(out, "[red]evil") {
		t.Errorf("unescaped color tag in output:\n%s", out)
	}
	if !strings.Contains(out, tview.Escape("[red]evil")) {
		t.Errorf("escaped value missing:\n%s", out)
	}
}

func TestPushRecent(t *testing.T) {
	r := pushRecent(nil, "a.yaml", 3)
	r = pushRecent(r, "b.yaml", 3)
	r = pushRecent(r, "a.yaml", 3) // dedup to front
	if len(r) != 2 || r[0] != "a.yaml" || r[1] != "b.yaml" {
		t.Fatalf("dedup-front failed: %v", r)
	}
	r = pushRecent(r, "c.yaml", 3)
	r = pushRecent(r, "d.yaml", 3) // cap
	if len(r) != 3 || r[0] != "d.yaml" {
		t.Fatalf("cap failed: %v", r)
	}
}

func TestViewPageEmptyDescribe(t *testing.T) {
	if _, _, ok := (&ViewPage{}).SelectedDescribe(); ok {
		t.Error("no selection on an uninitialized page")
	}
}

// A selected-but-empty server row must not panic describe/yank (review round 1).
func TestViewPageEmptyRowDescribe(t *testing.T) {
	v := &ViewPage{list: tviewTableWithOneRow(), result: &client.ViewResult{
		Columns: []client.ViewColumn{{Name: "code", Label: "Code"}},
		Rows:    [][]string{{}}, // server sent a row with zero cells
	}}
	if _, _, ok := v.SelectedDescribe(); ok {
		t.Error("empty row must describe as no-selection, not panic")
	}
	if _, ok := v.SelectedYank(); ok {
		t.Error("empty row must yank as no-selection")
	}
}
