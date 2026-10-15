package resources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

func newTestAmbiguous(server string) *Ambiguous {
	return &Ambiguous{
		table:   tview.NewTable(),
		history: tview.NewTextView().SetDynamicColors(true),
		sortCol: style.NewColumnSort([]string{"SOURCE", "OCCURRED_AT"}),
		client:  client.New(config.Context{Server: server}),
		spawn:   func(f func()) { f() },
		enqueue: func(f func()) { f() },
	}
}

func TestAmbiguousRenderShowsAllColumns(t *testing.T) {
	a := newTestAmbiguous("")
	a.active.items = []client.AmbiguousIdentity{
		{IdentityRef: "src:1", Source: "src", SourceID: "1",
			Candidates: []string{"E1", "E2"}, DecidedBy: "cascade"},
	}
	a.render()
	if got := a.table.GetCell(1, 0).Text; got != "src:1" {
		t.Errorf("IDENTITY_REF = %q", got)
	}
	if got := a.table.GetCell(1, 3).Text; got != "E1, E2" {
		t.Errorf("CANDIDATES = %q, want joined candidate IDs", got)
	}
	if got := a.table.GetCell(1, 4).Text; got != "cascade" {
		t.Errorf("DECIDED_BY = %q", got)
	}
}

func TestAmbiguousSortBySource(t *testing.T) {
	a := newTestAmbiguous("")
	a.active.items = []client.AmbiguousIdentity{
		{Source: "zzz", SourceID: "2"},
		{Source: "aaa", SourceID: "1"},
	}
	a.sortCol.Next() // SOURCE ascending
	a.applySort()
	if a.active.items[0].Source != "aaa" {
		t.Errorf("after sorting by SOURCE the first row is %q", a.active.items[0].Source)
	}
}

// Sorting never leaves the table in a state where `selected()` would panic or return a
// stale/mismatched row: after re-render the selection index still resolves to a real item
// (whichever one now occupies that row). No page in this package re-tracks a row by identity
// across a sort (sources.go's own 's' handler does the same plain re-render) — this pins that
// existing, deliberate behaviour for :ambiguous rather than silently drifting from it.
func TestAmbiguousSortKeepsSelectionValidAcrossReorder(t *testing.T) {
	a := newTestAmbiguous("")
	a.active.items = []client.AmbiguousIdentity{
		{Source: "zzz", SourceID: "target"},
		{Source: "aaa", SourceID: "other"},
	}
	a.render()
	a.table.Select(1, 0)

	a.sortCol.Next() // SOURCE ascending -> "aaa" now first
	a.applySort()
	a.render()

	after, ok := a.selected()
	if !ok {
		t.Fatal("expected a row selected after reorder, not a dangling selection")
	}
	if after.SourceID != "other" {
		t.Errorf("selection after reorder = %q, want the row now at index 1 (%q)", after.SourceID, "other")
	}
}

func TestAmbiguousSubmitEmptyNameIsNoop(t *testing.T) {
	var hit bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit = true
		w.Write([]byte("[]"))
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)
	a.submit("")

	if hit {
		t.Error("submit(\"\") must not touch the client")
	}
	if a.active.entity != "" {
		t.Errorf("entity = %q, want unset after an empty submit", a.active.entity)
	}
}

// submit now runs the fetch off the UI thread (efficiency pass: it was the one synchronous
// network call left in this file). A stale response from a superseded submit must not clobber
// what a newer one asked for.
func TestAmbiguousSubmitDiscardsStaleResponseFromASupersededSubmit(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`[{"identityRef":"whoever-is-asked-for"}]`))
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)

	var pending func()
	a.spawn = func(f func()) { pending = f } // capture without running: simulates "still in flight"

	a.submit("A")
	if pending == nil {
		t.Fatal("expected submit to spawn a fetch")
	}
	firstFetch := pending

	// A second submit supersedes the first before its response arrives.
	a.submit("B")
	secondFetch := pending

	firstFetch() // A's (now stale) response lands first
	if a.active.entity != "" {
		t.Errorf("active entity = %q after A's stale response, want it to stay unset until B's lands", a.active.entity)
	}

	secondFetch() // B's response lands
	if a.active.entity != "B" {
		t.Errorf("active entity = %q, want %q once its own response lands", a.active.entity, "B")
	}
}

// Review round 2 (PR #667): a name-only guard cannot tell apart two requests for the SAME
// entity (press `r` twice) — both would pass a bare `pendingEntity == name` check, so an
// older, slower response could overwrite a newer one that already landed. submitGen guards by
// request identity instead, so this must hold even when every request names entity "A".
func TestAmbiguousSubmitSameNameOutOfOrderKeepsTheNewerResponse(t *testing.T) {
	callCount := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		callCount++
		if callCount == 1 {
			// Served to whichever fetch is INVOKED first below — the second submit's.
			w.Write([]byte(`[{"identityRef":"fresh-second-submit"}]`))
		} else {
			w.Write([]byte(`[{"identityRef":"stale-first-submit"}]`))
		}
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)

	var pending []func()
	a.spawn = func(f func()) { pending = append(pending, f) }

	a.submit("A") // request #1 (gen 1)
	a.submit("A") // request #2 (gen 2) — same name, e.g. pressing r twice
	if len(pending) != 2 {
		t.Fatalf("expected 2 spawned fetches, got %d", len(pending))
	}

	// The NEWER request (gen 2) resolves first...
	pending[1]()
	if got := a.active.items[0].IdentityRef; got != "fresh-second-submit" {
		t.Fatalf("setup: expected the newer response applied first, got %q", got)
	}

	// ...then the OLDER request (gen 1) resolves late. Its response must be discarded even
	// though it names the same entity as the one already showing.
	pending[0]()

	if got := a.active.items[0].IdentityRef; got != "fresh-second-submit" {
		t.Errorf("active items = %q, want the newer response to survive a late same-name reply", got)
	}
}

func TestAmbiguousSubmitListErrorShowsErrorAndKeepsTableEmpty(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
		w.Write([]byte(`{"error":"boom"}`))
	}))
	defer srv.Close()

	var errMsg string
	a := newTestAmbiguous(srv.URL)
	a.showError = func(m string) { errMsg = m }

	a.submit("Customer")

	if errMsg == "" {
		t.Fatal("expected showError to be called on a list failure")
	}
	if !strings.Contains(errMsg, "boom") {
		t.Errorf("error = %q, want the hub's own reason", errMsg)
	}
	if len(a.active.items) != 0 {
		t.Errorf("items = %v, want none after a failed list", a.active.items)
	}
}

// Review round 1 (PR #667): a failed switch (A listed successfully, then B 404/500s) must
// leave A's entity+rows on screen untouched — never a partial switch where ^R/Enter already
// target B while the table still shows A.
func TestAmbiguousSubmitFailedSwitchKeepsPreviousEntityAndItems(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "/entities/A/") {
			w.Write([]byte(`[{"identityRef":"a-ref","source":"src","sourceId":"1"}]`))
			return
		}
		w.WriteHeader(500)
		w.Write([]byte(`{"error":"boom"}`))
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)
	a.submit("A")
	if a.active.entity != "A" || len(a.active.items) != 1 {
		t.Fatalf("setup: expected A loaded, got entity=%q items=%d", a.active.entity, len(a.active.items))
	}

	var errMsg string
	a.showError = func(m string) { errMsg = m }
	a.submit("B")

	if errMsg == "" {
		t.Fatal("expected an error for the failed switch to B")
	}
	if a.active.entity != "A" {
		t.Errorf("active entity = %q, want it to stay %q after a failed switch to B", a.active.entity, "A")
	}
	if len(a.active.items) != 1 || a.active.items[0].IdentityRef != "a-ref" {
		t.Errorf("active items = %+v, want A's rows still shown after a failed switch to B", a.active.items)
	}
}

// A successful switch must not let the PREVIOUS entity's history linger in the panel looking
// current — the new entity's rows are different identities even when a row happens to render
// at the same table position.
func TestAmbiguousSubmitSuccessClearsStaleHistoryPanel(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("[]"))
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)
	a.history.SetText("stale ledger from a previous entity")

	a.submit("B")

	if got := a.history.GetText(false); strings.Contains(got, "stale ledger") {
		t.Errorf("history panel = %q, want it reset on a successful entity switch", got)
	}
}

// Review round 1 (PR #667): the stale-history guard must key on the ACTIVE ENTITY too, not
// only source/sourceID — two entity manifests can share a source identity, so an in-flight
// history response for A must not render after the operator has switched to B, even if B's
// selected row happens to carry the same source/sourceID.
func TestAmbiguousLoadHistoryDiscardsStaleResponseAfterEntitySwitch(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`[{"ruleDeclaredId":"stale-from-A","outcome":"resolved"}]`))
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)
	a.active = ambiguousActive{entity: "A", items: []client.AmbiguousIdentity{{Source: "src", SourceID: "1"}}}
	a.render()
	a.table.Select(1, 0)

	var pending func()
	a.spawn = func(f func()) { pending = f } // capture without running: simulates "still in flight"

	a.loadHistory()
	if pending == nil {
		t.Fatal("expected loadHistory to spawn a fetch")
	}

	// The operator switches to a DIFFERENT entity while A's history request is in flight.
	// Same source/sourceID on B's row on purpose — only the entity differs, isolating the
	// entity check from the row check.
	a.active = ambiguousActive{entity: "B", items: []client.AmbiguousIdentity{{Source: "src", SourceID: "1"}}}
	a.render()
	a.table.Select(1, 0)

	pending() // the A response arrives now, after the switch to B

	if a.historyLoading {
		t.Error("historyLoading must still clear even when the response is discarded as stale")
	}
	if got := a.history.GetText(false); strings.Contains(got, "stale-from-A") {
		t.Errorf("history panel = %q, must not render A's late response after switching to B", got)
	}
	if got := a.history.GetText(false); got != ambiguousHistoryPlaceholder {
		t.Errorf("history panel = %q, want it reset to the placeholder rather than left on \"loading…\" (Copilot review, PR #667)", got)
	}
}

// Copilot review, PR #667: a stale discard used to `return` without touching the panel, so
// moving the selection to a row with no history request of its own left "loading…" stuck
// there — misleading, since nothing is actually loading for the row now shown.
func TestAmbiguousLoadHistoryStaleDiscardResetsPanelInsteadOfStrandingLoading(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`[{"ruleDeclaredId":"r1"}]`))
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)
	a.active = ambiguousActive{entity: "A", items: []client.AmbiguousIdentity{
		{Source: "src", SourceID: "1"},
		{Source: "src", SourceID: "2"},
	}}
	a.render()
	a.table.Select(1, 0)

	var pending func()
	a.spawn = func(f func()) { pending = f } // capture without running: simulates "still in flight"

	a.loadHistory()
	if pending == nil {
		t.Fatal("expected loadHistory to spawn a fetch")
	}
	if got := a.history.GetText(false); got != "[yellow]loading…[white]" {
		t.Fatalf("setup: expected the loading indicator to be showing, got %q", got)
	}

	// The selection moves to a DIFFERENT row (same entity) before the request returns —
	// no Enter pressed on it, so nothing is fetching for this row.
	a.table.Select(2, 0)

	pending() // row 1's now-stale response lands

	if got := a.history.GetText(false); got != ambiguousHistoryPlaceholder {
		t.Errorf("history panel = %q, want it reset to the placeholder, not stuck on \"loading…\" "+
			"for a row nothing is loading for", got)
	}
}

func TestAmbiguousResolveNoEntityShowsError(t *testing.T) {
	var errMsg string
	a := newTestAmbiguous("")
	a.showError = func(m string) { errMsg = m }

	a.resolve()

	if errMsg == "" {
		t.Error("expected an error when ^R is pressed with no entity typed yet")
	}
}

func TestAmbiguousResolveSuccessTogglesFlagAndRefreshes(t *testing.T) {
	var calls int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if strings.HasSuffix(r.URL.Path, "/resolve") {
			w.Write([]byte(`{"rowsSeen":10,"resolved":8,"ambiguous":2}`))
			return
		}
		w.Write([]byte("[]"))
	}))
	defer srv.Close()

	var toasted string
	a := newTestAmbiguous(srv.URL)
	a.toast = func(m string) { toasted = m }
	a.active.entity = "Customer"

	a.resolve()

	if a.resolving {
		t.Error("resolving must clear once the synchronous (test-seam) call returns")
	}
	if calls != 2 { // resolve, then the post-resolve refetch
		t.Errorf("client hit %d times, want 2 (resolve + refresh)", calls)
	}
	if !strings.Contains(toasted, "8 resolved") {
		t.Errorf("toast = %q, want the pass summary counters", toasted)
	}
}

func TestAmbiguousResolveErrorToastsAndClearsFlag(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
		w.Write([]byte(`{"error":"resolution failed"}`))
	}))
	defer srv.Close()

	var errMsg string
	a := newTestAmbiguous(srv.URL)
	a.showError = func(m string) { errMsg = m }
	a.active.entity = "Customer"

	a.resolve()

	if a.resolving {
		t.Error("resolving must clear on error too")
	}
	if !strings.Contains(errMsg, "resolution failed") {
		t.Errorf("error = %q, want the hub's own reason", errMsg)
	}
}

// Review round 2 (PR #667): resolve(A)'s completion must not silently yank the screen back to
// A if the operator has already switched to a different entity (B) while the resolve — a slow
// POSTSlow pass — was still in flight.
func TestAmbiguousResolveDoesNotSupersedeANewerEntitySwitch(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/resolve"):
			w.Write([]byte(`{"rowsSeen":1,"resolved":1,"ambiguous":0}`))
		case strings.Contains(r.URL.Path, "/entities/B/"):
			w.Write([]byte(`[{"identityRef":"b-row"}]`))
		default:
			w.Write([]byte(`[{"identityRef":"a-row"}]`))
		}
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)
	a.active = ambiguousActive{entity: "A", items: []client.AmbiguousIdentity{{IdentityRef: "a-row"}}}

	var pendingResolve func()
	a.spawn = func(f func()) { pendingResolve = f } // capture resolve's fetch: "still in flight"

	a.resolve()
	if pendingResolve == nil {
		t.Fatal("expected resolve to spawn a fetch")
	}

	// The operator switches to B while resolve(A) is still running. Restore synchronous
	// spawn so this switch actually completes within the test.
	a.spawn = func(f func()) { f() }
	a.submit("B")
	if a.active.entity != "B" {
		t.Fatalf("setup: expected B active after the switch, got %q", a.active.entity)
	}

	// A's resolve now completes. Its automatic list refresh must not override B.
	pendingResolve()

	if a.active.entity != "B" {
		t.Errorf("active entity = %q, want it to stay %q — resolve(A)'s completion must not "+
			"override a newer switch to B", a.active.entity, "B")
	}
}

// Review round 3 (PR #667): the round-2 fix checked a.active.entity at resolve's completion,
// but active.entity only reflects a LANDED response — if the operator's switch to B is still
// in flight (not yet landed) when resolve(A) finishes, active.entity is still "A", so that
// check would wrongly pass and resolve's own submit(A) would stomp B's still-pending result
// once it lands. submitGen must be snapshotted at resolve's START and compared at completion,
// since it reflects intent the instant submit() is called — landed or not.
func TestAmbiguousResolveDefersToASwitchStillInFlightWhenItCompletes(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/resolve"):
			w.Write([]byte(`{"rowsSeen":1,"resolved":1,"ambiguous":0}`))
		case strings.Contains(r.URL.Path, "/entities/B/"):
			w.Write([]byte(`[{"identityRef":"b-row"}]`))
		default:
			w.Write([]byte(`[{"identityRef":"a-row"}]`))
		}
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)
	a.active = ambiguousActive{entity: "A", items: []client.AmbiguousIdentity{{IdentityRef: "a-row"}}}

	var pending []func()
	a.spawn = func(f func()) { pending = append(pending, f) } // capture everything, run nothing yet

	a.resolve() // resolve(A) starts, snapshots submitGen
	if len(pending) != 1 {
		t.Fatalf("expected resolve to spawn 1 fetch, got %d", len(pending))
	}
	resolveFetch := pending[0]

	a.submit("B") // operator switches to B while resolve(A) is still in flight — NOT landed yet
	if len(pending) != 2 {
		t.Fatalf("expected submit(B) to spawn a fetch too, got %d", len(pending))
	}
	bFetch := pending[1]

	resolveFetch() // resolve(A) completes BEFORE B's list request lands
	bFetch()       // B's request finally lands

	if a.active.entity != "B" {
		t.Errorf("active entity = %q, want %q — resolve(A)'s completion must defer to a switch "+
			"already requested, even one that had not landed yet when it finished", a.active.entity, "B")
	}
}

// The single-flight guard is what makes ^R safe to press twice in a row while a slow
// POSTSlow pass is still running — a second concurrent resolve against the same entity
// must never be sent.
func TestAmbiguousResolveSingleFlight(t *testing.T) {
	var calls int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)
	a.active.entity = "Customer"
	a.resolving = true // simulates a resolve already in flight

	a.resolve()

	if calls != 0 {
		t.Errorf("client hit %d times, want 0 — a second ^R must be a no-op while one is in flight", calls)
	}
}

func TestAmbiguousHandleKeyCtrlRInvokesResolve(t *testing.T) {
	var calls int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)
	a.active.entity = "Customer"
	a.render()

	a.handleKey(tcell.NewEventKey(tcell.KeyCtrlR, 0, tcell.ModCtrl))

	if calls == 0 {
		t.Error("^R via handleKey did not reach the client")
	}
}

func TestAmbiguousLoadHistorySuccess(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`[{"ruleDeclaredId":"r1","outcome":"resolved","decidedBy":"cascade"}]`))
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)
	a.active.entity = "Customer"
	a.active.items = []client.AmbiguousIdentity{{Source: "src", SourceID: "1"}}
	a.render()
	a.table.Select(1, 0)

	a.loadHistory()

	if a.historyLoading {
		t.Error("historyLoading must clear once the (test-seam synchronous) fetch returns")
	}
	got := a.history.GetText(false)
	if !strings.Contains(got, "r1") || !strings.Contains(got, "resolved") {
		t.Errorf("history view = %q, want the fetched evidence event", got)
	}
}

func TestAmbiguousLoadHistoryErrorShowsErrorInPanel(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
		w.Write([]byte(`{"error":"no history"}`))
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)
	a.active.entity = "Customer"
	a.active.items = []client.AmbiguousIdentity{{Source: "src", SourceID: "1"}}
	a.render()
	a.table.Select(1, 0)

	a.loadHistory()

	got := a.history.GetText(false)
	if !strings.Contains(got, "no history") {
		t.Errorf("history view = %q, want the hub's own error", got)
	}
}

func TestAmbiguousLoadHistoryNoSelectionDoesNotTouchClient(t *testing.T) {
	var hit bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit = true
		w.Write([]byte("[]"))
	}))
	defer srv.Close()

	a := newTestAmbiguous(srv.URL)
	a.render()

	a.loadHistory()

	if hit {
		t.Error("loadHistory with no selection must not touch the client")
	}
}

func TestAmbiguousDescribeEmptyWithoutSelection(t *testing.T) {
	a := newTestAmbiguous("")
	if _, _, ok := a.SelectedDescribe(); ok {
		t.Error("no rows -> describe must report !ok, not panic")
	}
}

func TestAmbiguousDescribeIncludesDiscardedCandidates(t *testing.T) {
	a := newTestAmbiguous("")
	a.active.items = []client.AmbiguousIdentity{{
		IdentityRef: "src:1", OccurredAt: time.Now(),
		DiscardedCandidates: []client.ProposedCandidate{{EntityID: "E9", Score: 0.42}},
	}}
	a.render()
	a.table.Select(1, 0)

	_, body, ok := a.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if !strings.Contains(body, "E9") {
		t.Errorf("describe body missing discarded candidate: %q", body)
	}
}

func TestAmbiguousDescribeIncludesSetAside(t *testing.T) {
	a := newTestAmbiguous("")
	a.active.items = []client.AmbiguousIdentity{{
		IdentityRef: "src:1", OccurredAt: time.Now(),
		SetAside: []client.SetAside{{EntityID: "E7", Reason: "same-source", Rule: strPtr("email")}},
	}}
	a.render()
	a.table.Select(1, 0)

	_, body, ok := a.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	for _, want := range []string{"set aside:", "E7", "same-source", "rule email"} {
		if !strings.Contains(body, want) {
			t.Errorf("describe body missing %q: %q", want, body)
		}
	}
}

func TestRenderHistoryShowsWhatWasSetAside(t *testing.T) {
	got := renderHistory([]client.EvidenceEvent{{
		Outcome: "resolved", OccurredAt: time.Now(),
		SetAside: []client.SetAside{{EntityID: "E7", Reason: "same-source"}},
	}})
	for _, want := range []string{"set aside:", "E7", "same-source", "same assertion"} {
		if !strings.Contains(got, want) {
			t.Errorf("history missing %q: %q", want, got)
		}
	}
	if strings.Contains(renderHistory([]client.EvidenceEvent{{Outcome: "resolved", OccurredAt: time.Now()}}), "set aside") {
		t.Error("an event that set nothing aside must not print the section")
	}
}

func strPtr(s string) *string { return &s }
