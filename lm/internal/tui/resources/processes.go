package resources

import (
	"fmt"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// processCells maps one declared process to its table row.
func processCells(p client.ProcessInfo) []string {
	state := "off"
	if p.Enabled {
		state = "on"
	}
	return []string{p.Name, p.Table, p.Emit, state}
}

// Processes lists the tenant's kind:Process rules. `e` re-opens the stored
// manifest through the :new authoring loop — one loop for create and edit.
type Processes struct {
	client   *client.Client
	table    *tview.Table
	items    []client.ProcessInfo
	onEdit   func(name string) // app-level: fetch manifest → :new EditExisting
	onError  func(string)
	onSelect func()
}

func NewProcesses(c *client.Client, onEdit func(string), onError func(string)) *Processes {
	p := &Processes{client: c, onEdit: onEdit, onError: onError,
		table: tview.NewTable().SetBorders(false).SetSelectable(true, false)}
	style.ApplyChrome(p.table.Box)
	style.ApplyTableSelection(p.table)
	p.table.SetInputCapture(p.handleKey)
	p.table.SetSelectionChangedFunc(func(row, col int) {
		if p.onSelect != nil {
			p.onSelect()
		}
	})
	return p
}

func (p *Processes) View() tview.Primitive { return p.table }
func (p *Processes) Hints() string {
	return "e: edit the manifest (validate → apply loop) · ^P: enable/disable · Del: delete · d: describe · y: yank · r: refresh"
}

func (p *Processes) Refresh() {
	items, err := p.client.ProcessList()
	if err != nil {
		p.items = nil
		p.table.Clear()
		p.table.SetCell(0, 0, dataCell("error: "+err.Error()))
		return
	}
	p.items = items
	p.render()
}

func (p *Processes) render() {
	p.table.Clear()
	for c, h := range []string{"NAME", "TABLE", "EMIT", "ENABLED"} {
		p.table.SetCell(0, c, tview.NewTableCell(h).SetSelectable(false).SetTextColor(tcell.ColorYellow))
	}
	for r, it := range p.items {
		for c, cell := range processCells(it) {
			p.table.SetCell(r+1, c, dataCell(cell))
		}
	}
}

func (p *Processes) selected() (client.ProcessInfo, bool) {
	row, _ := p.table.GetSelection()
	if row < 1 || row-1 >= len(p.items) {
		return client.ProcessInfo{}, false
	}
	return p.items[row-1], true
}

func (p *Processes) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	// Same two chords, same words, same no-confirmation rule as the Sources page — a declared
	// process was the one listed thing with an ENABLED column and no hand to reach it (#487).
	switch ev.Key() {
	case tcell.KeyCtrlP:
		p.toggleEnabled()
		return nil
	case tcell.KeyDelete:
		p.delete()
		return nil
	}
	if ev.Rune() == 'e' {
		if it, ok := p.selected(); ok && p.onEdit != nil {
			p.onEdit(it.Name)
		}
		return nil
	}
	return ev
}

func (p *Processes) toggleEnabled() {
	if it, ok := p.selected(); ok {
		if _, err := p.client.ProcessSetEnabled(it.Name, !it.Enabled); err != nil {
			p.signalError(fmt.Sprintf("enable/disable: %v", err))
			return
		}
		p.Refresh()
	}
}

func (p *Processes) delete() {
	if it, ok := p.selected(); ok {
		if err := p.client.ProcessDelete(it.Name); err != nil {
			p.signalError(fmt.Sprintf("delete: %v", err))
			return
		}
		p.Refresh()
	}
}

func (p *Processes) signalError(msg string) {
	if p.onError != nil {
		p.onError(msg)
	}
}

// Describable
func (p *Processes) SelectedDescribe() (string, string, bool) {
	if p.table == nil {
		return "", "", false
	}
	it, ok := p.selected()
	if !ok {
		return "", "", false
	}
	esc := tview.Escape
	body := fmt.Sprintf("name:    %s\ntable:   %s\nemit:    %s\nenabled: %v",
		esc(it.Name), esc(it.Table), esc(it.Emit), it.Enabled)
	return it.Name, body, true
}
func (p *Processes) OnSelectionChange(cb func()) { p.onSelect = cb }

// Yankable
func (p *Processes) SelectedYank() (string, bool) {
	if p.table == nil {
		return "", false
	}
	it, ok := p.selected()
	if !ok {
		return "", false
	}
	return it.Name, true
}
