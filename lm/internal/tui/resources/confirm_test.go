package resources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
)

// Del asks first, naming what it deletes; only a yes reaches the hub (#1110).
func TestDel_asksBeforeDeleting(t *testing.T) {
	for _, answer := range []bool{false, true} {
		var deletes atomic.Int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodDelete && r.URL.RawQuery == "" { // a dry run deletes nothing (#1218)
				deletes.Add(1)
			}
			w.WriteHeader(http.StatusNoContent)
		}))
		c := client.New(config.Context{Server: srv.URL})

		var questions []string
		confirm := func(q, label string, yes func()) {
			questions = append(questions, q+" ["+label+"]")
			if answer {
				yes()
			}
		}
		s := NewSources(nil, c, nil, nil)
		s.SetConfirm(confirm)
		s.renderList(conns("erp-orders"), nil)
		s.table.Select(1, 0)
		s.handleKey(tcell.NewEventKey(tcell.KeyDelete, 0, tcell.ModNone))

		p := NewManifests(nil, ProcessesSource(c), nil, nil)
		p.SetConfirm(confirm)
		p.all = []ManifestRow{{Name: "gold-arrive"}}
		p.show()
		p.table.Select(1, 0)
		p.handleKey(tcell.NewEventKey(tcell.KeyDelete, 0, tcell.ModNone))
		srv.Close()

		if len(questions) != 2 || !strings.Contains(questions[0], `"erp-orders"`) || !strings.Contains(questions[1], `"gold-arrive"`) {
			t.Fatalf("questions = %q, want each naming its object", questions)
		}
		for _, q := range questions {
			if !strings.HasSuffix(q, "[Delete]") {
				t.Errorf("%q: a question that deletes is answered by Delete", q)
			}
		}
		want := int32(0)
		if answer {
			want = 2
		}
		if got := deletes.Load(); got != want {
			t.Errorf("answer %v: %d DELETE(s) reached the hub, want %d", answer, got, want)
		}
	}
}
