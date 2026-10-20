package resources

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

// / narrows a table to the rows whose text holds the filter, case-insensitively; the actions
// then act on what is shown, and the filter survives the live refresh (#1120).
func TestTextFilter_narrowsAndSurvivesRefresh(t *testing.T) {
	s := NewSources(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), nil, nil)
	s.renderList(conns("erp-orders", "crm-contacts", "erp-invoices"), nil)
	s.SetTextFilter("ERP")
	if got := s.table.GetRowCount() - 1; got != 2 {
		t.Fatalf("filtered rows = %d, want 2", got)
	}
	s.table.Select(2, 0)
	if sel, _ := s.selectedItem(); sel.Name != "erp-invoices" {
		t.Fatalf("the action would hit %q, want the row shown (erp-invoices)", sel.Name)
	}
	s.renderList(conns("erp-orders", "crm-contacts", "erp-invoices", "erp-stock"), nil) // live tick
	if got := s.table.GetRowCount() - 1; got != 3 {
		t.Errorf("after a refresh rows = %d, want the filter kept (3)", got)
	}
	if s.TextFilter() != "ERP" {
		t.Errorf("TextFilter() = %q", s.TextFilter())
	}
	s.SetTextFilter("")
	if got := s.table.GetRowCount() - 1; got != 4 {
		t.Errorf("cleared rows = %d, want 4", got)
	}

	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns)}
	r.renderList([]types.Run{{ID: "r1", ConnectorName: "erp"}, {ID: "r2", ConnectorName: "crm"}}, nil)
	r.SetTextFilter("crm")
	if run, ok := r.selected(); r.table.GetRowCount()-1 != 1 || (ok && run.ID != "r2") {
		t.Errorf("runs filter: %d rows, selected %q", r.table.GetRowCount()-1, run.ID)
	}
}

// / matches what the row shows, not every field it carries: a dead letter's payload is not a
// column (and can be large), so a word only in it must not keep the row (#1124 review).
func TestTextFilter_matchesTheShownColumnsOnly(t *testing.T) {
	d := NewDLQ(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), nil)
	d.renderList([]types.DeadLetter{
		{ID: "dl1", SkipReason: "row_error", Payload: "needle"},
		{ID: "dl2", SkipReason: "needle_reason"},
	}, nil)
	d.SetTextFilter("needle")
	if got := d.table.GetRowCount() - 1; got != 1 {
		t.Fatalf("rows = %d, want only the one showing needle", got)
	}
}
