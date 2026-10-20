package tui

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
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

// countingHub answers every request with an empty JSON list and counts the :sources list calls
// (the live stream and the scope seed are calls too, but not the page's).
func countingHub(t *testing.T, status int) (*httptest.Server, *atomic.Int32) {
	t.Helper()
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/platform/connectors" {
			hits.Add(1)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte("[]"))
	}))
	t.Cleanup(srv.Close)
	return srv, &hits
}

// withConfig points config.DefaultPath at a temporary HOME holding cfg.
func withConfig(t *testing.T, cfg *config.Config) {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("LUMNIK_URL", "") // an environment override would send every context to one hub
	t.Setenv("LUMNIK_TOKEN", "")
	if err := cfg.Save(filepath.Join(home, ".lm", "config.yaml")); err != nil {
		t.Fatal(err)
	}
}

func TestContextChoices_listsEveryContextSortedAndMarksTheCurrentOne(t *testing.T) {
	cfg := &config.Config{Contexts: map[string]config.Context{
		"staging": {Server: "https://staging.example:8443"},
		"local":   {Server: "http://localhost:8080"},
	}}
	got := contextChoices(cfg, "local", "http://localhost:8080")
	want := []contextChoice{
		{"local", "● local (localhost:8080)"},
		{"staging", "  staging (staging.example:8443)"},
	}
	if len(got) != len(want) || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("choices = %q, want %q", got, want)
	}
}

// Enter on :context rebuilds lm ui on the chosen context: the next call goes to the new hub, never
// the old one, and the header names the new hub (#1137).
func TestUseContext_theNextCallHitsTheNewHubAndTheHeaderNamesIt(t *testing.T) {
	hubA, hitsA := countingHub(t, http.StatusOK)
	hubB, hitsB := countingHub(t, http.StatusOK)
	withConfig(t, &config.Config{CurrentContext: "local", Contexts: map[string]config.Context{
		"local":   {Server: hubA.URL},
		"staging": {Server: hubB.URL},
	}})
	a := runningApp(t, hubA.URL)
	waitOnLoop(t, a, "the landing page", func() bool { a.switchTo("sources"); return true })
	waitOnLoop(t, a, "the old hub's :sources call", func() bool { return hitsA.Load() > 0 })

	var b *App
	waitOnLoop(t, a, "the switch", func() bool { b = a.useContext("staging"); return true })
	if b == nil {
		t.Fatal("useContext returned no app")
	}
	before := hitsA.Load()
	waitOnLoop(t, b, "the :sources call on the new hub", func() bool { return hitsB.Load() > 0 })
	time.Sleep(100 * time.Millisecond)
	if hitsA.Load() != before {
		t.Fatalf("the old hub was called after the switch: %d → %d", before, hitsA.Load())
	}
	var header string
	waitOnLoop(t, b, "the header", func() bool { header = b.header.tv.GetText(true); return true })
	if want := "staging (" + strings.TrimPrefix(hubB.URL, "http://") + ")"; !strings.Contains(header, want) {
		t.Fatalf("header %q does not name %q", header, want)
	}
	if front, _ := b.pages.GetFrontPage(); front != "sources" {
		t.Fatalf("the switch left the page: front = %q, want sources", front)
	}
}

// A context whose session expired raises the existing re-login modal on its first call.
func TestUseContext_anExpiredSessionRaisesTheReLoginModal(t *testing.T) {
	hubA, _ := countingHub(t, http.StatusOK)
	hubB, _ := countingHub(t, http.StatusUnauthorized)
	withConfig(t, &config.Config{CurrentContext: "local", Contexts: map[string]config.Context{
		"local":   {Server: hubA.URL},
		"staging": {Server: hubB.URL, Access: "expired-session", ExpiresAt: time.Now().Add(time.Hour).Unix()},
	}})
	a := runningApp(t, hubA.URL)
	var b *App
	waitOnLoop(t, a, "the switch", func() bool { b = a.useContext("staging"); return true })
	waitOnLoop(t, b, "the re-login modal", func() bool {
		box, ok := b.tv.GetFocus().(*tview.TextView)
		return ok && strings.Contains(box.GetTitle(), "auth")
	})
}

// An unknown context (removed from the file meanwhile) is said, and the session stays where it is.
func TestUseContext_anUnknownContextKeepsTheSession(t *testing.T) {
	hubA, _ := countingHub(t, http.StatusOK)
	withConfig(t, &config.Config{CurrentContext: "local", Contexts: map[string]config.Context{
		"local": {Server: hubA.URL},
	}})
	a := runningApp(t, hubA.URL)
	var b *App
	waitOnLoop(t, a, "the switch", func() bool { b = a.useContext("gone"); return true })
	if b != nil {
		t.Fatal("an unknown context must not rebuild the app")
	}
}

// With LUMNIK_URL set every context reaches that one hub: the picker must say so, not list the
// hosts the file names. The current row says what this session talks to — lm ui --server X (#1151).
func TestContextChoices_sayTheHostEachContextReaches(t *testing.T) {
	cfg := &config.Config{Contexts: map[string]config.Context{
		"staging": {Server: "https://staging.example:8443"},
		"local":   {Server: "http://localhost:8080"},
	}}
	t.Setenv("LUMNIK_URL", "https://pinned.example")
	got := contextChoices(cfg, "local", "http://other.example:9090")
	want := []contextChoice{
		{"local", "● local (other.example:9090)"},
		{"staging", "  staging (pinned.example)"},
	}
	if len(got) != len(want) || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("choices = %q, want %q", got, want)
	}
}

// The switch shows the page at once, as Run does: no blank centre pane until the queued switchTo.
func TestUseContext_showsThePageSynchronously(t *testing.T) {
	hubA, _ := countingHub(t, http.StatusOK)
	hubB, _ := countingHub(t, http.StatusOK)
	withConfig(t, &config.Config{CurrentContext: "local", Contexts: map[string]config.Context{
		"local":   {Server: hubA.URL},
		"staging": {Server: hubB.URL},
	}})
	a := runningApp(t, hubA.URL)
	waitOnLoop(t, a, "the landing page", func() bool { a.switchTo("sources"); return true })
	var front string
	waitOnLoop(t, a, "the switch", func() bool {
		b := a.useContext("staging")
		front, _ = b.pages.GetFrontPage()
		return true
	})
	if front != "sources" {
		t.Fatalf("right after the switch the front page is %q, want sources", front)
	}
}

// slowHub is hub A of the retired-app test: it lists one row on a page, holds the GET that e makes
// for it until released, and records every call that is not a GET.
type slowHub struct {
	*httptest.Server
	asked, answered chan struct{}
	release         chan struct{}
	mu              sync.Mutex
	writes          []string
}

func newSlowHub(t *testing.T, listPath, listBody, heldPath, heldType, heldBody string) *slowHub {
	t.Helper()
	h := &slowHub{asked: make(chan struct{}, 1), answered: make(chan struct{}, 1), release: make(chan struct{})}
	h.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method != http.MethodGet:
			h.mu.Lock()
			h.writes = append(h.writes, r.Method+" "+r.URL.Path)
			h.mu.Unlock()
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte("{}"))
		case r.URL.Path == listPath:
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(listBody))
		case r.URL.Path == heldPath:
			h.asked <- struct{}{}
			<-h.release
			w.Header().Set("Content-Type", heldType)
			_, _ = w.Write([]byte(heldBody))
			h.answered <- struct{}{}
		default:
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte("[]"))
		}
	}))
	t.Cleanup(h.Close)
	return h
}

func (h *slowHub) writesSoFar() []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return append([]string(nil), h.writes...)
}

// e while hub A is slow, then :context: when A's answer finally arrives, the app that left A must
// not open the editor on it, nor apply anything to A (#1151) — on the manifest pages and on
// :sources, whose e reads off the loop since #1157 and opened its own editor.
func TestUseContext_aRetiredAppNeverOpensTheEditorOnTheHubItLeft(t *testing.T) {
	for _, c := range []struct {
		page                                         string
		table                                        func(*App) *tview.Table
		listPath, listBody, heldPath, heldType, held string
	}{
		{"workflows", func(a *App) *tview.Table { return a.workflowsPage.View().(*tview.Table) },
			"/api/workflows", `[{"name":"x","table":"t","column":"c","enabled":true}]`,
			"/api/workflows/x/manifest", "application/yaml", "kind: Workflow\nmetadata:\n  name: x\n"},
		{"sources", func(a *App) *tview.Table { return a.sourcesPage.View().(*tview.Table) },
			"/api/platform/connectors", `[{"id":"c1","name":"erp","typeId":"csv-file","enabled":true}]`,
			"/api/platform/connectors/c1", "application/json", `{"id":"c1","name":"erp","typeId":"csv-file","enabled":true}`},
	} {
		t.Run(c.page, func(t *testing.T) {
			hubA := newSlowHub(t, c.listPath, c.listBody, c.heldPath, c.heldType, c.held)
			hubB, _ := countingHub(t, http.StatusOK)
			withConfig(t, &config.Config{CurrentContext: "local", Contexts: map[string]config.Context{
				"local":   {Server: hubA.URL},
				"staging": {Server: hubB.URL},
			}})
			var editors atomic.Int32
			orig := suspendEditor
			suspendEditor = func(*tview.Application, func(string), func()) { editors.Add(1) }
			t.Cleanup(func() { suspendEditor = orig })
			// A page that bypassed the app's editor would reach a real one: make it a script that
			// edits, so the bypass shows as a write to hub A instead of a hung test.
			script := filepath.Join(t.TempDir(), "ed.sh")
			if err := os.WriteFile(script, []byte("#!/bin/sh\necho '# edited' >> \"$1\"\n"), 0o700); err != nil {
				t.Fatal(err)
			}
			t.Setenv("LM_EDITOR", script)

			a := runningApp(t, hubA.URL)
			waitOnLoop(t, a, "the page", func() bool { a.switchTo(c.page); return true })
			table := c.table(a)
			waitOnLoop(t, a, "the row", func() bool { return table.GetRowCount() > 1 })
			onLoop(a, time.Second, func() {
				table.Select(1, 0)
				a.tv.SetFocus(table)
				a.tv.QueueEvent(tcell.NewEventKey(tcell.KeyRune, 'e', tcell.ModNone))
			})
			select {
			case <-hubA.asked:
			case <-time.After(5 * time.Second):
				t.Fatal("e never asked hub A")
			}
			var b *App
			waitOnLoop(t, a, "the switch", func() bool { b = a.useContext("staging"); return b != nil })

			close(hubA.release)
			select {
			case <-hubA.answered:
			case <-time.After(5 * time.Second):
				t.Fatal("hub A never answered")
			}
			time.Sleep(200 * time.Millisecond) // the old app's follow-up is queued on the shared loop
			waitOnLoop(t, b, "the loop to drain", func() bool { return true })
			if n := editors.Load(); n != 0 {
				t.Fatalf("the retired app opened the editor %d time(s) on the hub it left", n)
			}
			if w := hubA.writesSoFar(); len(w) != 0 {
				t.Fatalf("the retired app wrote to the hub it left: %v", w)
			}
			if front, _ := b.pages.GetFrontPage(); front != c.page {
				t.Fatalf("the retired app moved the screen: front = %q, want %s", front, c.page)
			}
		})
	}
}

// e on a manifest page, then another page before the hub answers: the manifest arriving late opens
// no editor and does not pull the screen to :new — it lands only where the user still is (#1171,
// as :mappings did since #1161). :apps has no e: the hub serves no stored App manifest. Enter on
// :views moved the screen to :view the same way.
func TestManifestPages_eAnsweredAfterLeavingThePageOpensNoEditor(t *testing.T) {
	for _, c := range []struct {
		page, press                                  string
		table                                        func(*App) *tview.Table
		listPath, listBody, heldPath, heldType, held string
	}{
		{"workflows", "e", func(a *App) *tview.Table { return a.workflowsPage.View().(*tview.Table) },
			"/api/workflows", `[{"name":"x","table":"t","column":"c","enabled":true}]`,
			"/api/workflows/x/manifest", "application/yaml", "kind: Workflow\nmetadata:\n  name: x\n"},
		{"views", "e", func(a *App) *tview.Table { return a.viewsPage.View().(*tview.Table) },
			"/api/views", `[{"name":"x","scope":"s","title":"X"}]`,
			"/api/views/x", "application/yaml", "kind: View\nmetadata:\n  name: x\n"},
		{"views", "Enter", func(a *App) *tview.Table { return a.viewsPage.View().(*tview.Table) },
			"/api/views", `[{"name":"x","scope":"s","title":"X"}]`,
			"/api/views/x", "application/yaml", "kind: View\nmetadata:\n  name: x\n"},
		{"processes", "e", func(a *App) *tview.Table { return a.processesPage.View().(*tview.Table) },
			"/api/processes", `[{"name":"x","table":"t","emit":"e","enabled":true}]`,
			"/api/processes/x/manifest", "application/yaml", "kind: Process\nmetadata:\n  name: x\n"},
		{"exports", "e", func(a *App) *tview.Table { return a.exportsPage.View().(*tview.Table) },
			"/api/exports", `[{"name":"x","scope":"s","entityName":"e","destinationKind":"s3"}]`,
			"/api/exports/x", "application/yaml", "kind: Export\nmetadata:\n  name: x\n"},
	} {
		t.Run(c.page+" "+c.press, func(t *testing.T) {
			press := tcell.NewEventKey(tcell.KeyRune, 'e', tcell.ModNone)
			if c.press == "Enter" {
				press = tcell.NewEventKey(tcell.KeyEnter, 0, tcell.ModNone)
			}
			hub := newSlowHub(t, c.listPath, c.listBody, c.heldPath, c.heldType, c.held)
			var editors atomic.Int32
			orig := suspendEditor
			suspendEditor = func(*tview.Application, func(string), func()) { editors.Add(1) }
			t.Cleanup(func() { suspendEditor = orig })

			a := runningApp(t, hub.URL)
			waitOnLoop(t, a, "the page", func() bool { a.switchTo(c.page); return true })
			table := c.table(a)
			waitOnLoop(t, a, "the row", func() bool { return table.GetRowCount() > 1 })
			onLoop(a, time.Second, func() {
				table.Select(1, 0)
				a.tv.SetFocus(table)
				a.tv.QueueEvent(press)
			})
			select {
			case <-hub.asked:
			case <-time.After(5 * time.Second):
				t.Fatalf("%s never asked the hub for the manifest", c.press)
			}
			waitOnLoop(t, a, "the other page", func() bool { a.switchTo("runs"); return true })
			close(hub.release)
			select {
			case <-hub.answered:
			case <-time.After(5 * time.Second):
				t.Fatal("the hub never answered")
			}
			time.Sleep(200 * time.Millisecond) // the follow-up is queued on the loop
			waitOnLoop(t, a, "the loop to drain", func() bool { return true })
			if n := editors.Load(); n != 0 {
				t.Fatalf("the editor opened %d time(s) for a page the user had left", n)
			}
			if front, _ := a.pages.GetFrontPage(); front != "runs" {
				t.Fatalf("the late manifest moved the screen to %q, want runs", front)
			}
		})
	}
}

// Every way the app takes the screen is closed to an app :context has retired.
func TestRetiredApp_takesNoOverlay(t *testing.T) {
	a := runningApp(t, "http://127.0.0.1:1")
	gestures := map[string]func(){
		"switchTo":   func() { a.switchTo("runs") },
		"prompt":     func() { a.prompt("name: ", false, func(string) {}) },
		"confirmAs":  func() { a.confirmAs("reindex?", "Reindex", func() {}) },
		"showText":   func() { a.showText(" history ", "body") },
		"pickTables": func() { a.pickTables([]client.JdbcTable{{Name: "t"}}, func([]client.JdbcTable) {}) },
	}
	waitOnLoop(t, a, "the landing page", func() bool { a.switchTo("sources"); a.restoreRoot(); return true })
	a.retired.Store(true)
	for name, gesture := range gestures {
		var before, after tview.Primitive
		var front string
		waitOnLoop(t, a, name, func() bool {
			before = a.tv.GetFocus()
			gesture()
			after = a.tv.GetFocus()
			front, _ = a.pages.GetFrontPage()
			return true
		})
		if after != before || front != "sources" {
			t.Errorf("%s on a retired app took the screen (focus %T → %T, front %q)", name, before, after, front)
		}
	}
}
