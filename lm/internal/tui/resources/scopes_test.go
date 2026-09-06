package resources

import (
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/rivo/tview"
)

func TestScopesRenderAndSelect(t *testing.T) {
	s := &Scopes{
		items: []client.RagScope{
			{Scope: "compta", Connectors: 3, Chunks: 12431, LastIndexed: "2026-05-24T09:12:00Z"},
			{Scope: "paie", Connectors: 1, Chunks: 2108, LastIndexed: "2026-05-24T08:55:00Z"},
		},
	}
	_, _, ok := s.SelectedDescribe()
	if ok {
		t.Errorf("expected no selection before init")
	}
}

// LAST INDEXED renders through output.HumanTimeStr in BOTH the table cell and the describe
// panel — the panel used to print sc.LastIndexed raw, having simply never called formatTime
// at all, while the table one row up rendered it nicely: two views of the same field,
// disagreeing. Expected value computed, not hardcoded, so this does not depend on the
// machine running it being on any particular timezone. output.HumanTimeStr's own parse/
// fallback/empty behavior is covered in internal/output/humantime_test.go — this test only
// pins that scopes.go actually calls it, at both sites.
func TestScopesRenderAndDescribe_lastIndexedIsLocalInBothPlaces(t *testing.T) {
	ts, err := time.Parse(time.RFC3339, "2026-05-24T09:12:00Z")
	if err != nil {
		t.Fatalf("the sample timestamp must parse: %v", err)
	}
	want := ts.In(time.Local).Format("2006-01-02 15:04")

	s := NewScopes(nil)
	s.items = []client.RagScope{{Scope: "compta", Connectors: 3, Chunks: 12431, LastIndexed: "2026-05-24T09:12:00Z"}}
	s.render()

	if got := s.table.GetCell(1, 3).Text; got != want {
		t.Errorf("table LAST INDEXED cell = %q, want %q", got, want)
	}

	s.table.Select(1, 0)
	_, body, ok := s.SelectedDescribe()
	if !ok {
		t.Fatal("expected a selection")
	}
	if wantLine := "last:       " + tview.Escape(want); !strings.Contains(body, wantLine) {
		t.Errorf("describe body missing %q:\n%s", wantLine, body)
	}
	if strings.Contains(body, "2026-05-24T09:12:00Z") {
		t.Errorf("describe body should render local-formatted, not the raw ISO string:\n%s", body)
	}
}
