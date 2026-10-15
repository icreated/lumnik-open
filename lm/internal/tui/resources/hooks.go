package resources

import (
	"fmt"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

type Hooks struct {
	app     *tview.Application
	table   *tview.Table
	client  *client.Client
	offLoop offLoop
	mu      sync.Mutex
	onError func(string)
}

func NewHooks(app *tview.Application, c *client.Client, onError func(string)) *Hooks {
	t := tview.NewTable().SetBorders(false).SetSelectable(true, false)
	t.SetTitle(" :hooks ").SetBorder(true)
	h := &Hooks{app: app, table: t, client: c, onError: onError}
	h.setHeader()
	h.table.SetInputCapture(h.handleKey)
	return h
}

func (h *Hooks) View() tview.Primitive { return h.table }
func (h *Hooks) Hints() string {
	return "r: refresh  :: switch  ?: help  q: quit"
}

func (h *Hooks) setHeader() {
	headers := []string{"NAME", "TYPE", "ENTITY", "SCOPE", "STATUS"}
	for i, head := range headers {
		h.table.SetCell(0, i, tview.NewTableCell(head).SetSelectable(false).SetExpansion(1))
	}
}

// Refresh lists off the event loop (offLoop): the live ticker calls it while the page is in front
// (#891).
func (h *Hooks) Refresh() {
	h.offLoop.run(h.app, func() func() {
		list, err := h.client.HookList()
		return func() { h.renderList(list, err) }
	})
}

func (h *Hooks) renderList(list []types.Hook, err error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if err != nil {
		if h.onError != nil {
			h.onError(fmt.Sprintf("hooks list: %v", err))
		}
		return
	}
	h.table.Clear()
	h.setHeader()
	for i, hk := range list {
		row := i + 1
		h.table.SetCell(row, 0, dataCell(hk.ID).SetExpansion(1))
		h.table.SetCell(row, 1, dataCell(hk.Type).SetExpansion(1))
		h.table.SetCell(row, 2, dataCell(hk.Entity).SetExpansion(1))
		h.table.SetCell(row, 3, dataCell(hk.Scope).SetExpansion(1))
		h.table.SetCell(row, 4, dataCell(hk.Status).SetExpansion(1))
	}
}

// handleKey no longer binds Ctrl-L: it used to call HookReload, POST
// /api/platform/hooks/{id}/reload — an endpoint that does not exist on the hub. Hooks are
// registered once, in-memory, from classpath manifests at startup; there is nothing here to
// reload individually (#610, same finding as `lm hook reload`).
func (h *Hooks) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Rune() == 'r' {
		h.Refresh()
		return nil
	}
	return ev
}
