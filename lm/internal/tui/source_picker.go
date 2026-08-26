package tui

import (
	"fmt"
	"strings"

	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

// PickTables opens a tview multi-select list and returns the user's selection.
// Space or Enter toggles; Enter on last item or Escape confirms and exits.
func PickTables(tables []client.JdbcTable) ([]client.JdbcTable, error) {
	selected := make(map[int]bool)
	app := tview.NewApplication()
	list := tview.NewList().ShowSecondaryText(false)

	var render func()
	render = func() {
		list.Clear()
		for i, t := range tables {
			mark := "[ ]"
			if selected[i] {
				mark = "[x]"
			}
			wm := "-"
			if t.SuggestedWatermark != nil {
				wm = *t.SuggestedWatermark
			}
			label := fmt.Sprintf("%s %-25s %10d rows  watermark: %s",
				mark, t.Schema+"."+t.Name, t.RowEstimate, wm)
			idx := i
			list.AddItem(label, "", 0, func() {
				selected[idx] = !selected[idx]
				render()
			})
		}
	}
	render()

	list.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
		switch event.Key() {
		case tcell.KeyESC, tcell.KeyEnter:
			app.Stop()
			return nil
		case tcell.KeyRune:
			if event.Rune() == ' ' {
				idx := list.GetCurrentItem()
				selected[idx] = !selected[idx]
				render()
				return nil
			}
		}
		return event
	})

	if err := app.SetRoot(list, true).Run(); err != nil {
		return nil, err
	}

	out := make([]client.JdbcTable, 0)
	for i, t := range tables {
		if selected[i] {
			out = append(out, t)
		}
	}
	return out, nil
}

// FormatSummary returns a comma-separated list of selected table names.
func FormatSummary(selected []client.JdbcTable) string {
	names := make([]string, 0, len(selected))
	for _, t := range selected {
		names = append(names, t.Name)
	}
	return strings.Join(names, ", ")
}
