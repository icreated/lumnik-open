package resources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
)

type searchHub struct {
	mu          sync.Mutex // the capability is asked beside the index list, in parallel
	calls       []string
	capability  string
	listDenied  bool
	queryDenied bool
}

func (h *searchHub) serve(t *testing.T) *client.Client {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.mu.Lock()
		h.calls = append(h.calls, r.Method+" "+r.URL.Path+"?"+r.URL.RawQuery)
		h.mu.Unlock()
		switch {
		case r.URL.Path == "/api/search/capability":
			_, _ = w.Write([]byte(h.capability))
		case r.URL.Path == "/api/platform/search/index" && h.listDenied:
			w.WriteHeader(http.StatusForbidden)
		case r.URL.Path == "/api/platform/search/index":
			_, _ = w.Write([]byte(`[{"name":"clients-idx","status":"ready","liveGeneration":"g1","documentCount":42,
				"lagSeconds":3,"activeRun":{"runId":"r","phase":"counting","startedAt":"2026-10-16T08:00:00Z"},"invalid":false}]`))
		case r.URL.Path == "/api/search" && h.queryDenied:
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusForbidden)
			_, _ = w.Write([]byte(`{"error":"scope not granted"}`))
		case r.URL.Path == "/api/search":
			_, _ = w.Write([]byte(`{"hits":[{"entity":"Client","key":{"id":"7"},"score":1.5,
				"fields":{"name":"Durand"},"highlights":{"name":["<b>Durand</b>"]}}]}`))
		case r.Method == http.MethodPost:
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"runId":"cccccccc-0000-0000-0000-000000000003"}`))
		default:
			w.WriteHeader(http.StatusNoContent)
		}
	}))
	t.Cleanup(srv.Close)
	return client.New(config.Context{Server: srv.URL})
}

func (h *searchHub) saw(prefix string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, c := range h.calls {
		if strings.HasPrefix(c, prefix) {
			return true
		}
	}
	return false
}

const (
	capAvailable    = `{"state":"available","provider":"pg","reason":null,"version":"1"}`
	capNotInstalled = `{"state":"unavailable","provider":null,"reason":"not_installed","version":null}`
)

func searchKey(p *Search, k tcell.Key, r rune) { p.handleKey(tcell.NewEventKey(k, r, tcell.ModNone)) }

// Zero providers is the normal state: the page says Search is not installed, never an empty result
// (#1132).
func TestSearch_saysWhenNoProviderIsInstalled(t *testing.T) {
	h := &searchHub{capability: capNotInstalled}
	p := NewSearch(nil, h.serve(t), func() string { return "" }, nil, nil)
	p.Refresh()
	if got := p.banner.GetText(false); !strings.Contains(got, "not installed") {
		t.Errorf("banner %q", got)
	}
}

// The indexes are listed with their status; R rebuilds, c cancels, Del removes after a yes.
func TestSearch_indexesRebuildCancelDelete(t *testing.T) {
	h := &searchHub{capability: capAvailable}
	var asked []string
	p := NewSearch(nil, h.serve(t), func() string { return "" }, nil, nil)
	p.SetConfirm(func(q, label string, yes func()) { asked = append(asked, q+" ["+label+"]"); yes() })
	p.Refresh()
	if !strings.Contains(p.banner.GetText(false), "available") {
		t.Errorf("banner %q", p.banner.GetText(false))
	}
	row := rowText(p.table, 1)
	for _, want := range []string{"clients-idx", "ready", "42", "counting"} {
		if !strings.Contains(row, want) {
			t.Errorf("row %q lacks %q", row, want)
		}
	}
	p.table.Select(1, 0)
	searchKey(p, tcell.KeyRune, 'R')
	searchKey(p, tcell.KeyRune, 'c')
	searchKey(p, tcell.KeyDelete, 0)
	for _, want := range []string{"POST /api/platform/search/index/clients-idx/rebuild",
		"POST /api/platform/search/index/clients-idx/cancel", "DELETE /api/search-indexes/clients-idx"} {
		if !h.saw(want) {
			t.Errorf("no %s in %v", want, h.calls)
		}
	}
	if len(asked) != 1 || !strings.Contains(asked[0], "clients-idx") || !strings.Contains(asked[0], "[Delete]") {
		t.Errorf("Del asked %q", asked)
	}
}

// / runs a query bound to the session scope, like the PWA's Search tab, and shows the hits; clearing
// it comes back to the indexes.
func TestSearch_queryIsScopeBoundAndShowsHits(t *testing.T) {
	h := &searchHub{capability: capAvailable}
	p := NewSearch(nil, h.serve(t), func() string { return "ventes" }, nil, nil)
	p.SetTextFilter("durand")
	if !h.saw("GET /api/search?q=durand&scope=ventes") {
		t.Errorf("query calls %v", h.calls)
	}
	row := p.table.GetCell(1, 0).Text + "|" + p.table.GetCell(1, 3).Text
	if !strings.Contains(row, "Client") || !strings.Contains(row, "Durand") || strings.Contains(row, "<b>") {
		t.Errorf("hit row %q", row)
	}
	searchKey(p, tcell.KeyDelete, 0) // a hit is not an index: nothing to delete
	if h.saw("DELETE") {
		t.Errorf("Del on a hit reached the hub: %v", h.calls)
	}
	p.SetTextFilter("")
	if p.table.GetCell(1, 0).Text != "clients-idx" {
		t.Errorf("clearing the query left %q", p.table.GetCell(1, 0).Text)
	}
}

// The index status doors are integrator-only: an lm_user is told so, and that / still queries.
func TestSearch_aForbiddenIndexListSaysIntegratorOnly(t *testing.T) {
	h := &searchHub{capability: capAvailable, listDenied: true}
	p := NewSearch(nil, h.serve(t), func() string { return "" }, nil, nil)
	p.Refresh()
	if got := p.table.GetCell(1, 0).Text; !strings.Contains(got, "integrator") {
		t.Errorf("forbidden list says %q", got)
	}
}

// A query refused 403 is the scope refusal, said as the hub says it — not the index doors'
// integrator-only, whose "/ still queries" would sit under a failed / (#1145 review).
func TestSearch_aRefusedQueryIsTheScopeRefusal(t *testing.T) {
	h := &searchHub{capability: capAvailable, queryDenied: true}
	p := NewSearch(nil, h.serve(t), func() string { return "ventes" }, nil, nil)
	p.SetTextFilter("durand")
	got := p.table.GetCell(1, 0).Text
	if !strings.Contains(got, "scope not granted") || strings.Contains(got, "integrator-only") {
		t.Errorf("refused query shows %q", got)
	}
}

func TestSearch_aWithheldOrInvalidIndexSaysSoInsteadOfItsBuildState(t *testing.T) {
	reason := "spec.fields[2].name: 'city' is gone"
	for _, tc := range []struct {
		name   string
		status client.SearchIndexStatus
		want   string
	}{
		{"withheld", client.SearchIndexStatus{Status: "ready", Withheld: true}, "withheld"},
		{"invalid wins", client.SearchIndexStatus{Status: "ready", Invalid: true, InvalidReason: reason, Withheld: true}, "invalid " + reason},
		{"served", client.SearchIndexStatus{Status: "ready"}, "ready"},
	} {
		if got := indexCells(tc.status)[1]; got != tc.want {
			t.Errorf("%s: STATUS cell = %q, want %q", tc.name, got, tc.want)
		}
	}
}
