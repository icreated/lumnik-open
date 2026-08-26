package resources

import (
	"fmt"
	"sync"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

type Migrations struct {
	table  *tview.Table
	client *client.Client
	mu     sync.Mutex
}

func NewMigrations(c *client.Client) *Migrations {
	t := tview.NewTable().SetBorders(false).SetSelectable(true, false)
	t.SetTitle(" :migrations ").SetBorder(true)
	m := &Migrations{table: t, client: c}
	m.setHeader()
	return m
}

func (m *Migrations) View() tview.Primitive { return m.table }
func (m *Migrations) Hints() string {
	return "r: refresh   :: switch   ?: help   q: quit"
}

func (m *Migrations) setHeader() {
	headers := []string{"SCHEMA", "VERSION", "PENDING", "LAST APPLIED"}
	for i, h := range headers {
		m.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1))
	}
}

func (m *Migrations) Refresh() {
	m.mu.Lock()
	defer m.mu.Unlock()
	list, err := m.client.MigrationStatus()
	if err != nil {
		m.table.Clear()
		m.setHeader()
		m.table.SetCell(1, 0, dataCell(fmt.Sprintf("ERROR: %v", err)))
		return
	}
	m.table.Clear()
	m.setHeader()
	for i, s := range list {
		row := i + 1
		m.table.SetCell(row, 0, dataCell(s.Schema).SetExpansion(1))
		m.table.SetCell(row, 1, dataCell(s.CurrentVersion).SetExpansion(1))
		m.table.SetCell(row, 2, tview.NewTableCell(fmt.Sprintf("%d", s.Pending)).SetExpansion(1))
		m.table.SetCell(row, 3, tview.NewTableCell(s.LastApplied.Format("2006-01-02 15:04")).SetExpansion(1))
	}
	_ = types.MigrationStatus{}
}
