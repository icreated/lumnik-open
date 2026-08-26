package resources

import (
	"fmt"
	"strings"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

type Endpoints struct {
	client      *client.Client
	table       *tview.Table
	endpoints   []types.Endpoint
	connectorID string
	onEnter     func(endpointID string)
	onSelect    func()
}

func NewEndpoints(c *client.Client) *Endpoints {
	t := tview.NewTable().SetBorders(false).SetSelectable(true, false)
	t.SetTitle(" :endpoints ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	p := &Endpoints{client: c, table: t}
	t.SetInputCapture(p.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if p.onSelect != nil {
			p.onSelect()
		}
	})
	return p
}

func (p *Endpoints) View() tview.Primitive { return p.table }

func (p *Endpoints) Hints() string {
	return "Enter: → runs · d: describe · y: yank · r: refresh · Esc: ← sources"
}

func (p *Endpoints) SetFilter(connectorID string) {
	p.connectorID = connectorID
	if connectorID != "" {
		short := connectorID
		if len(short) > 8 {
			short = short[:8]
		}
		p.table.SetTitle(fmt.Sprintf(" :endpoints [conn=%s] ", short))
	} else {
		p.table.SetTitle(" :endpoints ")
	}
}

func (p *Endpoints) SetOnEnter(cb func(string)) { p.onEnter = cb }

func (p *Endpoints) Refresh() {
	if p.connectorID == "" {
		p.table.Clear()
		p.renderHeader()
		return
	}
	eps, err := p.client.Endpoints(p.connectorID)
	if err != nil {
		p.table.Clear()
		p.renderHeader()
		p.table.SetCell(1, 0, dataCell(fmt.Sprintf("error: %v", err)))
		return
	}
	p.endpoints = eps
	p.render()
}

func (p *Endpoints) renderHeader() {
	headers := []string{"NAME", "METHOD", "PATH", "SCHEDULE", "LAST_RUN", "STATUS", "ENABLED"}
	for c, h := range headers {
		p.table.SetCell(0, c, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1).SetTextColor(tcell.ColorYellow))
	}
}

func (p *Endpoints) renderRows() [][]string {
	rows := make([][]string, 0, len(p.endpoints))
	for _, e := range p.endpoints {
		lastRun := ""
		if e.LastRunAt != nil {
			lastRun = e.LastRunAt.Format("2006-01-02 15:04:05")
		}
		enabled := "yes"
		if !e.Enabled {
			enabled = "no"
		}
		rows = append(rows, []string{e.Name, e.Method, e.Path, e.Schedule, lastRun, e.LastRunStatus, enabled})
	}
	return rows
}

func (p *Endpoints) render() {
	p.table.Clear()
	p.renderHeader()
	for r, row := range p.renderRows() {
		for c, v := range row {
			cell := dataCell(v).SetExpansion(1)
			if c == 5 && style.ColorsEnabled() { // STATUS column
				cell.SetTextColor(endpointStatusColor(v))
			}
			p.table.SetCell(r+1, c, cell)
		}
	}
	if p.table.GetRowCount() > 1 {
		p.table.Select(1, 0)
	}
}

func endpointStatusColor(status string) tcell.Color {
	switch runStatusKind(status) {
	case KindCompleted:
		return tcell.NewRGBColor(0x22, 0xC5, 0x5E) // green
	case KindFailed:
		return tcell.NewRGBColor(0xEF, 0x44, 0x44) // red
	case KindRunning:
		return tcell.NewRGBColor(0xFF, 0x8C, 0x00) // amber
	case KindCancelled:
		return tcell.NewRGBColor(0x6B, 0x72, 0x80) // gray — didn't finish
	}
	return tcell.ColorDefault
}

func (p *Endpoints) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	switch ev.Key() {
	case tcell.KeyEnter:
		if p.onEnter != nil {
			if ep, ok := p.selectedItem(); ok {
				p.onEnter(ep.ID)
			}
		}
		return nil
	}
	if ev.Rune() == 'r' {
		p.Refresh()
		return nil
	}
	return ev
}

func (p *Endpoints) selectedItem() (types.Endpoint, bool) {
	row, _ := p.table.GetSelection()
	if row < 1 || row-1 >= len(p.endpoints) {
		return types.Endpoint{}, false
	}
	return p.endpoints[row-1], true
}

// Describable

func (p *Endpoints) SelectedDescribe() (string, string, bool) {
	ep, ok := p.selectedItem()
	if !ok {
		return "", "", false
	}
	lastRun := ""
	if ep.LastRunAt != nil {
		lastRun = ep.LastRunAt.Format("2006-01-02 15:04:05")
	}
	enabled := "false"
	if ep.Enabled {
		enabled = "true"
	}
	esc := tview.Escape
	body := fmt.Sprintf("id:              %s\nconnector:       %s\nname:            %s\nmethod:          %s\npath:            %s\nschedule:        %s\nenabled:         %s\nlast_run_at:     %s\nlast_run_status: %s\n",
		esc(ep.ID), esc(ep.ConnectorID), esc(ep.Name), esc(ep.Method), esc(ep.Path),
		esc(ep.Schedule), enabled, lastRun, esc(ep.LastRunStatus))
	return "endpoint/" + ep.Name, body, true
}

func (p *Endpoints) OnSelectionChange(cb func()) { p.onSelect = cb }

// Yankable

func (p *Endpoints) SelectedYank() (string, bool) {
	ep, ok := p.selectedItem()
	if !ok {
		return "", false
	}
	return strings.TrimSpace(fmt.Sprintf("- name: %s\n  method: %s\n  path: %s\n  schedule: %s\n", ep.Name, ep.Method, ep.Path, ep.Schedule)), true
}
