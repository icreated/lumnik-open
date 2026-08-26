package resources

import (
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/rivo/tview"
)

func tviewTableWithOneRow() *tview.Table {
	tb := tview.NewTable().SetSelectable(true, false)
	tb.SetCell(0, 0, tview.NewTableCell("CODE"))
	tb.SetCell(1, 0, tview.NewTableCell("x"))
	tb.Select(1, 0)
	return tb
}

func TestDetailBody(t *testing.T) {
	d := client.ViewDetailResult{
		Title: "Clients", Key: "C-200",
		Fields: []client.DetailField{
			{Name: "customer_code", Label: "Code", Value: "C-200"},
			{Name: "name", Label: "Name", Value: "Marie"},
		},
		ConflictLedger: true,
		Conflicts: []client.DetailConflict{
			{Column: "name", Sources: []client.DetailSource{
				{Src: "cust_siebel", Value: "Marie"}, {Src: "t_cust_csv", Value: "MARIE A."}}},
		},
	}
	out := detailBody(d)
	for _, want := range []string{"C-200", "Marie", "name", "cust_siebel", "MARIE A."} {
		if !strings.Contains(out, want) {
			t.Errorf("detailBody missing %q:\n%s", want, out)
		}
	}
	d.Conflicts = nil
	if out := detailBody(d); !strings.Contains(out, "no conflicts") {
		t.Errorf("clean ledger must say so:\n%s", out)
	}
}

// Server data containing tview color tags must render literally, not as markup.
func TestDetailBodyEscapesColorTags(t *testing.T) {
	d := client.ViewDetailResult{Title: "T", Key: "K",
		Fields: []client.DetailField{{Name: "n", Label: "N", Value: "[red]evil"}}}
	out := detailBody(d)
	if strings.Contains(out, "[red]evil") {
		t.Errorf("unescaped color tag in output:\n%s", out)
	}
	if !strings.Contains(out, tview.Escape("[red]evil")) {
		t.Errorf("escaped value missing:\n%s", out)
	}
}

func TestPushRecent(t *testing.T) {
	r := pushRecent(nil, "a.yaml", 3)
	r = pushRecent(r, "b.yaml", 3)
	r = pushRecent(r, "a.yaml", 3) // dedup to front
	if len(r) != 2 || r[0] != "a.yaml" || r[1] != "b.yaml" {
		t.Fatalf("dedup-front failed: %v", r)
	}
	r = pushRecent(r, "c.yaml", 3)
	r = pushRecent(r, "d.yaml", 3) // cap
	if len(r) != 3 || r[0] != "d.yaml" {
		t.Fatalf("cap failed: %v", r)
	}
}

func TestViewPageEmptyDescribe(t *testing.T) {
	if _, _, ok := (&ViewPage{}).SelectedDescribe(); ok {
		t.Error("no selection on an uninitialized page")
	}
}

// A selected-but-empty server row must not panic describe/yank (review round 1).
func TestViewPageEmptyRowDescribe(t *testing.T) {
	v := &ViewPage{list: tviewTableWithOneRow(), result: &client.ViewResult{
		Columns: []client.ViewColumn{{Name: "code", Label: "Code"}},
		Rows:    [][]string{{}}, // server sent a row with zero cells
	}}
	if _, _, ok := v.SelectedDescribe(); ok {
		t.Error("empty row must describe as no-selection, not panic")
	}
	if _, ok := v.SelectedYank(); ok {
		t.Error("empty row must yank as no-selection")
	}
}
