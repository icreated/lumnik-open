package resources

import (
	"fmt"
	"strings"
	"sync"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

type Migrations struct {
	app     *tview.Application
	table   *tview.Table
	client  *client.Client
	offLoop offLoop
	mu      sync.Mutex
}

func NewMigrations(app *tview.Application, c *client.Client) *Migrations {
	t := newListTable()
	t.SetTitle(" :migrations ").SetBorder(true)
	m := &Migrations{app: app, table: t, client: c}
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

// Refresh lists off the event loop (offLoop): the live ticker calls it while the page is in front
// (#891).
func (m *Migrations) Refresh() {
	m.offLoop.run(m.app, func() func() {
		list, err := m.client.MigrationStatus()
		return func() { m.renderList(list, err) }
	})
}

func (m *Migrations) renderList(list []types.MigrationStatus, err error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if err != nil {
		m.table.Clear()
		m.setHeader()
		m.table.SetCell(1, 0, dataCell(fmt.Sprintf("ERROR: %v", err)))
		return
	}
	m.table.Clear()
	m.setHeader()
	pending := 0
	var schemas []string
	for i, s := range list {
		row := i + 1
		m.table.SetCell(row, 0, dataCell(s.Schema).SetExpansion(1))
		m.table.SetCell(row, 1, dataCell(s.CurrentVersion).SetExpansion(1))
		m.table.SetCell(row, 2, tview.NewTableCell(fmt.Sprintf("%d", s.Pending)).SetExpansion(1))
		m.table.SetCell(row, 3, dataCell(output.HumanTimeStrOr(s.LastApplied, "—")).SetExpansion(1)) // the hub's string: an unparseable one comes verbatim
		if s.Pending > 0 {
			pending += s.Pending
			schemas = append(schemas, s.Schema)
		}
	}
	m.table.SetTitle(migrationsTitle(pending, schemas))
}

// migrationsTitle says a pending migration where it is seen, with the one thing that applies it: the
// hub runs its migrations at startup and offers no door to run them later (#1136, #1142) — so no
// key here, which would only meet a 404.
func migrationsTitle(pending int, schemas []string) string {
	if pending == 0 {
		return " :migrations "
	}
	return fmt.Sprintf(" :migrations — %d pending (%s) — the hub applies them when it next starts ",
		pending, tview.Escape(strings.Join(schemas, ", ")))
}
