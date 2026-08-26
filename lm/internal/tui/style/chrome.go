// Package style holds the look the TUI wears: lumière chrome and the column-sort
// state machine. It is a leaf (brand, tcell, tview only) so both tui and its
// tui/resources child can import it — resources cannot import its parent, which is
// why these helpers used to live in two drifting copies.
package style

import (
	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/brand"
	"github.com/rivo/tview"
)

// Chrome lumière — borders, titles, focus, selected row. ChromePrimary is
// brand.GoldHex spelt as a tcell colour; a test pins the two together.
var ChromePrimary = tcell.NewRGBColor(0xFF, 0xB0, 0x00) // gold lumière

// ColorsEnabled honors the NO_COLOR convention (https://no-color.org). The answer lives in
// brand, which the CLI can reach too — the reader turning colour off means the whole binary.
func ColorsEnabled() bool {
	return brand.ColorsEnabled()
}

// ApplyChrome dresses a *tview.Box (table, page, modal) with lumière accents.
func ApplyChrome(b *tview.Box) {
	if !ColorsEnabled() {
		return
	}
	b.SetBorderColor(ChromePrimary)
	b.SetTitleColor(ChromePrimary)
}

// ApplyTableSelection paints the selected row with brand gold background.
func ApplyTableSelection(t *tview.Table) {
	if !ColorsEnabled() {
		return
	}
	t.SetSelectedStyle(tcell.StyleDefault.
		Background(ChromePrimary).
		Foreground(tcell.ColorBlack))
}
