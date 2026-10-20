package resources

import (
	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"
)

// newListTable is the table of every list page: row 0 is its header, kept on screen, and rows
// below it are selected whole (#1172).
//
// Its viewport follows the cursor, never the end: tview v0.42's Table turns on "track the end" in
// any Draw where its rows fit — every page, drawn empty before its list lands — and keeps it on, so
// the long list then drew from the bottom. The hook clears it (SetOffset does) before each Draw;
// re-check it on a tview bump.
func newListTable() *tview.Table {
	t := tview.NewTable().SetBorders(false).SetSelectable(true, false).SetFixed(1, 0)
	t.SetDrawFunc(func(_ tcell.Screen, _, _, _, _ int) (int, int, int, int) {
		t.SetOffset(t.GetOffset())
		return t.GetInnerRect() // the box's own: its layout set the rect just before this Draw
	})
	return t
}

// drillTitle is a drill-down page's title: what it is narrowed to, by name — or by the id's first
// 8 characters when no name is known.
func drillTitle(page, what, id, name string) string {
	if id == "" {
		return " " + page + " "
	}
	if name == "" {
		name = shortRunID(id)
	}
	if what != "" {
		name = what + " " + name
	}
	return " " + page + " › " + tview.Escape(name) + " "
}
