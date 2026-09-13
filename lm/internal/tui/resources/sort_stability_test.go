package resources

import (
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

// Negating the ascending boolean result (instead of swapping operands) reports
// less(i,j) AND less(j,i) as true for equal keys, which breaks sort.SliceStable's
// strict-weak-ordering contract and silently REVERSES every tie. Each page below
// sorts on a column where ties are the normal case (one connector has many runs,
// many dead letters).

func TestSourcesDescendingSortKeepsTiesStable(t *testing.T) {
	c := &Sources{table: tview.NewTable(),
		sortCol: style.NewColumnSort([]string{"NAME", "TYPE", "SCHED", "ENABLED"})}
	c.items = []types.Connector{
		{Name: "a", TypeID: "jdbc"}, {Name: "b", TypeID: "jdbc"}, {Name: "c", TypeID: "csv"},
	}
	c.sortCol.Next() // NAME asc
	c.sortCol.Next() // NAME desc
	c.sortCol.Next() // TYPE asc
	c.sortCol.Next() // TYPE desc
	c.applySort()
	if c.items[0].TypeID != "jdbc" {
		t.Fatalf("descending by TYPE put %q first", c.items[0].TypeID)
	}
	if c.items[0].Name != "a" || c.items[1].Name != "b" {
		t.Errorf("ties must keep their order, got %q then %q", c.items[0].Name, c.items[1].Name)
	}
}

// ENABLED (case 3) is the one comparator that isn't a plain '<' — it's the boolean
// "a.Enabled && !b.Enabled" — so it needs its own tie test: nothing above exercises it.
func TestSourcesEnabledDescendingSortKeepsTiesStable(t *testing.T) {
	c := &Sources{table: tview.NewTable(),
		sortCol: style.NewColumnSort([]string{"NAME", "TYPE", "SCHED", "ENABLED"})}
	c.items = []types.Connector{
		{Name: "x", Enabled: true}, {Name: "y", Enabled: true}, {Name: "z", Enabled: false},
	}
	c.sortCol.Next() // NAME asc
	c.sortCol.Next() // NAME desc
	c.sortCol.Next() // TYPE asc
	c.sortCol.Next() // TYPE desc
	c.sortCol.Next() // SCHED asc
	c.sortCol.Next() // SCHED desc
	c.sortCol.Next() // ENABLED asc
	c.sortCol.Next() // ENABLED desc
	c.applySort()
	if c.items[0].Enabled {
		t.Fatalf("descending by ENABLED put an enabled connector first: %q", c.items[0].Name)
	}
	if c.items[1].Name != "x" || c.items[2].Name != "y" {
		t.Errorf("ties must keep their order, got %q then %q", c.items[1].Name, c.items[2].Name)
	}
}

func TestRunsDescendingSortKeepsTiesStable(t *testing.T) {
	r := &Runs{table: tview.NewTable(),
		sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{
		{ID: "r1", Status: "Completed"}, {ID: "r2", Status: "Completed"}, {ID: "r3", Status: "Failed"},
	}
	r.sort.Next() // CONNECTOR asc
	r.sort.Next() // CONNECTOR desc
	r.sort.Next() // ENDPOINT asc
	r.sort.Next() // ENDPOINT desc
	r.sort.Next() // STATUS asc
	r.sort.Next() // STATUS desc
	r.applySort()
	if r.items[0].Status != "Failed" {
		t.Fatalf("descending by STATUS put %q first", r.items[0].Status)
	}
	if r.items[1].ID != "r1" || r.items[2].ID != "r2" {
		t.Errorf("ties must keep their order, got %q then %q", r.items[1].ID, r.items[2].ID)
	}
}

func TestDlqDescendingSortKeepsTiesStable(t *testing.T) {
	d := &DLQ{table: tview.NewTable(),
		sortCol: style.NewColumnSort([]string{"CONNECTOR", "CAPTURED", "REASON"})}
	now := time.Now()
	d.items = []types.DeadLetter{
		{ID: "d1", ConnectorID: "c-1", CapturedAt: now}, {ID: "d2", ConnectorID: "c-1", CapturedAt: now},
		{ID: "d3", ConnectorID: "c-2", CapturedAt: now},
	}
	d.sortCol.Next() // CONNECTOR asc
	d.sortCol.Next() // CONNECTOR desc
	d.applySort()
	if d.items[0].ConnectorID != "c-2" {
		t.Fatalf("descending by CONNECTOR put %q first", d.items[0].ConnectorID)
	}
	if d.items[1].ID != "d1" || d.items[2].ID != "d2" {
		t.Errorf("ties must keep their order, got %q then %q", d.items[1].ID, d.items[2].ID)
	}
}
