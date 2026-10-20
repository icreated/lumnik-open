package tui

import (
	"fmt"
	"strings"

	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

// newTablePicker is the JDBC table picker, one for both its hosts — lm source jdbc add
// --interactive (its own application) and lm ui's guided setup (#1117): Space toggles a table
// that can be added, Enter confirms, Esc leaves with confirmed false. Labels are escaped: the
// [ ]/[x]/[!] marks are text, not colour tags.
func newTablePicker(tables []client.JdbcTable, title string, done func(picked []client.JdbcTable, confirmed bool)) *tview.List {
	selected := map[int]bool{}
	list := tview.NewList().ShowSecondaryText(false)
	if title != "" {
		list.SetBorder(true).SetTitle(title)
	}
	for _, t := range tables {
		list.AddItem(tview.Escape(PickerLabel(t, false)), "", 0, nil)
	}
	finish := func(confirmed bool) {
		var out []client.JdbcTable
		for i, t := range tables {
			if selected[i] {
				out = append(out, t)
			}
		}
		done(out, confirmed)
	}
	list.SetInputCapture(func(ev *tcell.EventKey) *tcell.EventKey {
		switch {
		case ev.Key() == tcell.KeyEnter:
			finish(true)
			return nil
		case ev.Key() == tcell.KeyEscape:
			finish(false)
			return nil
		case ev.Rune() == ' ':
			if i := list.GetCurrentItem(); i >= 0 && i < len(tables) && Selectable(tables[i]) {
				selected[i] = !selected[i]
				list.SetItemText(i, tview.Escape(PickerLabel(tables[i], selected[i])), "")
			}
			return nil
		}
		return ev
	})
	return list
}

// PickTables opens the table picker as its own application and returns the selection. Esc keeps
// what was ticked, as Enter does — the CLI picker's behaviour since it shipped.
func PickTables(tables []client.JdbcTable) ([]client.JdbcTable, error) {
	app := tview.NewApplication()
	var out []client.JdbcTable
	list := newTablePicker(tables, "", func(picked []client.JdbcTable, _ bool) {
		out = picked
		app.Stop()
	})
	if err := app.SetRoot(list, true).Run(); err != nil {
		return nil, err
	}
	if out == nil {
		out = []client.JdbcTable{}
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
