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

// :tables lists the dynamic tables with the caller's row counts; Enter pages one table's raw
// rows, n/p move, Esc goes back (#1115).
func TestTables_listThenRowsThenBack(t *testing.T) {
	var paths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.RequestURI())
		if r.URL.Path == "/api/platform/data/tables" {
			_, _ = w.Write([]byte(`[{"mappingId":"erp-orders","tableSchema":"connector","tableName":"t_erp_orders","rowCount":150},
				{"mappingId":"gone","tableSchema":"connector","tableName":"t_gone","rowCount":null}]`))
			return
		}
		rows := make([]string, 0, 100)
		for i := 0; i < 100; i++ {
			rows = append(rows, `{"id":"`+string(rune('a'+i%26))+`","code":"X"}`)
		}
		_, _ = w.Write([]byte("[" + strings.Join(rows, ",") + "]"))
	}))
	defer srv.Close()

	p := NewTables(nil, client.New(config.Context{Server: srv.URL}), nil)
	p.Refresh()
	if got := p.table.GetCell(2, 2).Text; got != "unknown" {
		t.Errorf("an uncounted table shows %q, want unknown", got)
	}
	p.table.Select(1, 0)
	p.handleKey(tcell.NewEventKey(tcell.KeyEnter, 0, tcell.ModNone))
	p.handleKey(tcell.NewEventKey(tcell.KeyRune, 'n', tcell.ModNone))
	last := paths[len(paths)-1]
	if last != "/api/platform/data/tables/erp-orders/rows?limit=100&offset=100" || !p.FilterActive() {
		t.Fatalf("paths = %v", paths)
	}
	if !strings.Contains(p.table.GetTitle(), "t_erp_orders") {
		t.Errorf("title %q does not name the table", p.table.GetTitle())
	}
	p.ClearFilter()
	if p.FilterActive() || p.table.GetRowCount() != 3 {
		t.Error("Esc did not bring the list back")
	}
}
