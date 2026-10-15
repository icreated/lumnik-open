package resources

import (
	"fmt"
	"sync/atomic"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// inboxWhen renders a decision's age the k9s way: 2m, 3h, 2d. Unparseable input passes through.
func inboxWhen(occurredAt string, now time.Time) string {
	if occurredAt == "" {
		return ""
	}
	// RFC3339Nano for house consistency with the CLI's quandOf (Java Instant.toString()
	// carries fractional seconds); Go parses them under either layout — the test proves it.
	t, err := time.Parse(time.RFC3339Nano, occurredAt)
	if err != nil {
		return occurredAt
	}
	d := now.Sub(t)
	if d < 0 {
		d = 0 // clock skew must never render a negative age
	}
	switch {
	case d < time.Hour:
		return fmt.Sprintf("%dm", int(d.Minutes()))
	case d < 24*time.Hour:
		return fmt.Sprintf("%dh", int(d.Hours()))
	default:
		return fmt.Sprintf("%dd", int(d.Hours()/24))
	}
}

// inboxCells maps one decision to its table row; disposed entries carry the [traité] mark.
func inboxCells(e client.InboxEntry, now time.Time) []string {
	msg := e.Message
	if e.Acked {
		msg += " [traité]"
	}
	return []string{fmt.Sprintf("%d", e.ID), inboxWhen(e.OccurredAt, now), e.Process, msg, e.EntityKey}
}

// Inbox is the observation deck: the tenant's decision.* events, newest first.
// `a` acks the selected decision (idempotent server-side); `A` toggles disposed entries.
type Inbox struct {
	app       *tview.Application
	client    *client.Client
	table     *tview.Table
	items     []client.InboxEntry
	offLoop   offLoop
	showAll   atomic.Bool // toggled on the loop, read by the off-loop fetch
	showError func(string)
	toast     func(string)
	onSelect  func()
}

func NewInbox(app *tview.Application, c *client.Client, showError func(string), toast func(string)) *Inbox {
	i := &Inbox{app: app, client: c, showError: showError, toast: toast,
		table: tview.NewTable().SetBorders(false).SetSelectable(true, false)}
	style.ApplyChrome(i.table.Box)
	style.ApplyTableSelection(i.table)
	i.table.SetInputCapture(i.handleKey)
	i.table.SetSelectionChangedFunc(func(row, col int) {
		if i.onSelect != nil {
			i.onSelect()
		}
	})
	return i
}

func (i *Inbox) View() tview.Primitive { return i.table }
func (i *Inbox) Hints() string {
	return "a: ack · A: show disposed · d: describe · y: yank · r: refresh"
}

func (i *Inbox) toggleAll() { i.showAll.Store(!i.showAll.Load()) }

// Refresh lists off the event loop (offLoop): the live ticker calls it while the page is in front
// (#891).
func (i *Inbox) Refresh() {
	i.offLoop.run(i.app, func() func() {
		items, err := i.client.InboxList(50, i.showAll.Load())
		return func() { i.renderList(items, err) }
	})
}

func (i *Inbox) renderList(items []client.InboxEntry, err error) {
	if err != nil {
		// Keep internal state consistent with the rendered error: a stale selection
		// must never let `a` ack an entry the user can no longer see.
		i.items = nil
		i.table.Clear()
		i.table.SetCell(0, 0, dataCell("error: "+err.Error()))
	} else {
		i.items = items
		i.render()
	}
	if i.onSelect != nil { // an open describe panel follows the fresh row, or its absence
		i.onSelect()
	}
}

func (i *Inbox) render() {
	i.table.Clear()
	headers := []string{"ID", "QUAND", "PROCESS", "MESSAGE", "ENTITY_KEY"}
	for c, h := range headers {
		i.table.SetCell(0, c, tview.NewTableCell(h).SetSelectable(false).SetTextColor(tcell.ColorYellow))
	}
	now := time.Now()
	for r, e := range i.items {
		for c, cell := range inboxCells(e, now) {
			i.table.SetCell(r+1, c, dataCell(cell))
		}
	}
}

func (i *Inbox) selected() (client.InboxEntry, bool) {
	row, _ := i.table.GetSelection()
	if row < 1 || row-1 >= len(i.items) {
		return client.InboxEntry{}, false
	}
	return i.items[row-1], true
}

func (i *Inbox) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	switch ev.Rune() {
	case 'a':
		if e, ok := i.selected(); ok {
			if err := i.client.InboxAck(e.ID); err != nil {
				if i.showError != nil {
					i.showError(err.Error())
				}
				return nil
			}
			if i.toast != nil {
				i.toast("acked.")
			}
			i.Refresh()
		}
		return nil
	case 'A':
		i.toggleAll()
		i.Refresh()
		return nil
	}
	return ev
}

// Describable
func (i *Inbox) SelectedDescribe() (string, string, bool) {
	if i.table == nil {
		return "", "", false
	}
	e, ok := i.selected()
	if !ok {
		return "", "", false
	}
	esc := tview.Escape
	body := fmt.Sprintf("id:         %d\nwhen:       %s\ntype:       %s\nprocess:    %s\nmessage:    %s\nentity:     %s\nentity_key: %s\nacked:      %v",
		e.ID, esc(e.OccurredAt), esc(e.Type), esc(e.Process), esc(e.Message), esc(e.Entity), esc(e.EntityKey), e.Acked)
	return fmt.Sprintf("decision %d", e.ID), body, true
}
func (i *Inbox) OnSelectionChange(cb func()) { i.onSelect = cb }

// Yankable
func (i *Inbox) SelectedYank() (string, bool) {
	if i.table == nil {
		return "", false
	}
	e, ok := i.selected()
	if !ok {
		return "", false
	}
	return e.Message, true
}
