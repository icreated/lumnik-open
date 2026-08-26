package resources

import (
	"fmt"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

type Modules struct {
	table  *tview.Table
	client *client.Client
	mu     sync.Mutex
}

func NewModules(c *client.Client) *Modules {
	t := tview.NewTable().SetBorders(false).SetSelectable(true, false)
	t.SetTitle(" :modules ").SetBorder(true)
	p := &Modules{table: t, client: c}
	p.setHeader()
	return p
}

func (m *Modules) View() tview.Primitive { return m.table }

func (m *Modules) Hints() string {
	return "r: refresh   :: switch   ?: help   q: quit"
}

func (m *Modules) setHeader() {
	headers := []string{"NAME", "VERSION", "SCHEMA", "STATUS"}
	for i, h := range headers {
		cell := tview.NewTableCell(h).SetSelectable(false).SetExpansion(1).SetTextColor(tcell.ColorYellow)
		m.table.SetCell(0, i, cell)
	}
}

func (m *Modules) Refresh() {
	m.mu.Lock()
	defer m.mu.Unlock()

	modules, err := m.client.ModuleList()
	if err != nil {
		m.table.Clear()
		m.setHeader()
		m.table.SetCell(1, 0, dataCell(fmt.Sprintf("ERROR: %v", err)).SetExpansion(4))
		return
	}
	m.table.Clear()
	m.setHeader()
	if len(modules) == 0 {
		m.table.SetCell(1, 0, tview.NewTableCell(
			"No business modules installed — this hub is an integration substrate; use :sources and :new.").
			SetSelectable(false).SetExpansion(4))
		return
	}
	for i, mod := range modules {
		row := i + 1
		m.table.SetCell(row, 0, dataCell(mod.Name).SetExpansion(1))
		m.table.SetCell(row, 1, dataCell(mod.Version).SetExpansion(1))
		m.table.SetCell(row, 2, dataCell(mod.Schema).SetExpansion(1))
		m.table.SetCell(row, 3, dataCell(mod.Status).SetExpansion(1))
	}
	_ = types.Module{}
}
