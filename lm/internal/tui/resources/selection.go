package resources

import "github.com/rivo/tview"

// selectedKey is the key of the object under the cursor of a table whose row r+1 shows items[r],
// "" when the cursor is on no item.
func selectedKey[T any](t *tview.Table, items []T, key func(T) string) string {
	row, _ := t.GetSelection()
	if row < 1 || row-1 >= len(items) {
		return ""
	}
	return key(items[row-1])
}

// reselect puts the cursor back on the row showing the object keyed prev, after a re-render.
// tview's Table.Clear keeps the selected row NUMBER, so without this a refresh that reorders,
// inserts or removes rows left the cursor on another object — the one Del, R or c then hit
// (#1107). It reports whether the object is still listed; when it is gone the cursor stays where
// tview clamps it.
// relist lands a fresh list on a page: items takes list, render redraws the table (sorting first
// if the page sorts), and the cursor stays on the object it was on. An open describe panel hears
// of the new row once: through reselect's Select when the object is still listed, else here.
func relist[T any](t *tview.Table, items *[]T, list []T, key func(T) string, render func(), onSelect func()) {
	prev := selectedKey(t, *items, key)
	*items = list
	render()
	if !reselect(t, *items, key, prev) && onSelect != nil {
		onSelect()
	}
}

func reselect[T any](t *tview.Table, items []T, key func(T) string, prev string) bool {
	if prev == "" {
		return false
	}
	for i, it := range items {
		if key(it) == prev {
			t.Select(i+1, 0)
			return true
		}
	}
	return false
}
