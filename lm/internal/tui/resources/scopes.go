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
	table    *tview.Table
	items    []client.RagScope
	onEnter  func(scope string)
	onSelect func()
}

func NewScopes(c *client.Client) *Scopes {
	s := &Scopes{client: c, table: tview.NewTable().SetBorders(false).SetSelectable(true, false)}
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
	return "Enter: switch scope · d: describe · y: yank · s: sort · r: refresh"
}
func (s *Scopes) SetOnEnter(fn func(string)) { s.onEnter = fn }

func (s *Scopes) Refresh() {
	items, err := s.client.RagScopes()
	if err != nil {
		s.table.Clear()
		s.table.SetCell(0, 0, dataCell("error: "+err.Error()))
		return
	}
	s.items = items
	s.render()
}

func (s *Scopes) render() {
	s.table.Clear()
	headers := []string{"SCOPE", "CONNECTORS", "CHUNKS", "LAST INDEXED"}
	for i, h := range headers {
		s.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetTextColor(tcell.ColorYellow))
	}
	for i, sc := range s.items {
		row := i + 1
		s.table.SetCell(row, 0, dataCell(sc.Scope))
		s.table.SetCell(row, 1, tview.NewTableCell(fmt.Sprintf("%d", sc.Connectors)))
		s.table.SetCell(row, 2, tview.NewTableCell(fmt.Sprintf("%d", sc.Chunks)))
		s.table.SetCell(row, 3, dataCell(output.HumanTimeStr(sc.LastIndexed)))
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
