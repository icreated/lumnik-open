package tui

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"
)

// screenHas reports whether the screen, as last drawn after the loop's pending work, shows text.
func screenHas(a *App, sim tcell.SimulationScreen, text string) bool {
	drawn := make(chan struct{})
	go a.tv.QueueUpdateDraw(func() {})
	go a.tv.QueueUpdate(func() { close(drawn) }) // runs after the draw above: one loop, in order
	select {
	case <-drawn:
	case <-time.After(time.Second):
		return false
	}
	cells, w, h := sim.GetContents()
	var b strings.Builder
	for i := range w * h {
		if r := cells[i].Runes; len(r) > 0 {
			b.WriteRune(r[0])
		}
		if (i+1)%w == 0 {
			b.WriteByte('\n')
		}
	}
	return strings.Contains(b.String(), text)
}

// The inventory behind setRoot: a root set around it leaves overlaid wrong, and the global keys
// take an overlay's Esc again — or answer the main layout's as an overlay's.
func TestApp_takesTheScreenOnlyThroughSetRoot(t *testing.T) {
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range files {
		if strings.HasSuffix(f, "_test.go") || f == "source_picker.go" { // the picker runs its own app
			continue
		}
		src, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		for i, line := range strings.Split(string(src), "\n") {
			if strings.Contains(line, "a.tv.SetRoot(") && !strings.Contains(line, "a.tv.SetRoot(root, true)") {
				t.Errorf("%s:%d takes the screen around setRoot: %s", f, i+1, strings.TrimSpace(line))
			}
		}
	}
}

// waitScreen polls the screen until it shows text (want) or no longer does, for up to d, and
// reports whether it got there.
func waitScreen(a *App, sim tcell.SimulationScreen, text string, want bool, d time.Duration) bool {
	for deadline := time.Now().Add(d); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		if screenHas(a, sim, text) == want {
			return true
		}
	}
	return false
}

// An overlay — the help, a text, :context — owns the keys while it holds the screen. With the
// describe panel open, the global keys took them first: Esc closed the panel under the help, which
// no Esc then closed, Tab handed the keys to a panel the overlay hides, and d toggled the panel
// under it (#1179 review). q still quits.
func TestOverlay_ownsItsKeysOverTheDescribePanel(t *testing.T) {
	const help = "Help (press Esc to close)"
	key := func(k tcell.Key) *tcell.EventKey { return tcell.NewEventKey(k, 0, tcell.ModNone) }
	d := tcell.NewEventKey(tcell.KeyRune, 'd', tcell.ModNone)
	for name, c := range map[string]struct {
		describe bool
		keys     []*tcell.EventKey
	}{
		"no describe, ? Esc":   {false, []*tcell.EventKey{key(tcell.KeyEscape)}},
		"no describe, ? d Esc": {false, []*tcell.EventKey{d, key(tcell.KeyEscape)}},
		"describe, ? Esc":      {true, []*tcell.EventKey{key(tcell.KeyEscape)}},
		"describe, ? Tab Esc":  {true, []*tcell.EventKey{key(tcell.KeyTab), key(tcell.KeyEscape)}},
	} {
		t.Run(name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_, _ = w.Write([]byte(`[{"id":"c1","tenantId":1,"name":"erp","typeId":"csv-file","enabled":true}]`))
			}))
			t.Cleanup(srv.Close)
			a, sim := runningAppOn(t, srv.URL)
			table := a.sourcesPage.View().(*tview.Table)
			onLoop(a, 2*time.Second, func() { a.switchTo("sources") })
			waitOnLoop(t, a, "the list", func() bool { return table.GetRowCount() > 1 })
			onLoop(a, time.Second, func() {
				table.Select(1, 0)
				if c.describe {
					a.toggleDescribe()
				}
			})

			a.tv.QueueEvent(tcell.NewEventKey(tcell.KeyRune, '?', tcell.ModNone))
			if !waitScreen(a, sim, help, true, 5*time.Second) {
				t.Fatal("? drew no help")
			}
			for _, ev := range c.keys {
				a.tv.QueueEvent(ev)
			}
			if !waitScreen(a, sim, help, false, 2*time.Second) {
				t.Fatal("the help is still on screen: its Esc never reached it")
			}
			var open, inPanel bool
			onLoop(a, time.Second, func() { open, inPanel = a.describeOpen, a.tv.GetFocus() == a.describe.body })
			if open != c.describe {
				t.Errorf("describe open = %v after the help closed, want %v", open, c.describe)
			}
			if inPanel {
				t.Error("Tab under the help gave the keys to the hidden describe panel")
			}
		})
	}
}

// typeKeys queues each rune of s as a key press.
func typeKeys(a *App, s string) {
	for _, r := range s {
		a.tv.QueueEvent(tcell.NewEventKey(tcell.KeyRune, r, tcell.ModNone))
	}
}

// A page name typed in full is already the highlighted suggestion: one Enter switches to it.
// The first Enter used to only accept the suggestion — the same text — and a second one
// switched (#1175). A prefix still takes one Enter to accept, then one to switch.
func TestPalette_oneEnterSwitchesWhenTheTextIsTheSuggestion(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`[]`))
	}))
	t.Cleanup(srv.Close)
	enter := tcell.NewEventKey(tcell.KeyEnter, 0, tcell.ModNone)
	front := func(a *App) func() bool {
		return func() bool { name, _ := a.pages.GetFrontPage(); return name == "modules" }
	}

	a, _ := runningAppOn(t, srv.URL)
	typeKeys(a, ":modules")
	a.tv.QueueEvent(enter)
	waitOnLoop(t, a, "one Enter to switch to :modules", front(a))

	b, _ := runningAppOn(t, srv.URL)
	typeKeys(b, ":modu")
	b.tv.QueueEvent(enter)
	var name string
	onLoop(b, time.Second, func() { name, _ = b.pages.GetFrontPage() })
	if name == "modules" {
		t.Fatal("a prefix switched on its first Enter, before the suggestion was accepted")
	}
	b.tv.QueueEvent(enter)
	waitOnLoop(t, b, "the second Enter to switch to :modules", front(b))
}

// :ambiguous opens with the keys in its Entity: field, where `:` was typed as text (`Entity: :q`)
// and only Esc let the palette back (#1175). An entity name never starts with `:`: in an EMPTY
// field it opens the palette; after a first character it is text again.
func TestAmbiguous_colonInTheEmptyFieldOpensThePalette(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`[]`))
	}))
	t.Cleanup(srv.Close)
	a, _ := runningAppOn(t, srv.URL)
	field := a.ambiguousPage.FocusPrimitive().(*tview.InputField)
	onLoop(a, 2*time.Second, func() { a.switchTo("ambiguous") })

	typeKeys(a, ":modules")
	a.tv.QueueEvent(tcell.NewEventKey(tcell.KeyEnter, 0, tcell.ModNone))
	waitOnLoop(t, a, "`:` in the empty field to open the palette", func() bool {
		name, _ := a.pages.GetFrontPage()
		return name == "modules"
	})
	var text string
	onLoop(a, time.Second, func() { text = field.GetText() })
	if text != "" {
		t.Errorf("the field kept %q", text)
	}

	onLoop(a, 2*time.Second, func() { a.switchTo("ambiguous") })
	typeKeys(a, "a:b")
	waitOnLoop(t, a, "`:` after a first character to be typed", func() bool { return field.GetText() == "a:b" })
}

