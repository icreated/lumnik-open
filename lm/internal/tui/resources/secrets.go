package resources

import (
	"fmt"
	"strings"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// Prompt asks for one line of text; masked hides what is typed. done is called only on Enter.
type Prompt func(label string, masked bool, done func(string))

// Secrets is :secrets — the credentials a connector manifest refers to by name, stored from the
// TUI instead of a detour through `lm secret set` (#1113). The hub never returns a value, and the
// page never holds one longer than the call that sends it: not in a toast, a cell or describe.
type Secrets struct {
	app    *tview.Application
	client *client.Client
	table  *tview.Table
	items  []client.SecretMeta
	all    []client.SecretMeta // every secret the hub listed; items is what the / filter shows
	textFilter
	mu       sync.Mutex
	offLoop  offLoop
	inFlight inFlight // the guard on n, e and Del (#1147)
	toast    func(string)
	onError  func(string)
	ask      Prompt
	confirm  ConfirmAs
	onSelect func()
}

func NewSecrets(app *tview.Application, c *client.Client, toast, onError func(string)) *Secrets {
	t := newListTable()
	t.SetTitle(" :secrets ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	s := &Secrets{app: app, client: c, table: t, toast: toast, onError: onError}
	t.SetInputCapture(s.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if s.onSelect != nil {
			s.onSelect()
		}
	})
	return s
}

// SetAsk and SetConfirm are wired by the App; without them n/e and Del do nothing.
func (s *Secrets) SetAsk(fn Prompt)        { s.ask = fn }
func (s *Secrets) SetConfirm(fn ConfirmAs) { s.confirm = fn }

func (s *Secrets) View() tview.Primitive { return s.table }

func (s *Secrets) Hints() string {
	return "n: new  e: rotate the value  Del: delete  d: describe  r: refresh"
}

func (s *Secrets) Refresh() {
	s.offLoop.run(s.app, func() func() {
		list, err := s.client.SecretList()
		return func() { s.renderList(list, err) }
	})
}

func (s *Secrets) renderList(list []client.SecretMeta, err error) {
	if err != nil {
		say(s.onError, fmt.Sprintf("secrets: %v", err))
		return
	}
	s.mu.Lock()
	prev := selectedKey(s.table, s.items, secretKey)
	s.all = list
	s.items = filterRows(list, s.q, func(m client.SecretMeta) []string { return []string{m.Name, m.Description} })
	s.render()
	reselect(s.table, s.items, secretKey, prev)
	s.mu.Unlock()
	if s.onSelect != nil {
		s.onSelect()
	}
}

// SetTextFilter narrows the table to the secrets holding q (#1120); "" shows them all.
func (s *Secrets) SetTextFilter(q string) { s.q = q; s.renderList(s.all, nil) }

func secretKey(m client.SecretMeta) string { return m.Name }

func (s *Secrets) render() {
	s.table.Clear()
	for c, h := range []string{"NAME", "DESCRIPTION", "UPDATED", "ROTATED"} {
		s.table.SetCell(0, c, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1).SetTextColor(tcell.ColorYellow))
	}
	if len(s.items) == 0 {
		s.table.SetCell(1, 0, tview.NewTableCell("No secrets yet — press n to store one.").SetSelectable(false))
		return
	}
	for r, m := range s.items {
		for c, v := range []string{m.Name, m.Description, output.HumanTimeStr(m.UpdatedAt), output.HumanTimeStr(m.RotatedAt)} {
			s.table.SetCell(r+1, c, dataCell(v).SetExpansion(1))
		}
	}
}

func (s *Secrets) selected() (client.SecretMeta, bool) {
	row, _ := s.table.GetSelection()
	if row < 1 || row-1 >= len(s.items) {
		return client.SecretMeta{}, false
	}
	return s.items[row-1], true
}

func (s *Secrets) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Key() == tcell.KeyDelete {
		s.delete()
		return nil
	}
	switch ev.Rune() {
	case 'n':
		s.create()
		return nil
	case 'e':
		s.rotate()
		return nil
	case 'r':
		s.Refresh()
		return nil
	}
	return ev
}

func (s *Secrets) create() {
	if s.ask == nil || s.inFlight.busy("n", s.toast) { // no question for a press that would be dropped
		return
	}
	s.ask("Secret name: ", false, func(name string) {
		name = strings.TrimSpace(name)
		if name == "" {
			return
		}
		s.ask("Value for "+name+" (hidden): ", true, func(value string) {
			s.inFlight.background(s.app, "n", s.toast, func() func() {
				_, err := s.client.SecretSet(name, value, nil)
				return func() {
					if err != nil {
						say(s.onError, fmt.Sprintf("set %s: %v", name, err))
						return
					}
					say(s.toast, "✓ stored: "+name)
					s.Refresh()
				}
			})
		})
	})
}

func (s *Secrets) rotate() {
	m, ok := s.selected()
	if !ok || s.ask == nil || s.inFlight.busy("e", s.toast) {
		return
	}
	s.ask("New value for "+m.Name+" (hidden): ", true, func(value string) {
		s.inFlight.background(s.app, "e", s.toast, func() func() {
			err := s.client.SecretUpdate(m.Name, value, nil)
			return func() {
				if err != nil {
					say(s.onError, fmt.Sprintf("rotate %s: %v", m.Name, err))
					return
				}
				say(s.toast, "✓ rotated: "+m.Name)
				s.Refresh()
			}
		})
	})
}

func (s *Secrets) delete() {
	m, ok := s.selected()
	if !ok || s.confirm == nil || s.inFlight.busy("Del", s.toast) {
		return
	}
	s.confirm(fmt.Sprintf("Delete secret %q? Connectors that refer to it stop authenticating.", m.Name), "Delete", func() {
		s.inFlight.background(s.app, "Del", s.toast, func() func() {
			err := s.client.SecretDelete(m.Name)
			return func() {
				if err != nil {
					say(s.onError, fmt.Sprintf("delete %s: %v", m.Name, err))
					return
				}
				say(s.toast, "deleted: "+m.Name)
				s.Refresh()
			}
		})
	})
}

// Describable — metadata only; the hub has no value to give.

func (s *Secrets) SelectedDescribe() (string, string, bool) {
	m, ok := s.selected()
	if !ok {
		return "", "", false
	}
	esc := tview.Escape
	var b strings.Builder
	fmt.Fprintf(&b, "name:        %s\n", esc(m.Name))
	fmt.Fprintf(&b, "description: %s\n", esc(m.Description))
	fmt.Fprintf(&b, "created:     %s\n", esc(m.CreatedAt))
	fmt.Fprintf(&b, "updated:     %s\n", esc(m.UpdatedAt))
	fmt.Fprintf(&b, "rotated:     %s\n", esc(m.RotatedAt))
	b.WriteString("\nThe value is write-only: the hub never returns it.\n")
	return m.Name, b.String(), true
}

func (s *Secrets) OnSelectionChange(cb func()) { s.onSelect = cb }
