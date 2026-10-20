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
	app     *tview.Application
	table   *tview.Table
	client  *client.Client
	offLoop offLoop
	mu      sync.Mutex
}

func NewModules(app *tview.Application, c *client.Client) *Modules {
	t := newListTable()
	t.SetTitle(" :modules ").SetBorder(true)
	p := &Modules{app: app, table: t, client: c}
	p.setHeader()
	return p
}

func (m *Modules) View() tview.Primitive { return m.table }

func (m *Modules) Hints() string {
	return "r: refresh   :: switch   ?: help   q: quit"
}

func (m *Modules) setHeader() {
	headers := []string{"NAME", "VERSION", "SCHEMA", "STATUS", "REASON"}
	for i, h := range headers {
		cell := tview.NewTableCell(h).SetSelectable(false).SetExpansion(1).SetTextColor(tcell.ColorYellow)
		m.table.SetCell(0, i, cell)
	}
}

// Refresh lists off the event loop (offLoop): the live ticker calls it while the page is in front
// (#891).
func (m *Modules) Refresh() {
	m.offLoop.run(m.app, func() func() {
		modules, err := m.client.ModuleList()
		return func() { m.renderList(modules, err) }
	})
}

func (m *Modules) renderList(modules []types.Module, err error) {
	m.mu.Lock()
	defer m.mu.Unlock()

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
		m.table.SetCell(row, 4, dataCell(mod.Reason).SetExpansion(1))
	}
}
