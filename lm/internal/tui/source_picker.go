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
			idx := i
			list.AddItem(PickerLabel(t, selected[i]), "", 0, func() {
				if !Selectable(tables[idx]) {
					return
				}
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
				if idx >= 0 && idx < len(tables) && Selectable(tables[idx]) {
					selected[idx] = !selected[idx]
					render()
				}
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

// Selectable reports whether a discovered table can be added: one with a column the source's dialect
// cannot read is shown, but never offered for selection (the hub would refuse it by name anyway).
func Selectable(t client.JdbcTable) bool {
	return len(t.UnsupportedColumns) == 0
}

// PickerLabel is the line the picker shows for a table: a selection mark and its figures, or — for a
// table that cannot be added — a [!] and the columns and types that make it so.
func PickerLabel(t client.JdbcTable, selected bool) string {
	name := t.Schema + "." + t.Name
	if !Selectable(t) {
		return fmt.Sprintf("[!] %-25s UNSUPPORTED: %s", name, strings.Join(t.UnsupportedColumns, "; "))
	}
	mark := "[ ]"
	if selected {
		mark = "[x]"
	}
	wm := "-"
	if t.SuggestedWatermark != nil {
		wm = *t.SuggestedWatermark
	}
	return fmt.Sprintf("%s %-25s %10d rows  watermark: %s", mark, name, t.RowEstimate, wm)
}

// FormatSummary returns a comma-separated list of selected table names.
func FormatSummary(selected []client.JdbcTable) string {
	names := make([]string, 0, len(selected))
	for _, t := range selected {
		names = append(names, t.Name)
	}
	return strings.Join(names, ", ")
}
