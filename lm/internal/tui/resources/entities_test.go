package resources

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
)

type entityHub struct {
	mu    sync.Mutex
	calls []string
}

func (h *entityHub) serve(t *testing.T) *httptest.Server {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		h.mu.Lock()
		h.calls = append(h.calls, r.Method+" "+r.URL.RequestURI()+" "+strings.TrimSpace(string(body)))
		h.mu.Unlock()
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/entities":
			_, _ = w.Write([]byte(`[{"name":"Customer","scopes":["crm"],"sources":["t_crm","t_erp"],"resolution":true,"columns":["code","email"]}]`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/entities/Customer":
			_, _ = w.Write([]byte(`[
				{"code":"9003","email":"a@x","_entity_id":"5b0f2a9e-1111-4c1d-9a3e-000000000001","_resolution":"resolved","_handle":null,"_conflicts":"{}"},
				{"code":"C-77","email":"a@x","_entity_id":null,"_resolution":null,"_handle":"t_erp:C-77","_conflicts":"{}"}]`))
		case strings.Contains(r.URL.Path, "/resolution/identity/"):
			_, _ = w.Write([]byte(`[{"decision":"resolved","decidedBy":"email","occurredAt":"2026-10-16T10:00:00Z"}]`))
		case strings.HasSuffix(r.URL.Path, "/resolve"):
			_, _ = w.Write([]byte(`{"rowsSeen":2,"resolved":2}`))
		default:
			w.WriteHeader(http.StatusNoContent)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

func (h *entityHub) saw(want string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, c := range h.calls {
		if strings.Contains(c, want) {
			return true
		}
	}
	return false
}

// :entities lists the readable entities, Enter reads one's rows with their identity, Esc goes
// back (#1114).
func TestEntities_listThenRowsThenBack(t *testing.T) {
	h := &entityHub{}
	p := NewEntities(nil, client.New(config.Context{Server: h.serve(t).URL}), nil, nil)
	p.Refresh()
	if p.table.GetRowCount() != 2 {
		t.Fatalf("list rows = %d, want header + Customer", p.table.GetRowCount())
	}
	p.table.Select(1, 0)
	p.handleKey(tcell.NewEventKey(tcell.KeyEnter, 0, tcell.ModNone))
	if !h.saw("GET /api/entities/Customer?identity=true") || !p.FilterActive() {
		t.Fatalf("Enter did not open the rows with identity: %v", h.calls)
	}
	if p.table.GetRowCount() != 3 {
		t.Errorf("rows = %d, want header + 2", p.table.GetRowCount())
	}
	p.ClearFilter()
	if p.FilterActive() || p.table.GetRowCount() != 2 {
		t.Error("Esc did not bring the list back")
	}
}

// On the rows: mark one, assert same/distinct on another — reason, then a confirmation naming
// both handles; h shows the identity's history; ^R resolves; Del on the list deletes after a
// yes (#1116).
func TestEntities_resolutionActions(t *testing.T) {
	h := &entityHub{}
	var questions, shown []string
	p := NewEntities(nil, client.New(config.Context{Server: h.serve(t).URL}), nil, nil)
	p.SetAsk(func(label string, masked bool, done func(string)) { done("same customer, two systems") })
	p.SetConfirm(func(q, label string, yes func()) { questions = append(questions, q+" ["+label+"]"); yes() })
	p.SetShow(func(title, body string) { shown = append(shown, title+"\n"+body) })
	key := func(k tcell.Key, r rune) { p.handleKey(tcell.NewEventKey(k, r, tcell.ModNone)) }

	p.Refresh()
	p.table.Select(1, 0)
	key(tcell.KeyEnter, 0)
	p.table.Select(1, 0)
	key(tcell.KeyRune, 'm')
	p.table.Select(2, 0)
	key(tcell.KeyRune, '=') // a recorded assertion clears the mark
	p.table.Select(1, 0)
	key(tcell.KeyRune, 'm')
	p.table.Select(2, 0)
	key(tcell.KeyRune, '!')
	key(tcell.KeyRune, 'h')
	key(tcell.KeyCtrlR, 0)

	for _, want := range []string{
		// a resolved row is named by its entity id (leftRef), an unresolved one by its SOURCE:ID handle
		`POST /api/entities/Customer/assertions/same {"leftRef":"5b0f2a9e-1111-4c1d-9a3e-000000000001","right":"t_erp:C-77","reason":"same customer, two systems"}`,
		`POST /api/entities/Customer/assertions/distinct {"leftRef":"5b0f2a9e-1111-4c1d-9a3e-000000000001","right":"t_erp:C-77"`,
		"GET /api/entities/Customer/resolution/identity/t_erp/C-77",
		"POST /api/entities/Customer/resolve",
	} {
		if !h.saw(want) {
			t.Errorf("missing %q in:\n%s", want, strings.Join(h.calls, "\n"))
		}
	}
	if len(questions) != 2 || !strings.Contains(questions[0], "5b0f2a9e") || !strings.Contains(questions[0], "t_erp:C-77") {
		t.Errorf("questions = %q, want both handles named", questions)
	}
	for i := range questions { // an assertion deletes nothing: its yes is Assert, not Delete (#1150)
		if !strings.HasSuffix(questions[i], "[Assert]") {
			t.Errorf("question %d = %q, want it answered by Assert", i, questions[i])
		}
	}
	if len(shown) != 1 || !strings.Contains(shown[0], "email") {
		t.Errorf("history shown = %q", shown)
	}

	p.ClearFilter()
	p.table.Select(1, 0)
	key(tcell.KeyDelete, 0)
	if !h.saw("DELETE /api/entities/Customer") {
		t.Errorf("Del did not delete after a yes: %v", h.calls)
	}
	if len(questions) != 3 || !strings.HasSuffix(questions[2], "[Delete]") ||
		!strings.Contains(questions[2], "for every tenant") || !strings.Contains(questions[2], "every search index over it") {
		t.Errorf("questions = %q, want the delete naming what goes, answered by Delete", questions)
	}
}

// A resolved row fuses several source rows: h says where its history is instead of asking the hub
// for a source row it does not have (#1125 review).
func TestEntities_historyOnAResolvedRowExplains(t *testing.T) {
	h := &entityHub{}
	var errs []string
	p := NewEntities(nil, client.New(config.Context{Server: h.serve(t).URL}), nil, func(m string) { errs = append(errs, m) })
	p.SetShow(func(string, string) { t.Error("nothing to show for a resolved row") })
	p.Refresh()
	p.table.Select(1, 0)
	p.handleKey(tcell.NewEventKey(tcell.KeyEnter, 0, tcell.ModNone))
	p.table.Select(1, 0) // the resolved row
	p.handleKey(tcell.NewEventKey(tcell.KeyRune, 'h', tcell.ModNone))
	if h.saw("/resolution/identity/") || len(errs) != 1 || !strings.Contains(errs[0], "per source row") {
		t.Errorf("errs = %q, calls = %v", errs, h.calls)
	}
}

// A page past the last row says so; it never shows an inverted range (#1125 review).
func TestEntities_anEmptyPageSaysItIsPastTheEnd(t *testing.T) {
	p := NewEntities(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), nil, nil)
	p.entity, p.offset = "Customer", 200
	p.renderRows()
	if title := p.table.GetTitle(); strings.Contains(title, "201–200") || !strings.Contains(title, "past the last row") {
		t.Errorf("title = %q", title)
	}
}
