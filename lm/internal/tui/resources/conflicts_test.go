package resources

import (
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

func sampleConflicts() []client.Conflict {
	return []client.Conflict{
		{Entity: "FusedCustomer", Key: "C-200", Columns: []string{"name"},
			Conflicts: map[string][]client.DetailSource{
				"name": {{Src: "t_erp", Value: client.StrPtr("Marie")}, {Src: "t_csv", Value: client.StrPtr("MARIE A.")}}}},
		{Entity: "Article", Key: "A-1", Columns: []string{"label", "price"},
			Conflicts: map[string][]client.DetailSource{
				"label": {{Src: "t_erp", Value: client.StrPtr("Vis")}, {Src: "t_csv", Value: client.StrPtr("VIS")}},
				"price": {{Src: "t_erp", Value: client.StrPtr("1")}, {Src: "t_sap", Value: client.StrPtr("2")}}}},
	}
}

func TestConflictsRenderShowsColumnsAndSources(t *testing.T) {
	c := &Conflicts{table: tview.NewTable(), sortCol: style.NewColumnSort([]string{"ENTITY", "KEY"})}
	c.items = sampleConflicts()
	c.render()
	if got := c.table.GetCell(1, 0).Text; got != "FusedCustomer" {
		t.Errorf("ENTITY = %q", got)
	}
	if got := c.table.GetCell(2, 2).Text; got != "label, price" {
		t.Errorf("COLUMNS = %q, want the conflicted column names", got)
	}
	if got := c.table.GetCell(2, 3).Text; got != "t_erp, t_csv, t_sap" {
		t.Errorf("SOURCES = %q, want the distinct sources in first-mention order", got)
	}
}

func TestConflictsSortByEntity(t *testing.T) {
	c := &Conflicts{table: tview.NewTable(), sortCol: style.NewColumnSort([]string{"ENTITY", "KEY"})}
	c.items = sampleConflicts()
	c.sortCol.Next() // first press activates column 0 ascending
	c.applySort()
	if c.items[0].Entity != "Article" {
		t.Errorf("after sorting by ENTITY the first row is %q", c.items[0].Entity)
	}
}

// The describe panel is the whole point of the page: it must name every source and
// every disagreeing value, through the SHARED renderer the :view detail also uses.
func TestConflictsDescribeRendersTheWholeLedger(t *testing.T) {
	c := &Conflicts{table: tview.NewTable(), sortCol: style.NewColumnSort([]string{"ENTITY", "KEY"})}
	c.items = sampleConflicts()
	c.render()
	c.table.Select(1, 0)
	title, body, ok := c.SelectedDescribe()
	if !ok {
		t.Fatal("SelectedDescribe returned !ok for a selected row")
	}
	if !strings.Contains(title, "C-200") {
		t.Errorf("title = %q, want the record key", title)
	}
	for _, want := range []string{"name", "t_erp", "Marie", "t_csv", "MARIE A."} {
		if !strings.Contains(body, want) {
			t.Errorf("describe body missing %q:\n%s", want, body)
		}
	}
}

func TestConflictsDescribeEmptyWithoutSelection(t *testing.T) {
	c := &Conflicts{table: tview.NewTable(), sortCol: style.NewColumnSort([]string{"ENTITY", "KEY"})}
	if _, _, ok := c.SelectedDescribe(); ok {
		t.Error("no rows -> describe must report !ok, not panic")
	}
}

// A descending sort must stay STABLE among ties. Negating the ascending result
// (`less = !less`) reports less(i,j) AND less(j,i) as true for equal keys, which
// breaks sort.SliceStable's strict-weak-ordering contract and silently reverses
// every tie. Two rows share an entity here, so a broken comparator flips them.
func TestConflictsDescendingSortKeepsTiesStable(t *testing.T) {
	c := &Conflicts{table: tview.NewTable(), sortCol: style.NewColumnSort([]string{"ENTITY", "KEY"})}
	c.items = []client.Conflict{
		{Entity: "Article", Key: "A-1"},
		{Entity: "Article", Key: "A-2"},
		{Entity: "FusedCustomer", Key: "C-200"},
	}
	c.sortCol.Next() // ENTITY ascending
	c.sortCol.Next() // ENTITY descending
	c.applySort()
	if c.items[0].Entity != "FusedCustomer" {
		t.Fatalf("descending by ENTITY put %q first", c.items[0].Entity)
	}
	if c.items[1].Key != "A-1" || c.items[2].Key != "A-2" {
		t.Errorf("ties must keep their relative order, got %q then %q", c.items[1].Key, c.items[2].Key)
	}
}

// The list lands off the loop now (#1171): a refresh that reorders the ledger keeps the active sort
// and the cursor on the record it was on.
func TestConflictsRefreshKeepsTheSortAndTheSelectedRecord(t *testing.T) {
	c := &Conflicts{table: tview.NewTable(), sortCol: style.NewColumnSort([]string{"ENTITY", "KEY"})}
	c.sortCol.Next() // ENTITY ascending
	c.renderList(sampleConflicts(), nil)
	c.table.Select(2, 0) // FusedCustomer C-200, below Article A-1
	c.renderList(append(sampleConflicts(), client.Conflict{Entity: "Bin", Key: "B-1"}), nil)
	if got := c.table.GetCell(1, 0).Text; got != "Article" {
		t.Errorf("first row = %q, want the ENTITY sort kept", got)
	}
	if it, ok := c.selected(); !ok || it.Key != "C-200" {
		t.Errorf("cursor on %+v, want it kept on C-200", it)
	}
}
