package resources

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// Conflicts is the aggregated fusion ledger: every fused-entity row with a source conflict
// or a lookup ambiguity, across every entity the caller may read. Read-only — resolving a conflict is
// the integrator's job upstream, in the manifest, never a keystroke here.
type Conflicts struct {
	table    *tview.Table
	client   *client.Client
	items    []client.Conflict
	mu       sync.Mutex
	onError  func(string)
	sortCol  *style.ColumnSort
	onSelect func()
}

func NewConflicts(c *client.Client, onError func(string)) *Conflicts {
	t := tview.NewTable().SetBorders(false).SetSelectable(true, false)
	t.SetTitle(" :conflicts ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	cf := &Conflicts{
		table:   t,
		client:  c,
		onError: onError,
		sortCol: style.NewColumnSort([]string{"ENTITY", "KEY"}),
	}
	cf.setHeader()
	t.SetInputCapture(cf.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if cf.onSelect != nil {
			cf.onSelect()
		}
	})
	return cf
}

func (c *Conflicts) View() tview.Primitive { return c.table }

func (c *Conflicts) Hints() string {
	return "d: describe  y: yank  s: sort  r: refresh"
}

func (c *Conflicts) setHeader() {
	headers := []string{c.sortCol.HeaderLabel(0), c.sortCol.HeaderLabel(1), "COLUMNS", "SOURCES"}
	for i, h := range headers {
		c.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1))
	}
}

func (c *Conflicts) Refresh() {
	c.mu.Lock()
	defer c.mu.Unlock()
	list, err := c.client.ConflictList(200)
	if err != nil {
		if c.onError != nil {
			c.onError(fmt.Sprintf("conflicts: %v", err))
		}
		return
	}
	c.items = list
	c.applySort()
	c.render()
}

// The server already orders by entity then key; this only re-orders on demand.
func (c *Conflicts) applySort() {
	idx, desc, sorted := c.sortCol.Active()
	if !sorted {
		return
	}
	sort.SliceStable(c.items, func(i, j int) bool {
		// Descending swaps the OPERANDS, never negates the result: negating would
		// report less(i,j) and less(j,i) both true for equal keys, breaking
		// SliceStable's strict-weak-ordering contract and reversing every tie.
		a, b := c.items[i], c.items[j]
		if desc {
			a, b = b, a
		}
		switch idx {
		case 0:
			return a.Entity < b.Entity
		case 1:
			return a.Key < b.Key
		}
		return false
	})
}

func (c *Conflicts) render() {
	c.table.Clear()
	c.setHeader()
	for i, it := range c.items {
		row := i + 1
		c.table.SetCell(row, 0, dataCell(it.Entity).SetExpansion(1))
		c.table.SetCell(row, 1, dataCell(it.Key).SetExpansion(1))
		c.table.SetCell(row, 2, dataCell(strings.Join(it.Columns, ", ")).SetExpansion(1))
		c.table.SetCell(row, 3, dataCell(strings.Join(it.SourceNames(), ", ")).SetExpansion(2))
	}
}

func (c *Conflicts) selected() (client.Conflict, bool) {
	row, _ := c.table.GetSelection()
	if row < 1 || row-1 >= len(c.items) {
		return client.Conflict{}, false
	}
	return c.items[row-1], true
}

func (c *Conflicts) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	switch ev.Rune() {
	case 's':
		c.sortCol.Next()
		c.Refresh()
		return nil
	case 'r':
		c.Refresh()
		return nil
	}
	return ev
}

// Describable

func (c *Conflicts) SelectedDescribe() (string, string, bool) {
	it, ok := c.selected()
	if !ok {
		return "", "", false
	}
	var b strings.Builder
	fmt.Fprintf(&b, "[yellow]%s — %s[white]\n\n", tview.Escape(it.Entity), tview.Escape(it.Key))
	ledgerLines(&b, it.Ledger())
	return it.Entity + " " + it.Key, b.String(), true
}

func (c *Conflicts) OnSelectionChange(cb func()) { c.onSelect = cb }

// Yankable

func (c *Conflicts) SelectedYank() (string, bool) {
	it, ok := c.selected()
	if !ok {
		return "", false
	}
	raw, err := json.MarshalIndent(it, "", "  ")
	if err != nil {
		return "", false
	}
	return string(raw), true
}
