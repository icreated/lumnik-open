package resources

import (
	"fmt"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

type Scopes struct {
	client   *client.Client
	app      *tview.Application
	offLoop  offLoop
	table    *tview.Table
	items    []client.RagScope
	onEnter  func(scope string)
	onSelect func()
}

func NewScopes(app *tview.Application, c *client.Client) *Scopes {
	s := &Scopes{client: c, app: app, table: newListTable()}
	s.table.SetTitle(" :scopes ").SetBorder(true)
	style.ApplyChrome(s.table.Box)
	style.ApplyTableSelection(s.table)
	s.table.SetInputCapture(s.handleKey)
	s.table.SetSelectionChangedFunc(func(row, col int) {
		if s.onSelect != nil {
			s.onSelect()
		}
	})
	return s
}

func (s *Scopes) View() tview.Primitive { return s.table }
func (s *Scopes) Hints() string {
	return "Enter: switch scope · d: describe · y: yank · r: refresh"
}
func (s *Scopes) SetOnEnter(fn func(string)) { s.onEnter = fn }

// Refresh lists off the event loop (offLoop), like every page since #1171.
func (s *Scopes) Refresh() {
	s.offLoop.run(s.app, func() func() {
		items, err := s.client.RagScopes()
		return func() { s.renderList(items, err) }
	})
}

func (s *Scopes) renderList(items []client.RagScope, err error) {
	if err != nil {
		s.items = nil
		s.table.Clear()
		s.table.SetCell(0, 0, dataCell("error: "+err.Error()))
		if s.onSelect != nil { // an open describe panel closes: its row is gone
			s.onSelect()
		}
		return
	}
	relist(s.table, &s.items, items, scopeKey, s.render, s.onSelect)
}

func scopeKey(sc client.RagScope) string { return sc.Scope }

func (s *Scopes) render() {
	s.table.Clear()
	renderRagScopes(s.table, s.items)
}

// renderRagScopes draws the per-scope RAG coverage — :scopes and :rag show the same table.
func renderRagScopes(t *tview.Table, items []client.RagScope) {
	headers := []string{"SCOPE", "CONNECTORS", "CHUNKS", "LAST INDEXED"}
	for i, h := range headers {
		t.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetTextColor(tcell.ColorYellow))
	}
	for i, sc := range items {
		row := i + 1
		t.SetCell(row, 0, dataCell(sc.Scope))
		t.SetCell(row, 1, tview.NewTableCell(fmt.Sprintf("%d", sc.Connectors)))
		t.SetCell(row, 2, tview.NewTableCell(fmt.Sprintf("%d", sc.Chunks)))
		t.SetCell(row, 3, dataCell(output.HumanTimeStr(sc.LastIndexed)))
	}
}

func (s *Scopes) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Key() == tcell.KeyEnter {
		row, _ := s.table.GetSelection()
		if row > 0 && row-1 < len(s.items) {
			if s.onEnter != nil {
				s.onEnter(s.items[row-1].Scope)
			}
		}
		return nil
	}
	return ev
}

// Describable
func (s *Scopes) SelectedDescribe() (string, string, bool) {
	if s.table == nil {
		return "", "", false
	}
	row, _ := s.table.GetSelection()
	if row < 1 || row-1 >= len(s.items) {
		return "", "", false
	}
	sc := s.items[row-1]
	esc := tview.Escape
	body := fmt.Sprintf("scope:      %s\nconnectors: %d\nchunks:     %d\nlast:       %s",
		esc(sc.Scope), sc.Connectors, sc.Chunks, esc(output.HumanTimeStr(sc.LastIndexed)))
	return sc.Scope, body, true
}
func (s *Scopes) OnSelectionChange(cb func()) { s.onSelect = cb }

// Yankable
func (s *Scopes) SelectedYank() (string, bool) {
	if s.table == nil {
		return "", false
	}
	row, _ := s.table.GetSelection()
	if row < 1 || row-1 >= len(s.items) {
		return "", false
	}
	return s.items[row-1].Scope, true
}
