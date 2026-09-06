package resources

import (
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

// The describe panel's "captured:" line renders local, second precision — not the raw
// RFC3339 the hub sends. Expected value computed, not hardcoded, so this does not depend on
// the machine running it being on any particular timezone.
func TestDLQDescribe_capturedIsLocal(t *testing.T) {
	captured, err := time.Parse(time.RFC3339, "2026-08-15T09:12:04Z")
	if err != nil {
		t.Fatalf("the sample timestamp must parse: %v", err)
	}
	d := &DLQ{table: tview.NewTable(), sortCol: style.NewColumnSort([]string{"CONNECTOR", "CAPTURED", "REASON"}),
		items: []types.DeadLetter{
			{ID: "dl1", ConnectorID: "c1", RunID: "r1", SkipReason: "row_error", CapturedAt: captured},
		}}
	d.render()
	d.table.Select(1, 0)
	_, body, ok := d.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	want := "captured:    " + captured.In(time.Local).Format("2006-01-02 15:04:05")
	if !strings.Contains(body, want) {
		t.Errorf("describe body missing %q:\n%s", want, body)
	}
	if strings.Contains(body, "2026-08-15T09:12:04Z") {
		t.Errorf("describe body should render local-formatted, not the raw ISO string:\n%s", body)
	}
}

// The TUI's CONNECTOR cell reads like the runs panel (the name), and describe carries name + id.
func TestDLQ_connectorNameInCellAndDescribe(t *testing.T) {
	d := &DLQ{table: tview.NewTable(), sortCol: style.NewColumnSort([]string{"CONNECTOR", "CAPTURED", "REASON"}),
		items: []types.DeadLetter{
			{ID: "dl1", ConnectorID: "11111111-2222-3333-4444-555555555555", ConnectorName: "orders-csv", RunID: "r1", SkipReason: "row_error"},
		}}
	d.render()
	if got := d.table.GetCell(1, 1).Text; got != "orders-csv" {
		t.Errorf("named connector cell = %q, want the name", got)
	}
	d.table.Select(1, 0)
	_, body, ok := d.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	for _, want := range []string{"orders-csv", "11111111-2222-3333-4444-555555555555"} {
		if !strings.Contains(body, want) {
			t.Errorf("describe must carry %q (name for the reader, id for the script):\n%s", want, body)
		}
	}
}
