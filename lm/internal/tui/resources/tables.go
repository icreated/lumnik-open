package resources

import (
	"fmt"
	"strings"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// tablePage is the rows page size; the hub caps a page at 1000.
const tablePage = 100

// Tables is :tables — the dynamic t_* tables the connectors landed for the caller, with its row
// counts, and on Enter one table's raw rows in landing order (#1115): what landed between "the run
// is green" and "an Entity fuses it". Read-only; every fetch runs off the event loop.
type Tables struct {
	app      *tview.Application
	client   *client.Client
	table    *tview.Table
	mu       sync.Mutex
	offLoop  offLoop
	onError  func(string)
	onSelect func()

	list    []client.DataTable
	mapping string // "" = the list; otherwise the table whose rows are shown
	offset  int
	rows    []client.EntityRow
}

func NewTables(app *tview.Application, c *client.Client, onError func(string)) *Tables {
	t := newListTable()
	t.SetTitle(" :tables ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	p := &Tables{app: app, client: c, table: t, onError: onError}
	t.SetInputCapture(p.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if p.onSelect != nil {
			p.onSelect()
		}
	})
	return p
}

func (p *Tables) View() tview.Primitive { return p.table }

func (p *Tables) Hints() string {
	if p.mapping == "" {
		return "Enter: rows  d: describe  r: refresh"
	}
	return "Esc: ← tables  n/p: next/prev page  d: the whole row  r: refresh"
}

// FilterActive / ClearFilter make the rows view a drill-down the App's Esc unwinds (Drillable).
func (p *Tables) FilterActive() bool { return p.mapping != "" }

func (p *Tables) ClearFilter() {
	p.setView("", 0)
	p.rows = nil
	p.table.SetTitle(" :tables ")
	p.renderList()
	p.Refresh()
}

// setView picks what Refresh reads, under the lock the fetch goroutine reads it with.
func (p *Tables) setView(mapping string, offset int) {
	p.mu.Lock()
	p.mapping, p.offset = mapping, offset
	p.mu.Unlock()
}

func (p *Tables) Refresh() {
	p.offLoop.run(p.app, func() func() {
		p.mu.Lock()
		mapping, offset := p.mapping, p.offset
		p.mu.Unlock()
		if mapping == "" {
			list, err := p.client.DataTables()
			return func() {
				if err != nil {
					say(p.onError, fmt.Sprintf("tables: %v", err))
					return
				}
				p.list = list
				p.renderList()
			}
		}
		rows, err := p.client.DataTableRows(mapping, tablePage, offset)
		return func() {
			if err != nil {
				say(p.onError, fmt.Sprintf("%s: %v", mapping, err))
				return
			}
			if p.mapping != mapping {
				return // Esc landed meanwhile
			}
			p.rows = rows
			p.renderRows()
		}
	})
}

func tableKey(t client.DataTable) string { return t.MappingID }

func (p *Tables) renderList() {
	prev := selectedKey(p.table, p.list, tableKey)
	headerRow(p.table, []string{"table", "mapping", "rows"})
	if len(p.list) == 0 {
		p.table.SetCell(1, 0, tview.NewTableCell("Nothing landed yet — run a connector from :sources.").SetSelectable(false))
		return
	}
	for r, t := range p.list {
		for c, v := range []string{t.TableSchema + "." + t.TableName, t.MappingID, t.Rows()} {
			p.table.SetCell(r+1, c, dataCell(v).SetExpansion(1))
		}
	}
	reselect(p.table, p.list, tableKey, prev)
}

func (p *Tables) tableName() string {
	for _, t := range p.list {
		if t.MappingID == p.mapping {
			return t.TableName
		}
	}
	return p.mapping
}

func (p *Tables) renderRows() {
	var cols []string
	if len(p.rows) > 0 {
		cols = p.rows[0].Columns()
	}
	headerRow(p.table, cols)
	title := fmt.Sprintf(" :tables [%s] rows %d–%d ", p.tableName(), p.offset+1, p.offset+len(p.rows))
	if len(p.rows) == 0 {
		title = fmt.Sprintf(" :tables [%s] past the last row (p: back) ", p.tableName())
		p.table.SetCell(1, 0, tview.NewTableCell("No rows on this page.").SetSelectable(false))
	}
	p.table.SetTitle(tview.Escape(title))
	for r, row := range p.rows {
		for c, v := range cellsOf(row, cols) {
			p.table.SetCell(r+1, c, dataCell(v).SetExpansion(1))
		}
	}
	if len(p.rows) > 0 {
		p.table.Select(1, 0)
	}
}

func (p *Tables) selectedTable() (client.DataTable, bool) {
	row, _ := p.table.GetSelection()
	if p.mapping != "" || row < 1 || row-1 >= len(p.list) {
		return client.DataTable{}, false
	}
	return p.list[row-1], true
}

func (p *Tables) selectedRow() (client.EntityRow, bool) {
	row, _ := p.table.GetSelection()
	if p.mapping == "" || row < 1 || row-1 >= len(p.rows) {
		return client.EntityRow{}, false
	}
	return p.rows[row-1], true
}

func (p *Tables) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Rune() == 'r' {
		p.Refresh()
		return nil
	}
	if p.mapping == "" {
		if t, ok := p.selectedTable(); ok && ev.Key() == tcell.KeyEnter {
			p.setView(t.MappingID, 0)
			p.Refresh()
			return nil
		}
		return ev
	}
	switch ev.Rune() {
	case 'n':
		if len(p.rows) == tablePage {
			p.setView(p.mapping, p.offset+tablePage)
			p.Refresh()
		}
		return nil
	case 'p':
		if p.offset > 0 {
			p.setView(p.mapping, max(0, p.offset-tablePage))
			p.Refresh()
		}
		return nil
	}
	return ev
}

// Describable — the table's registration, or the whole row.

func (p *Tables) SelectedDescribe() (string, string, bool) {
	esc := tview.Escape
	var b strings.Builder
	if t, ok := p.selectedTable(); ok {
		fmt.Fprintf(&b, "table:   %s.%s\n", esc(t.TableSchema), esc(t.TableName))
		fmt.Fprintf(&b, "mapping: %s\n", esc(t.MappingID))
		fmt.Fprintf(&b, "rows:    %s (yours)\n", t.Rows())
		return t.TableName, b.String(), true
	}
	r, ok := p.selectedRow()
	if !ok {
		return "", "", false
	}
	for _, c := range r.Columns() {
		fmt.Fprintf(&b, "%-14s %s\n", esc(c)+":", esc(r.Cell(c)))
	}
	return p.tableName(), b.String(), true
}

func (p *Tables) OnSelectionChange(cb func()) { p.onSelect = cb }
