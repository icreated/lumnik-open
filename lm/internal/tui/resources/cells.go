package resources

import (
	"strings"

	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"
)

// dataCell builds a table cell whose text derives from server or user data.
// Table cells always parse color tags, so the text is DATA, not markup —
// escaped here once. The house rule: text that carries (even partly)
// server- or user-derived strings goes through dataCell; compile-time
// literals and locally-formatted values that provably contain no brackets
// (numbers, glyphs, local date formats) may use tview.NewTableCell directly.
func dataCell(text string) *tview.TableCell {
	return tview.NewTableCell(tview.Escape(text))
}

// headerRow clears t and writes cols as its header row. Column names can be the hub's — a source's
// own headers — so they are data too: escaped here, once, for every page that draws them.
func headerRow(t *tview.Table, cols []string) {
	t.Clear()
	for c, h := range cols {
		t.SetCell(0, c, tview.NewTableCell(tview.Escape(strings.ToUpper(h))).SetSelectable(false).SetExpansion(1).SetTextColor(tcell.ColorYellow))
	}
}
