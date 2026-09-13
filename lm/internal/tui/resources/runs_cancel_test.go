package resources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

func runsColumnSort() *style.ColumnSort {
	return style.NewColumnSort(runSortColumns)
}

// `c` is only wired through handleKey, never called directly by a caller (unlike the
// mouse-free actions on the other pages that are exercised via their own method too) — so
// this pins the key-to-action mapping itself, not just cancel()'s body.
func TestRunsHandleKey_cInvokesCancel(t *testing.T) {
	var hit string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		hit = req.URL.Path
		w.WriteHeader(http.StatusAccepted)
	}))
	defer srv.Close()

	var toasted string
	r := &Runs{
		table:  tview.NewTable(),
		sort:   runsColumnSort(),
		client: client.New(config.Context{Server: srv.URL}),
		toast:  func(m string) { toasted = m },
		items:  []types.Run{{ID: "run-1", ConnectorID: "c-1", ConnectorName: "ad_user"}},
	}
	r.render()
	r.table.Select(1, 0)

	r.handleKey(tcell.NewEventKey(tcell.KeyRune, 'c', tcell.ModNone))

	if hit != "/api/platform/runs/run-1/cancel" {
		t.Fatalf("cancel did not hit the endpoint, path = %q", hit)
	}
	if toasted == "" {
		t.Fatal("expected a toast on success")
	}
}

// The 202 only means the request was recorded — the wording must say REQUESTED and
// must never claim the run is cancelled or stopped (the chunk in flight may still
// finish normally).
func TestRunsCancel_successToastsRequestedNeverClaimsCancelled(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		w.WriteHeader(http.StatusAccepted)
	}))
	defer srv.Close()

	var toasted string
	r := &Runs{
		table:  tview.NewTable(),
		sort:   runsColumnSort(),
		client: client.New(config.Context{Server: srv.URL}),
		toast:  func(m string) { toasted = m },
		items:  []types.Run{{ID: "run-1", ConnectorID: "c-1", ConnectorName: "ad_user"}},
	}
	r.render()
	r.table.Select(1, 0)

	r.cancel()

	if !strings.Contains(toasted, "requested") {
		t.Errorf("toast = %q, want it to say the cancellation was REQUESTED", toasted)
	}
	if strings.Contains(toasted, "cancelled") || strings.Contains(toasted, "stopped") {
		t.Errorf("toast = %q, must never claim the run IS cancelled/stopped — a 202 only records the request", toasted)
	}
	if !strings.Contains(toasted, "ad_user") {
		t.Errorf("toast = %q, want it to name the run being cancelled", toasted)
	}
}

// A 409 means the hub refused — "already ended" or "not in flight". The hub's own
// reason is the diagnosis and must reach the toast unchanged, not a generic failure.
func TestRunsCancel_409ToastsHubReasonUnchanged(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"error":"run run-1 already ended (Completed)"}`))
	}))
	defer srv.Close()

	var toasted string
	r := &Runs{
		table:  tview.NewTable(),
		sort:   runsColumnSort(),
		client: client.New(config.Context{Server: srv.URL}),
		toast:  func(m string) { toasted = m },
		items:  []types.Run{{ID: "run-1", ConnectorID: "c-1", ConnectorName: "ad_user"}},
	}
	r.render()
	r.table.Select(1, 0)

	r.cancel()

	if !strings.Contains(toasted, "run run-1 already ended (Completed)") {
		t.Errorf("toast = %q, want the hub's own refusal reason verbatim", toasted)
	}
}

// No selection must not reach the client at all — a nil client here would panic if it did.
func TestRunsCancel_noSelectionDoesNotTouchClient(t *testing.T) {
	var toasted string
	r := &Runs{
		table:  tview.NewTable(),
		sort:   runsColumnSort(),
		client: nil,
		toast:  func(m string) { toasted = m },
	}
	r.render()

	defer func() {
		if rec := recover(); rec != nil {
			t.Fatalf("cancel with no selection must refuse before touching the client, panicked: %v", rec)
		}
	}()
	r.cancel()

	if toasted != "" {
		t.Errorf("toast = %q, want no toast when nothing is selected", toasted)
	}
}
