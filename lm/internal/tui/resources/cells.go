package resources

import "github.com/rivo/tview"

// dataCell builds a table cell whose text derives from server or user data.
// Table cells always parse color tags, so the text is DATA, not markup —
// escaped here once. The house rule: text that carries (even partly)
// server- or user-derived strings goes through dataCell; compile-time
// literals and locally-formatted values that provably contain no brackets
// (numbers, glyphs, local date formats) may use tview.NewTableCell directly.
func dataCell(text string) *tview.TableCell {
	return tview.NewTableCell(tview.Escape(text))
}
