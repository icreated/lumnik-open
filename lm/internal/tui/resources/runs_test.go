package resources

import (
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

func TestStartedLabel(t *testing.T) {
	// A fixed "now" and a fixed zone so the test is deterministic.
	loc := time.FixedZone("CET", 2*3600)
	now := time.Date(2026, 7, 7, 14, 30, 0, 0, loc)

	cases := []struct {
		name string
		utc  time.Time
		want string
	}{
		{"today shows local time only", time.Date(2026, 7, 7, 12, 5, 9, 0, time.UTC), "14:05:09"},
		{"older shows date + local time", time.Date(2026, 7, 5, 8, 0, 0, 0, time.UTC), "07-05 10:00"},
	}
	for _, c := range cases {
		if got := startedLabel(c.utc, now); got != c.want {
			t.Errorf("%s: startedLabel = %q, want %q", c.name, got, c.want)
		}
	}
}

// The RECORDS column becomes the full in/out/skip triple — SKIPPED joins the CLI
// table here too, in the TUI's same compact idiom.
func TestRunsRender_recordsCellShowsInOutSkip(t *testing.T) {
	r := &Runs{table: tview.NewTable(),
		sort: style.NewColumnSort([]string{"CONNECTOR", "STATUS", "STARTED", "DURATION"})}
	r.items = []types.Run{
		{ID: "r1", RecordsIn: 10, RecordsOut: 7, RecordsSkipped: 3},
	}
	r.render()
	if got := r.table.GetCell(1, 4).Text; got != "10/7/3" {
		t.Errorf("RECORDS cell = %q, want %q", got, "10/7/3")
	}
}

// The describe panel adds records_skipped right after records_out — the full
// conservation triple must be readable there too.
func TestRunsDescribe_includesRecordsSkippedAfterRecordsOut(t *testing.T) {
	r := &Runs{table: tview.NewTable(),
		sort: style.NewColumnSort([]string{"CONNECTOR", "STATUS", "STARTED", "DURATION"})}
	r.items = []types.Run{
		{ID: "r1", RecordsIn: 10, RecordsOut: 7, RecordsSkipped: 3},
	}
	r.render()
	r.table.Select(1, 0)
	_, body, ok := r.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	outIdx := strings.Index(body, "records_out:")
	skipIdx := strings.Index(body, "records_skipped:")
	if outIdx == -1 || skipIdx == -1 || skipIdx < outIdx {
		t.Fatalf("expected records_skipped right after records_out, got body:\n%s", body)
	}
	if !strings.Contains(body, "records_skipped: 3\n") {
		t.Errorf("records_skipped value missing/wrong, got body:\n%s", body)
	}
}

// The describe panel adds cell_errors right after records_skipped — a cell hook nulling a
// value it cannot parse is invisible everywhere else, so this is the one door that shows it.
func TestRunsDescribe_includesCellErrorsAfterRecordsSkipped(t *testing.T) {
	r := &Runs{table: tview.NewTable(),
		sort: style.NewColumnSort([]string{"CONNECTOR", "STATUS", "STARTED", "DURATION"})}
	r.items = []types.Run{
		{ID: "r1", RecordsIn: 10, RecordsOut: 7, RecordsSkipped: 3, CellErrors: 2},
	}
	r.render()
	r.table.Select(1, 0)
	_, body, ok := r.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	skipIdx := strings.Index(body, "records_skipped:")
	cellIdx := strings.Index(body, "cell_errors:")
	if skipIdx == -1 || cellIdx == -1 || cellIdx < skipIdx {
		t.Fatalf("expected cell_errors right after records_skipped, got body:\n%s", body)
	}
	if !strings.Contains(body, "cell_errors:     2\n") {
		t.Errorf("cell_errors value/padding missing/wrong, got body:\n%s", body)
	}
}
