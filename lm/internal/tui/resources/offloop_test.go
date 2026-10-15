package resources

import (
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/rivo/tview"
)

// A burst of refreshes while one fetch is in flight costs exactly one more fetch — not one per
// request, and not zero: the last request's state must still be rendered.
func TestOffLoop_aBurstMidFlightBuysExactlyOneMoreRound(t *testing.T) {
	app := tview.NewApplication()
	sim := tcell.NewSimulationScreen("UTF-8")
	app.SetScreen(sim)
	stopped := make(chan struct{})
	go func() {
		_ = app.Run()
		close(stopped)
	}()
	t.Cleanup(func() {
		app.Stop()
		<-stopped
	})

	var o offLoop
	var fetches atomic.Int32
	started, release := make(chan struct{}), make(chan struct{})
	rendered := make(chan int32, 8)
	fetch := func() func() {
		n := fetches.Add(1)
		if n == 1 {
			close(started)
			<-release // the first fetch is still waiting on the hub
		}
		return func() { rendered <- n }
	}

	o.run(app, fetch)
	<-started
	for range 5 {
		o.run(app, fetch)
	}
	close(release)

	for want := int32(1); want <= 2; want++ {
		select {
		case got := <-rendered:
			if got != want {
				t.Fatalf("render %d came from fetch %d", want, got)
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("render %d never landed", want)
		}
	}
	select {
	case got := <-rendered:
		t.Fatalf("a burst of 5 bought fetch %d; it must buy exactly one more round", got)
	case <-time.After(200 * time.Millisecond):
	}

	o.run(app, fetch) // the flight is over: the next refresh starts a new one
	select {
	case got := <-rendered:
		if got != 3 {
			t.Fatalf("the next refresh rendered fetch %d, want 3", got)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("a refresh after the flight never fetched")
	}
}

// The live tick refreshes a page in place and no longer closes its describe panel, so the panel
// must follow the fresh row: a refresh re-describes it. Every describable ticker page.
func TestLiveRefresh_redescribesAnOpenPanel(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/platform/connectors":
			_, _ = w.Write([]byte(`[{"id":"c1","tenantId":1,"name":"ad_user","typeId":"csv-file"}]`))
		case "/api/inbox":
			_, _ = w.Write([]byte(`[{"id":1,"message":"x"}]`))
		case "/api/processes":
			_, _ = w.Write([]byte(`[{"name":"p1"}]`))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	c := client.New(config.Context{Server: srv.URL})

	pages := map[string]interface {
		Refresh()
		OnSelectionChange(func())
	}{
		"sources":   NewSources(nil, c, nil, nil),
		"inbox":     NewInbox(nil, c, nil, nil),
		"processes": NewProcesses(nil, c, nil, nil),
	}
	for name, p := range pages {
		described := 0
		p.OnSelectionChange(func() { described++ })
		p.Refresh()
		if described != 1 {
			t.Errorf(":%s refresh re-described the open panel %d time(s), want 1", name, described)
		}
	}
}

// A failed list empties :inbox and :processes and draws the error in the table: the open panel
// must hear of it too, or it keeps presenting a row that is no longer on screen.
func TestLiveRefresh_aFailedListRedescribesTheOpenPanel(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "down", http.StatusInternalServerError)
	}))
	t.Cleanup(srv.Close)
	c := client.New(config.Context{Server: srv.URL})

	pages := map[string]interface {
		Refresh()
		OnSelectionChange(func())
	}{
		"inbox":     NewInbox(nil, c, nil, nil),
		"processes": NewProcesses(nil, c, nil, nil),
	}
	for name, p := range pages {
		described := 0
		p.OnSelectionChange(func() { described++ })
		p.Refresh()
		if described != 1 {
			t.Errorf(":%s failed refresh re-described the open panel %d time(s), want 1", name, described)
		}
	}
}
