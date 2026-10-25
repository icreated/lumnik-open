package resources

import (
	"encoding/json"
	"errors"
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

// An answer that came back while $EDITOR held the terminal was dropped by QueueUpdate: the question
// was asked, the hub answered, and the page went on saying "…thinking" (#1158). It now lands once
// the editor closes — drained on the loop, as SuspendEditor drains.
func TestAsk_anAnswerUnderAnOpenEditorLandsOnceItCloses(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"answer":"42 late orders","sql":"SELECT 1","rowCount":0}`))
	}))
	t.Cleanup(srv.Close)
	app := runningSimApp(t)
	EditorSuspended.Store(true)
	t.Cleanup(func() { EditorSuspended.Store(false) })

	var a *Ask
	onLoopWithin(app, func() {
		a = NewAsk(app, client.New(config.Context{Server: srv.URL}), nil, nil, nil)
		a.submit("how many late orders?")
	})
	if !deferredHeld() {
		t.Fatal("the answer was dropped under the editor instead of held for its close")
	}
	onLoopWithin(app, func() { EditorSuspended.Store(false); drainDeferred() })
	var answer string
	deadline := time.Now().Add(5 * time.Second)
	for answer == "" && time.Now().Before(deadline) {
		onLoopWithin(app, func() {
			if a.last != nil {
				answer = a.last.Answer
			}
		})
		time.Sleep(10 * time.Millisecond)
	}
	if answer != "42 late orders" {
		t.Fatalf("the answer never landed after the editor closed (got %q)", answer)
	}
}

// askHub answers the ask door with bodies in turn (the last one repeats) and records, per question, the
// `previous` it carried — "" when the question was sent alone.
func askHub(t *testing.T, bodies ...string) (url string, sent func() []string) {
	t.Helper()
	var mu sync.Mutex
	var previous []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Previous string `json:"previous"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		mu.Lock()
		body := bodies[min(len(previous), len(bodies)-1)]
		previous = append(previous, req.Previous)
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	return srv.URL, func() []string { mu.Lock(); defer mu.Unlock(); return slices.Clone(previous) }
}

// askOn submits question on the loop and waits until its answer has landed.
func askOn(t *testing.T, app *tview.Application, a *Ask, question string) {
	t.Helper()
	onLoopWithin(app, func() { a.submit(question) })
	deadline := time.Now().Add(5 * time.Second)
	for thinking := true; thinking; time.Sleep(10 * time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatalf("the answer to %q never landed", question)
		}
		onLoopWithin(app, func() { thinking = strings.Contains(a.headline.GetText(true), "thinking") })
	}
}

// #1406: the page carries the question it last answered as `previous`, so "Et SUP-004 ?" resolves —
// the sentence the hub understood when it resolved one, as the PWA's chat sends it. `n` and `r`
// start over; leaving the results with Enter does not.
func TestAsk_aFollowUpCarriesTheQuestionLastAnswered(t *testing.T) {
	const answered = `{"answer":"3","sql":"SELECT 1","rowCount":0}`
	const resolved = `{"answer":"1","sql":"SELECT 1","rowCount":0,"understoodAs":"late orders of SUP-004?"}`
	const abstained = `{"answer":"no","sql":"","rowCount":0,"abstained":true}`
	none := func(*Ask) {}
	cases := []struct {
		name    string
		first   string
		bodies  []string
		between func(a *Ask)
		want    []string // the previous each question carried, the first one included
	}{
		{"a standalone answer", "late orders?", []string{answered}, none, []string{"", "late orders?"}},
		{"a resolved follow-up", "Et SUP-004 ?", []string{resolved}, none, []string{"", "late orders of SUP-004?"}},
		{"Enter on the results", "late orders?", []string{answered},
			func(a *Ask) { pressOf(a.tableKeys, tcell.KeyEnter, 0)() }, []string{"", "late orders?"}},
		{"n: new question", "late orders?", []string{answered},
			func(a *Ask) { pressOf(a.tableKeys, tcell.KeyRune, 'n')() }, []string{"", ""}},
		{"r: clear", "late orders?", []string{answered}, func(a *Ask) { a.Refresh() }, []string{"", ""}},
		// :scope <tag> while on :ask: the question was about another scope's schema.
		{"a scope change", "late orders?", []string{answered}, func(a *Ask) { a.SetScope("billing") }, []string{"", ""}},
		{"the same scope again", "late orders?", []string{answered}, func(a *Ask) { a.SetScope("") },
			[]string{"", "late orders?"}},
		// A refusal or an error does not replace it: the user often retries in other words.
		{"an abstention in between", "late orders?", []string{answered, abstained}, none,
			[]string{"", "late orders?", "late orders?"}},
		// The hub refuses a longer previous: holding one would refuse every next question.
		{"a question too long to carry", strings.Repeat("x", 1001), []string{answered}, none, []string{"", ""}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			url, sent := askHub(t, tc.bodies...)
			app := runningSimApp(t)
			var a *Ask
			onLoopWithin(app, func() { a = NewAsk(app, client.New(config.Context{Server: url}), nil, nil, nil) })
			askOn(t, app, a, tc.first)
			onLoopWithin(app, func() { tc.between(a) })
			for range tc.want[1:] {
				askOn(t, app, a, "Et SUP-004 ?")
			}
			if got := sent(); !slices.Equal(got, tc.want) {
				t.Errorf("previous sent = %q, want %q", got, tc.want)
			}
		})
	}
}

// #1406: the headline says what the hub answered when it resolved a follow-up — the only honest guard
// against a wrong resolution is that the user reads it.
func TestAsk_theHeadlineShowsTheResolvedQuestion(t *testing.T) {
	url, _ := askHub(t, `{"answer":"1","sql":"SELECT 1","rowCount":1,"understoodAs":"late orders of SUP-004?"}`)
	app := runningSimApp(t)
	var a *Ask
	onLoopWithin(app, func() { a = NewAsk(app, client.New(config.Context{Server: url}), nil, nil, nil) })
	askOn(t, app, a, "Et SUP-004 ?")
	var head string
	onLoopWithin(app, func() { head = a.headline.GetText(true) })
	if !strings.Contains(head, "1 row") || !strings.Contains(head, "understood as: late orders of SUP-004?") {
		t.Errorf("headline = %q, want the count and \"understood as: late orders of SUP-004?\"", head)
	}
}

func TestDeriveHeadline(t *testing.T) {
	cases := []struct {
		answer   string
		rowCount int
		want     string
	}{
		{"47 orders worth €312,480 were never invoiced.", 47, "€312,480"},
		{"Found 312 480 unpaid across the year.", 5, "312 480"},
		{"Just a few matches, nothing big.", 3, "3 rows"},
		{"One match only.", 1, "1 row"},
		{"", 0, "0 rows"},
	}
	for _, tc := range cases {
		if got := deriveHeadline(tc.answer, tc.rowCount); got != tc.want {
			t.Errorf("deriveHeadline(%q, %d) = %q, want %q", tc.answer, tc.rowCount, got, tc.want)
		}
	}
}

func TestSqlBadge(t *testing.T) {
	tag, label := sqlBadge("Here are the 47 matching orders.")
	if tag != "green" || !strings.Contains(label, "SELECT-only") {
		t.Errorf("normal badge = (%q, %q), want green / SELECT-only", tag, label)
	}
	tag, label = sqlBadge("I couldn't translate that into a safe query.")
	if tag != "red" || !strings.Contains(label, "rejected") {
		t.Errorf("rejected badge = (%q, %q), want red / rejected", tag, label)
	}
}

func TestRowsToCSV(t *testing.T) {
	got := rowsToCSV([]string{"doc", "amt"}, [][]string{{"SO-9", "312480"}, {"SO-10", "5"}})
	want := "doc,amt\nSO-9,312480\nSO-10,5\n"
	if got != want {
		t.Errorf("rowsToCSV = %q, want %q", got, want)
	}
}

func TestFriendlyAskError(t *testing.T) {
	// 503 — analytic engine (AskPort) not on the classpath
	msg := friendlyAskError(&client.APIError{Status: 503, Body: `{"error":"No AskPort impl on classpath"}`}, "atelier")
	if !strings.Contains(msg, "unavailable") {
		t.Errorf("503 msg = %q, want 'unavailable'", msg)
	}
	// 404 — the hub does not answer on the chat door (Open edition, or lumnik.ai.enabled=false):
	// say so; the "no discovered schema" hint would name a cause the client cannot know.
	msg = friendlyAskError(&client.APIError{Status: 404, Body: ""}, "atelier")
	if !strings.Contains(msg, "chat is not available on this hub") || strings.Contains(msg, "discovered") {
		t.Errorf("404 msg = %q, want 'chat is not available on this hub' and no schema hint", msg)
	}
	// 500 — returns the real error + hint (the dominant cause: no discovered SchemaCard for REST/CSV scopes)
	msg = friendlyAskError(&client.APIError{Status: 500, Body: "boom"}, "atelier")
	if !strings.Contains(msg, "discovered") || !strings.Contains(msg, "Hint") {
		t.Errorf("500 msg = %q, want 'discovered' + 'Hint'", msg)
	}
	// network / non-API error
	msg = friendlyAskError(errors.New("connection refused"), "atelier")
	if !strings.Contains(msg, "reach") {
		t.Errorf("network msg = %q, want 'reach'", msg)
	}
}
