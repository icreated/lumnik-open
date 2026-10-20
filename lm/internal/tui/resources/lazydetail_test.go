package resources

import (
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"
)

// A describe detail is fetched once per key, off the loop: the first look answers "not yet" at
// once, the landing redraws the panel, the next looks are served from it. A failure is kept — a
// redraw must not retry against a hub that is down — until retryFailed (#1138).
func TestLazyDetail_fetchesOncePerKeyOffTheLoop(t *testing.T) {
	app := tview.NewApplication()
	app.SetScreen(tcell.NewSimulationScreen("UTF-8"))
	stopped := make(chan struct{})
	go func() {
		_ = app.Run()
		close(stopped)
	}()
	t.Cleanup(func() {
		app.Stop()
		<-stopped
	})
	onLoop := func(f func()) {
		done := make(chan struct{})
		app.QueueUpdate(func() { f(); close(done) })
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatal("the loop never ran the call")
		}
	}

	var l lazyDetail
	var fetches atomic.Int32
	fail := atomic.Bool{}
	fail.Store(true)
	landed := make(chan struct{}, 8)
	fetch := func() (string, error) {
		fetches.Add(1)
		if fail.Load() {
			return "", errors.New("hub [down]")
		}
		return "the detail", nil
	}
	look := func() (text string, ok bool) {
		onLoop(func() { text, ok = l.get(app, "k", fetch, func() { landed <- struct{}{} }) })
		return
	}
	waitLanded := func() {
		select {
		case <-landed:
		case <-time.After(5 * time.Second):
			t.Fatal("the fetch never landed")
		}
	}

	if _, ok := look(); ok {
		t.Fatal("the first look must not wait for the hub")
	}
	look() // a second look before the landing asks nothing more
	waitLanded()
	text, ok := look()
	if !ok || !strings.Contains(text, "hub [down[]") {
		t.Errorf("look after a failure = %q, %v — want the escaped error kept", text, ok)
	}
	if n := fetches.Load(); n != 1 {
		t.Errorf("%d fetches before retryFailed, want 1", n)
	}

	fail.Store(false)
	onLoop(l.retryFailed)
	look()
	waitLanded()
	if text, ok := look(); !ok || text != "the detail" {
		t.Errorf("look after the retry = %q, %v", text, ok)
	}
	onLoop(l.retryFailed) // a success is kept
	look()
	if n := fetches.Load(); n != 2 {
		t.Errorf("%d fetches, want 2", n)
	}
}
