package resources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/rivo/tview"
)

// Ctrl-L used to call HookReload, POST /api/platform/hooks/{id}/reload — an endpoint that does
// not exist on the hub (#610, same finding as `lm hook reload`). handleKey has no Ctrl-L branch
// left at all, so no server is stubbed here: an unreachable address is enough to prove nothing
// could be sent even if a branch reappeared, without a real listener.
func TestHooksHandleKey_ctrlLNeverTouchesTheHub(t *testing.T) {
	h := NewHooks(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), func(string) {})

	ev := tcell.NewEventKey(tcell.KeyCtrlL, 0, tcell.ModNone)
	if got := h.handleKey(ev); got != ev {
		t.Errorf("Ctrl-L must fall through unhandled, got %v", got)
	}
}

func TestHooksHints_doesNotAdvertiseReload(t *testing.T) {
	h := &Hooks{table: tview.NewTable()}
	if got := h.Hints(); got != "d: describe  r: refresh  :: switch  ?: help  q: quit" {
		t.Errorf("Hints() = %q, must not mention a ^L reload that no longer does anything", got)
	}
}

// d describes the hook as `lm hook get` shows it — the singular door's richer shape, fetched off
// the loop (#1138); the hub's text is escaped, the panel being colour-tagged.
func TestHooksDescribe_showsTheHookDetail(t *testing.T) {
	var gets []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gets = append(gets, r.URL.Path)
		switch r.URL.Path {
		case "/api/platform/admin/hooks":
			_, _ = w.Write([]byte(`[{"id":"orders-normalise","type":"row","entity":"t_orders","scope":"ventes","status":"enabled"}]`))
		case "/api/platform/hooks/orders-normalise":
			_, _ = w.Write([]byte(`{"id":"orders-normalise","module":"core","description":"trim [red]codes",
				"events":["row.mapped"],"handler":{"scriptPath":"hooks/orders.kts"},"priority":10,"enabled":true}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	h := NewHooks(nil, client.New(config.Context{Server: srv.URL}), func(string) {})
	h.Refresh()
	h.table.Select(1, 0)
	title, body, ok := h.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok on a selected hook")
	}
	if !strings.Contains(title, "orders-normalise") {
		t.Errorf("title = %q, want the hook id", title)
	}
	for _, want := range []string{"hooks/orders.kts", "row.mapped", "priority", "trim [red[]codes"} {
		if !strings.Contains(body, want) {
			t.Errorf("describe body missing %q:\n%s", want, body)
		}
	}
	h.SelectedDescribe() // the panel follows the row: a second look is served from what was fetched
	if n := strings.Count(strings.Join(gets, " "), "/api/platform/hooks/orders-normalise"); n != 1 {
		t.Errorf("the detail was fetched %d times, want once per refresh", n)
	}
}

func TestHooksDescribe_emptyWithoutSelection(t *testing.T) {
	h := NewHooks(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), func(string) {})
	if _, _, ok := h.SelectedDescribe(); ok {
		t.Error("no rows -> describe must report !ok")
	}
}
