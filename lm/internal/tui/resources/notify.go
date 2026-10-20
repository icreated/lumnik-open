package resources

import (
	"fmt"
	"strconv"
	"strings"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// Notify is :notify — the email rules routing matching events to a recipient (#1133), the TUI
// twin of `lm notify add|list|rm`. A rule carries no secret: the SMTP credentials are the deploy's.
type Notify struct {
	app    *tview.Application
	client *client.Client
	table  *tview.Table
	items  []client.NotificationRule
	all    []client.NotificationRule // every rule the hub listed; items is what the / filter shows
	textFilter
	mu       sync.Mutex
	offLoop  offLoop
	inFlight inFlight // the guard on n and Del (#1147)
	toast    func(string)
	onError  func(string)
	ask      Prompt
	confirm  ConfirmAs
	onSelect func()
}

func NewNotify(app *tview.Application, c *client.Client, toast, onError func(string)) *Notify {
	t := newListTable()
	t.SetTitle(" :notify ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	p := &Notify{app: app, client: c, table: t, toast: toast, onError: onError}
	t.SetInputCapture(p.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if p.onSelect != nil {
			p.onSelect()
		}
	})
	return p
}

// SetAsk and SetConfirm are wired by the App; without them n and Del do nothing.
func (p *Notify) SetAsk(fn Prompt)        { p.ask = fn }
func (p *Notify) SetConfirm(fn ConfirmAs) { p.confirm = fn }

func (p *Notify) View() tview.Primitive { return p.table }

func (p *Notify) Hints() string {
	return "n: new rule  Del: remove  d: describe  r: refresh"
}

func (p *Notify) Refresh() {
	p.offLoop.run(p.app, func() func() {
		list, err := p.client.NotificationRuleList()
		return func() { p.renderList(list, err) }
	})
}

func (p *Notify) renderList(list []client.NotificationRule, err error) {
	if err != nil {
		say(p.onError, fmt.Sprintf("notify: %v", err))
		return
	}
	p.mu.Lock()
	prev := selectedKey(p.table, p.items, ruleKey)
	p.all = list
	p.items = filterRows(list, p.q, ruleColumns)
	p.render()
	reselect(p.table, p.items, ruleKey, prev)
	p.mu.Unlock()
	if p.onSelect != nil {
		p.onSelect()
	}
}

// SetTextFilter narrows the table to the rules holding q; "" shows them all.
func (p *Notify) SetTextFilter(q string) { p.q = q; p.renderList(p.all, nil) }

func ruleKey(r client.NotificationRule) string { return strconv.FormatInt(r.ID, 10) }

func ruleColumns(r client.NotificationRule) []string {
	return []string{strconv.FormatInt(r.ID, 10), r.EventPattern, r.EntityFilter, r.Recipient, r.Template,
		yesNo(r.Enabled, "yes", "no")}
}

func (p *Notify) render() {
	headerRow(p.table, []string{"ID", "EVENTS", "ENTITY", "RECIPIENT", "TEMPLATE", "ENABLED"})
	if len(p.items) == 0 {
		p.table.SetCell(1, 0, tview.NewTableCell("No notification rules yet — press n to add one.").SetSelectable(false))
		return
	}
	for r, it := range p.items {
		for c, v := range ruleColumns(it) {
			p.table.SetCell(r+1, c, dataCell(v).SetExpansion(1))
		}
	}
}

func (p *Notify) selected() (client.NotificationRule, bool) {
	row, _ := p.table.GetSelection()
	if row < 1 || row-1 >= len(p.items) {
		return client.NotificationRule{}, false
	}
	return p.items[row-1], true
}

func (p *Notify) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Key() == tcell.KeyDelete {
		p.remove()
		return nil
	}
	switch ev.Rune() {
	case 'n':
		p.create()
		return nil
	case 'r':
		p.Refresh()
		return nil
	}
	return ev
}

// create adds a rule through a guided prompt; the hub validates every field and says why it refuses.
func (p *Notify) create() {
	if p.ask == nil || p.inFlight.busy("n", p.toast) { // no question for a press that would be dropped
		return
	}
	askAll(p.ask, []step{
		{"Events (a type, or a prefix ending in .*): ", false, true},
		{"Recipient email: ", false, true},
		{"Entity filter (Enter: every entity): ", false, false},
		{"Template (Enter: event-notification): ", false, false},
	}, func(a []string) {
		in := client.NotificationRuleCreate{EventPattern: a[0], Recipient: a[1], EntityFilter: a[2], Template: a[3]}
		p.inFlight.background(p.app, "n", p.toast, func() func() {
			created, err := p.client.NotificationRuleCreate(in)
			return func() {
				if err != nil {
					say(p.onError, fmt.Sprintf("add rule: %v", err))
					return
				}
				say(p.toast, fmt.Sprintf("✓ rule %d: %s → %s", created.ID, created.EventPattern, created.Recipient))
				p.Refresh()
			}
		})
	})
}

func (p *Notify) remove() {
	r, ok := p.selected()
	if !ok || p.confirm == nil || p.inFlight.busy("Del", p.toast) {
		return
	}
	p.confirm(fmt.Sprintf("Remove notification rule %d (%s → %s)?", r.ID, r.EventPattern, r.Recipient), "Remove", func() {
		p.inFlight.background(p.app, "Del", p.toast, func() func() {
			err := p.client.NotificationRuleDelete(r.ID)
			return func() {
				if err != nil {
					say(p.onError, fmt.Sprintf("remove rule %d: %v", r.ID, err))
					return
				}
				say(p.toast, fmt.Sprintf("removed: rule %d", r.ID))
				p.Refresh()
			}
		})
	})
}

// Describable

func (p *Notify) SelectedDescribe() (string, string, bool) {
	r, ok := p.selected()
	if !ok {
		return "", "", false
	}
	esc := tview.Escape
	entity := r.EntityFilter
	if entity == "" {
		entity = "(every entity)"
	}
	var b strings.Builder
	fmt.Fprintf(&b, "rule:      %d\n", r.ID)
	fmt.Fprintf(&b, "events:    %s\n", esc(r.EventPattern))
	fmt.Fprintf(&b, "entity:    %s\n", esc(entity))
	fmt.Fprintf(&b, "recipient: %s\n", esc(r.Recipient))
	fmt.Fprintf(&b, "template:  %s\n", esc(r.Template))
	fmt.Fprintf(&b, "enabled:   %t\n", r.Enabled)
	b.WriteString("\nDelivery needs the deploy's SMTP settings; without them a rule waits.\n")
	return "rule " + strconv.FormatInt(r.ID, 10), b.String(), true
}

func (p *Notify) OnSelectionChange(cb func()) { p.onSelect = cb }
