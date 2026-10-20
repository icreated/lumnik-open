package resources

import (
	"fmt"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
	"gopkg.in/yaml.v3"
)

type Hooks struct {
	app      *tview.Application
	table    *tview.Table
	client   *client.Client
	offLoop  offLoop
	mu       sync.Mutex
	onError  func(string)
	items    []types.Hook
	detail   lazyDetail // d: the hook as `lm hook get` shows it (#1138)
	onSelect func()
}

func NewHooks(app *tview.Application, c *client.Client, onError func(string)) *Hooks {
	t := newListTable()
	t.SetTitle(" :hooks ").SetBorder(true)
	h := &Hooks{app: app, table: t, client: c, onError: onError}
	h.setHeader()
	h.table.SetInputCapture(h.handleKey)
	h.table.SetSelectionChangedFunc(func(row, col int) {
		if h.onSelect != nil {
			h.onSelect()
		}
	})
	return h
}

func (h *Hooks) View() tview.Primitive { return h.table }
func (h *Hooks) Hints() string {
	return "d: describe  r: refresh  :: switch  ?: help  q: quit"
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
	if err != nil {
		h.mu.Unlock()
		if h.onError != nil {
			h.onError(fmt.Sprintf("hooks list: %v", err))
		}
		return
	}
	prev := selectedKey(h.table, h.items, hookKey)
	h.items = list
	h.detail.retryFailed()
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
	reselect(h.table, h.items, hookKey, prev)
	h.mu.Unlock()
	if h.onSelect != nil { // an open describe panel follows the fresh row
		h.onSelect()
	}
}

func hookKey(hk types.Hook) string { return hk.ID }

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

// SelectedDescribe shows the hook as `lm hook get` does: the singular door's binding (handler,
// events, priority…), richer than the list's row. It is fetched once per hook, off the loop; until
// it lands the panel shows the row itself.
func (h *Hooks) SelectedDescribe() (string, string, bool) {
	row, _ := h.table.GetSelection()
	if row < 1 || row-1 >= len(h.items) {
		return "", "", false
	}
	hk := h.items[row-1]
	body, ok := h.detail.get(h.app, hk.ID, func() (string, error) {
		d, err := h.client.HookGet(hk.ID)
		if err != nil {
			return "", err
		}
		data, err := yaml.Marshal(d)
		if err != nil {
			return "", err
		}
		return highlightYAML(string(data)), nil
	}, h.onSelect)
	if !ok {
		body = fmt.Sprintf("type:    %s\nentity:  %s\nscope:   %s\nstatus:  %s\n\n[gray]fetching the binding…[white]",
			tview.Escape(hk.Type), tview.Escape(hk.Entity), tview.Escape(hk.Scope), tview.Escape(hk.Status))
	}
	return "hook " + hk.ID, body, true
}

func (h *Hooks) OnSelectionChange(cb func()) { h.onSelect = cb }
