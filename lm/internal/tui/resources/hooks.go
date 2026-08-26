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
	table   *tview.Table
	client  *client.Client
	items   []types.Hook
	mu      sync.Mutex
	onError func(string)
}

func NewHooks(c *client.Client, onError func(string)) *Hooks {
	t := tview.NewTable().SetBorders(false).SetSelectable(true, false)
	t.SetTitle(" :hooks ").SetBorder(true)
	h := &Hooks{table: t, client: c, onError: onError}
	h.setHeader()
	h.table.SetInputCapture(h.handleKey)
	return h
}

func (h *Hooks) View() tview.Primitive { return h.table }
func (h *Hooks) Hints() string {
	return "^L: reload  r: refresh  :: switch  ?: help  q: quit"
}

func (h *Hooks) setHeader() {
	headers := []string{"NAME", "TYPE", "ENTITY", "SCOPE", "STATUS"}
	for i, head := range headers {
		h.table.SetCell(0, i, tview.NewTableCell(head).SetSelectable(false).SetExpansion(1))
	}
}

func (h *Hooks) Refresh() {
	h.mu.Lock()
	defer h.mu.Unlock()
	list, err := h.client.HookList()
	if err != nil {
		if h.onError != nil {
			h.onError(fmt.Sprintf("hooks list: %v", err))
		}
		return
	}
	h.items = list
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

func (h *Hooks) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Key() == tcell.KeyCtrlL {
		row, _ := h.table.GetSelection()
		if row >= 1 && row-1 < len(h.items) {
			if err := h.client.HookReload(h.items[row-1].ID); err != nil && h.onError != nil {
				h.onError(fmt.Sprintf("reload: %v", err))
			}
			h.Refresh()
		}
		return nil
	}
	if ev.Rune() == 'r' {
		h.Refresh()
		return nil
	}
	return ev
}
