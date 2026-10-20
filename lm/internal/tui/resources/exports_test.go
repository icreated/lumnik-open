package resources

import (
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/rivo/tview"
)

type exportHub struct {
	mu     sync.Mutex // the last runs are read in parallel (lastRunFanOut)
	calls  []string
	denied bool
}

func (h *exportHub) serve(t *testing.T) *client.Client {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.mu.Lock()
		h.calls = append(h.calls, r.Method+" "+r.URL.Path)
		h.mu.Unlock()
		if h.denied {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/exports":
			_, _ = w.Write([]byte(`[{"name":"daily-clients","scope":"ventes","entityName":"Client","destinationKind":"s3","schedule":"0 6 * * *"}]`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/exports/daily-clients/runs":
			_, _ = w.Write([]byte(`[{"id":"aaaaaaaa-0000-0000-0000-000000000001","export":"daily-clients","status":"Failed",
				"trigger":"manual","attempt":1,"failureCode":"destination_refused","failureReason":"403 from bucket"}]`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/exports/daily-clients":
			_, _ = w.Write([]byte("kind: Export\n"))
		case r.Method == http.MethodPost:
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"runId":"bbbbbbbb-0000-0000-0000-000000000002"}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(srv.Close)
	return client.New(config.Context{Server: srv.URL})
}

func (h *exportHub) saw(want string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return slices.Contains(h.calls, want)
}

// rowText is row r's cells joined by |, for a contains-check over the whole row.
func rowText(t *tview.Table, r int) string {
	var cells []string
	for c := 0; c < t.GetColumnCount(); c++ {
		cells = append(cells, t.GetCell(r, c).Text)
	}
	return strings.Join(cells, "|")
}

func exportKey(p *Exports, k tcell.Key, r rune) { p.handleKey(tcell.NewEventKey(k, r, tcell.ModNone)) }

// :exports lists the declared exports with their last run; R runs one now, e re-opens its stored
// manifest in :new, Enter shows its runs and ^R retries a failed one (#1131).
func TestExports_listRunEditDrillRetry(t *testing.T) {
	h := &exportHub{}
	var toasts []string
	var edited string
	p := NewExports(nil, h.serve(t), func(m string) { toasts = append(toasts, m) }, nil)
	p.SetOnEdit(func(kind string, yaml []byte) { edited = kind + ":" + string(yaml) })
	p.Refresh()
	row := rowText(p.table, 1)
	for _, want := range []string{"daily-clients", "Client", "s3", "0 6 * * *", "Failed"} {
		if !strings.Contains(row, want) {
			t.Errorf("row %q lacks %q", row, want)
		}
	}
	p.table.Select(1, 0)

	exportKey(p, tcell.KeyRune, 'R')
	if !h.saw("POST /api/exports/daily-clients/runs") {
		t.Errorf("R did not start a run: %v", h.calls)
	}
	exportKey(p, tcell.KeyRune, 'e')
	if edited != "Export:kind: Export\n" {
		t.Errorf("e edited %q", edited)
	}

	exportKey(p, tcell.KeyEnter, 0)
	if !p.FilterActive() || !strings.Contains(p.table.GetCell(1, 1).Text, "Failed") {
		t.Fatalf("Enter did not show the runs: active=%v cell=%q", p.FilterActive(), p.table.GetCell(1, 1).Text)
	}
	p.table.Select(1, 0)
	exportKey(p, tcell.KeyCtrlR, 0)
	if !h.saw("POST /api/exports/daily-clients/runs/aaaaaaaa-0000-0000-0000-000000000001/retry") {
		t.Errorf("^R did not retry the failed run: %v", h.calls)
	}
	if len(toasts) == 0 || !strings.Contains(toasts[len(toasts)-1], "bbbbbbbb") {
		t.Errorf("toasts %q do not name the new run", toasts)
	}

	p.ClearFilter()
	if p.FilterActive() || p.table.GetCell(1, 0).Text != "daily-clients" {
		t.Errorf("Esc did not come back to the list: %q", p.table.GetCell(1, 0).Text)
	}
}

// /api/exports/* is integrator-only: an lm_user sees why the page is empty, not an empty list.
func TestExports_aForbiddenListSaysIntegratorOnly(t *testing.T) {
	h := &exportHub{denied: true}
	p := NewExports(nil, h.serve(t), nil, nil)
	p.Refresh()
	if got := p.table.GetCell(1, 0).Text; !strings.Contains(got, "integrator") {
		t.Errorf("forbidden list says %q", got)
	}
}

// Only a Failed run can be retried: ^R on another says so instead of asking the hub.
func TestExports_retryOnlyAFailedRun(t *testing.T) {
	h := &exportHub{}
	var toasts []string
	p := NewExports(nil, h.serve(t), func(m string) { toasts = append(toasts, m) }, nil)
	p.export = "daily-clients"
	p.allRuns = []client.ExportRun{{ID: "r1", Status: client.ExportRunCompleted}}
	p.render()
	p.table.Select(1, 0)
	exportKey(p, tcell.KeyCtrlR, 0)
	if len(h.calls) != 0 || len(toasts) != 1 {
		t.Errorf("calls %v toasts %q", h.calls, toasts)
	}
}
