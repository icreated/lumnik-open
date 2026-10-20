package tui

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/rivo/tview"
)

// newAskHub holds the ask door's call until release is closed, then answers it with status and
// body; asked says it is held. Every other call is an empty list.
func newAskHub(t *testing.T, status int, body string) (url string, asked chan struct{}, release chan struct{}) {
	t.Helper()
	asked, release = make(chan struct{}, 1), make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path != "/api/platform/rag/chat" {
			_, _ = w.Write([]byte("[]"))
			return
		}
		asked <- struct{}{}
		<-release
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	return srv.URL, asked, release
}

func askHeadline(a *App) string {
	return a.askPage.View().(*tview.Flex).GetItem(1).(*tview.TextView).GetText(true)
}

// An :ask answer lands on its page always, but moves the focus only when :ask is the front page of
// a live app (#1160): before, an answer that came back while the user was on :sources sent every
// key to a widget of a page not shown, and lm ui looked frozen.
func TestAsk_theAnswerTakesTheFocusOnlyWhenAskIsInFront(t *testing.T) {
	answers := []struct {
		name, body string
		status     int
		focus      func(*App) tview.Primitive // where the answer puts the focus while :ask is in front
	}{
		{"render", `{"answer":"42 late orders","sql":"SELECT 1","rowCount":0}`, http.StatusOK,
			func(a *App) tview.Primitive { return a.askPage.View().(*tview.Flex).GetItem(3) }},
		{"notice", `{"error":"no discovered schema"}`, http.StatusServiceUnavailable,
			func(a *App) tview.Primitive { return a.askPage.FocusPrimitive() }},
	}
	// Each move runs on the loop while the answer is held, and returns the app that then owns the
	// screen. keeps: the answer must leave the focus where the move left it.
	moves := []struct {
		name  string
		move  func(a *App) *App
		keeps bool
	}{
		{"another page in front", func(a *App) *App { a.switchTo("sources"); return a }, true},
		{"ask in front", func(a *App) *App {
			a.tv.SetFocus(a.askPage.View().(*tview.Flex).GetItem(4)) // the answer text: neither target
			return a
		}, false},
		{"retired app", func(a *App) *App { return a.useContext("staging") }, true},
	}
	for _, ans := range answers {
		for _, m := range moves {
			t.Run(ans.name+"/"+m.name, func(t *testing.T) {
				url, asked, release := newAskHub(t, ans.status, ans.body)
				hubB, _ := countingHub(t, http.StatusOK)
				withConfig(t, &config.Config{CurrentContext: "local", Contexts: map[string]config.Context{
					"local":   {Server: url},
					"staging": {Server: hubB.URL},
				}})
				a := runningApp(t, url)
				waitOnLoop(t, a, ":ask", func() bool {
					a.switchTo("ask")
					a.askPage.FocusPrimitive().(*tview.InputField).SetText("how many late orders?")
					a.tv.QueueEvent(tcell.NewEventKey(tcell.KeyEnter, 0, tcell.ModNone))
					return true
				})
				select {
				case <-asked:
				case <-time.After(5 * time.Second):
					t.Fatal(":ask never asked the hub")
				}
				var front *App
				var before tview.Primitive
				waitOnLoop(t, a, m.name, func() bool { front = m.move(a); before = a.tv.GetFocus(); return front != nil })

				close(release)
				// a and front share the loop: once a's page has the answer, its follow-up has run.
				waitOnLoop(t, a, "the answer", func() bool { return askHeadline(a) != "…thinking" })
				var got tview.Primitive
				waitOnLoop(t, front, "the focus", func() bool { got = front.tv.GetFocus(); return true })
				want := ans.focus(a)
				if m.keeps {
					want = before
				}
				if got != want {
					t.Fatalf("the answer left the focus on %T, want %T", got, want)
				}
			})
		}
	}
}
