package resources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
)

type manifestHub struct{ calls []string }

func (h *manifestHub) serve(t *testing.T) *client.Client {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.calls = append(h.calls, r.Method+" "+r.URL.Path)
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/apps":
			_, _ = w.Write([]byte(`[{"name":"desk","title":"Desk","scope":"clients","default":true,"tabs":["chat"]}]`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/apps/desk":
			_, _ = w.Write([]byte(`{"name":"desk","title":"Desk","scope":"clients","icon":"clients","default":true,"tabs":["chat"]}`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/workflows":
			_, _ = w.Write([]byte(`[{"name":"commande","table":"connector.t_cmd","column":"status","scope":"ventes","enabled":true}]`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/views":
			_, _ = w.Write([]byte(`[{"name":"clients-desk","scope":"clients","title":"Clients"}]`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/processes":
			_, _ = w.Write([]byte(`[{"name":"gold-arrive","table":"connector.t_cust","emit":"decision.gold-arrive","enabled":true},` +
				`{"name":"late-order","table":"connector.t_cmd","emit":"decision.late","enabled":false}]`))
		case r.Method == http.MethodGet:
			_, _ = w.Write([]byte("kind: X\n"))
		default:
			_, _ = w.Write([]byte(`{}`))
		}
	}))
	t.Cleanup(srv.Close)
	return client.New(config.Context{Server: srv.URL})
}

func (h *manifestHub) saw(want string) bool {
	for _, c := range h.calls {
		if c == want {
			return true
		}
	}
	return false
}

// :apps, :workflows and :views list what the hub serves the PWA, and each row can be edited
// (its stored manifest in :new), deleted after a yes, and — a workflow — enabled/disabled (#1119).
func TestManifestPages(t *testing.T) {
	for _, c := range []struct {
		name   string
		source func(*client.Client) ManifestSource
		cell   string
		get    string
		del    string
		toggle string
	}{
		{"apps", AppsSource, "Desk", "GET /api/apps/desk", "DELETE /api/apps/desk", ""},
		{"workflows", WorkflowsSource, "connector.t_cmd.status", "GET /api/workflows/commande/manifest",
			"DELETE /api/workflows/commande", "PUT /api/workflows/commande/enabled"},
		{"views", ViewsSource, "Clients", "GET /api/views/clients-desk", "DELETE /api/views/clients-desk", ""},
		{"processes", ProcessesSource, "connector.t_cust", "GET /api/processes/gold-arrive/manifest",
			"DELETE /api/processes/gold-arrive", "PUT /api/processes/gold-arrive/enabled"},
	} {
		t.Run(c.name, func(t *testing.T) {
			h := &manifestHub{}
			var edited, asked []string
			p := NewManifests(nil, c.source(h.serve(t)), nil, nil)
			p.SetOnEdit(func(kind string, yaml []byte) { edited = append(edited, kind+":"+string(yaml)) })
			p.SetConfirm(func(q, label string, yes func()) { asked = append(asked, q+" ["+label+"]"); yes() })
			p.Refresh()
			if !strings.Contains(p.table.GetCell(1, 1).Text+p.table.GetCell(1, 2).Text, c.cell) {
				t.Fatalf("row 1 = %q / %q, want %q", p.table.GetCell(1, 1).Text, p.table.GetCell(1, 2).Text, c.cell)
			}
			p.table.Select(1, 0)
			key := func(k tcell.Key, r rune) { p.handleKey(tcell.NewEventKey(k, r, tcell.ModNone)) }
			key(tcell.KeyRune, 'e')
			if len(edited) != 1 || !h.saw(c.get) {
				t.Errorf("e: edited %v, calls %v", edited, h.calls)
			}
			key(tcell.KeyCtrlP, 0)
			if c.toggle != "" && !h.saw(c.toggle) {
				t.Errorf("^P did not toggle: %v", h.calls)
			}
			key(tcell.KeyDelete, 0)
			if len(asked) != 1 || !strings.HasSuffix(asked[0], "[Delete]") || !h.saw(c.del) {
				t.Errorf("Del: asked %q, calls %v", asked, h.calls)
			}
		})
	}
}

// :processes is a Manifests page (#1139): every gesture its own page had, on the shared one.
func TestProcessesSource_keepsEveryGesture(t *testing.T) {
	h := &manifestHub{}
	var edited, toasts []string
	p := NewManifests(nil, ProcessesSource(h.serve(t)), func(m string) { toasts = append(toasts, m) }, nil)
	p.SetOnEdit(func(kind string, yaml []byte) { edited = append(edited, kind) })
	p.SetConfirm(func(_, _ string, yes func()) { yes() })
	p.Refresh()

	row := func(r int) string {
		var cells []string
		for c := 0; c < p.table.GetColumnCount(); c++ {
			cells = append(cells, p.table.GetCell(r, c).Text)
		}
		return strings.Join(cells, "|")
	}
	if got := row(0); got != "NAME|TABLE|EMIT|ENABLED" {
		t.Errorf("header = %q", got)
	}
	if got := row(2); got != "late-order|connector.t_cmd|decision.late|no" {
		t.Errorf("row 2 = %q", got)
	}
	key := func(k tcell.Key, r rune) { p.handleKey(tcell.NewEventKey(k, r, tcell.ModNone)) }
	p.table.Select(2, 0)
	key(tcell.KeyRune, 'e')
	if len(edited) != 1 || edited[0] != "Process" || !h.saw("GET /api/processes/late-order/manifest") {
		t.Errorf("e: edited %v, calls %v", edited, h.calls)
	}
	key(tcell.KeyCtrlP, 0)
	if !h.saw("PUT /api/processes/late-order/enabled") || len(toasts) == 0 || toasts[len(toasts)-1] != "enabled: late-order" {
		t.Errorf("^P on a disabled process: toasts %v, calls %v", toasts, h.calls)
	}
	if text, ok := p.SelectedYank(); !ok || text != "late-order" {
		t.Errorf("y = %q, %v", text, ok)
	}
	if name, body, ok := p.SelectedDescribe(); !ok || name != "late-order" || !strings.Contains(body, "decision.late") {
		t.Errorf("d = %q %q %v", name, body, ok)
	}
}

// / narrows every manifest page to the rows holding a text, survives a refresh, and y yanks the
// row on screen (#1139).
func TestManifests_filterAndYank(t *testing.T) {
	h := &manifestHub{}
	p := NewManifests(nil, ProcessesSource(h.serve(t)), nil, nil)
	p.Refresh()
	p.SetTextFilter("LATE")
	if p.TextFilter() != "LATE" || len(p.items) != 1 || p.items[0].Name != "late-order" {
		t.Fatalf("filter LATE kept %v", p.items)
	}
	p.Refresh()
	if len(p.items) != 1 {
		t.Errorf("a refresh dropped the filter: %v", p.items)
	}
	p.table.Select(1, 0)
	if text, ok := p.SelectedYank(); !ok || text != "late-order" {
		t.Errorf("y = %q, %v", text, ok)
	}
	p.SetTextFilter("")
	if len(p.items) != 2 {
		t.Errorf("clearing the filter shows %d rows, want 2", len(p.items))
	}
	if h := p.Hints(); !strings.Contains(h, "/: filter") || !strings.Contains(h, "y: yank") {
		t.Errorf("hints %q", h)
	}
}

// The footer offers only the gestures wired on this page (#1129 review).
func TestManifestHints_offerOnlyWiredGestures(t *testing.T) {
	src := ManifestSource{Title: "x", Get: func(string) ([]byte, error) { return nil, nil }, Delete: func(string) error { return nil }}
	p := NewManifests(nil, src, nil, nil)
	if h := p.Hints(); strings.Contains(h, "e:") || strings.Contains(h, "Del:") {
		t.Errorf("unwired page hints %q", h)
	}
	p.SetOnEdit(func(string, []byte) {})
	p.SetConfirm(func(string, string, func()) {})
	if h := p.Hints(); !strings.Contains(h, "e:") || !strings.Contains(h, "Del:") {
		t.Errorf("wired page hints %q", h)
	}
}
