package resources

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
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
