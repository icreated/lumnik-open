package resources

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// Outputs is what the substrate sent out and whether it arrived, flattened across every
// integration. The order is the server's promise (failures first) — this page renders it.
type Outputs struct {
	table    *tview.Table
	client   *client.Client
	items    []client.Output
	mu       sync.Mutex
	onError  func(string)
	toast    func(string)
	onSelect func()
}

func NewOutputs(c *client.Client, onError, toast func(string)) *Outputs {
	t := tview.NewTable().SetBorders(false).SetSelectable(true, false)
	t.SetTitle(" :outputs ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	o := &Outputs{table: t, client: c, onError: onError, toast: toast}
	o.setHeader()
	t.SetInputCapture(o.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if o.onSelect != nil {
			o.onSelect()
		}
	})
	return o
}

func (o *Outputs) View() tview.Primitive { return o.table }

func (o *Outputs) Hints() string {
	return "^R: replay a failure  d: describe  y: yank  r: refresh"
}

func (o *Outputs) setHeader() {
	for i, h := range []string{"INTEGRATION", "EVENT", "STATUS", "WHEN", "ERROR"} {
		o.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1))
	}
}

func (o *Outputs) Refresh() {
	o.mu.Lock()
	defer o.mu.Unlock()
	list, err := o.client.OutputList(200)
	if err != nil {
		if o.onError != nil {
			o.onError(fmt.Sprintf("outputs: %v", err))
		}
		return
	}
	o.items = list
	o.render()
}

func (o *Outputs) render() {
	o.table.Clear()
	o.setHeader()
	now := time.Now()
	for i, it := range o.items {
		row := i + 1
		cells := []*tview.TableCell{
			dataCell(it.Integration).SetExpansion(1),
			dataCell(it.EventType).SetExpansion(2),
			dataCell(statusLabel(it)).SetExpansion(1),
			dataCell(humanizeSince(now.Sub(it.AttemptedAt))).SetExpansion(1),
			dataCell(it.Error).SetExpansion(2),
		}
		for col, cell := range cells {
			// A delivery that did not arrive is a warning: semantic yellow, never the brand
			// gold, which is chrome (the header rule).
			if !it.Succeeded && style.ColorsEnabled() {
				cell.SetTextColor(tcell.ColorYellow)
			}
			o.table.SetCell(row, col, cell)
		}
	}
}

// statusLabel keeps the two failure shapes distinguishable: a refusal carries an HTTP status,
// a connection that never landed carries none.
func statusLabel(it client.Output) string {
	if it.Succeeded {
		return "ok"
	}
	if it.HTTPStatus == nil {
		return "no response"
	}
	return fmt.Sprintf("%d", *it.HTTPStatus)
}

func (o *Outputs) selected() (client.Output, bool) {
	row, _ := o.table.GetSelection()
	if row < 1 || row-1 >= len(o.items) {
		return client.Output{}, false
	}
	return o.items[row-1], true
}

func (o *Outputs) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Key() == tcell.KeyCtrlR {
		it, ok := o.selected()
		if !ok {
			return nil
		}
		// Replay re-sends a real event to a third party. Only offered where it means
		// something: a delivery that failed.
		if it.Succeeded {
			if o.toast != nil {
				o.toast("this delivery arrived — nothing to replay")
			}
			return nil
		}
		if err := o.client.OutputReplay(it.ID); err != nil {
			if o.onError != nil {
				o.onError(fmt.Sprintf("replay: %v", err))
			}
			return nil
		}
		if o.toast != nil {
			o.toast("✓ replayed — a new attempt is on record")
		}
		o.Refresh()
		return nil
	}
	if ev.Rune() == 'r' {
		o.Refresh()
		return nil
	}
	return ev
}

// Describable

func (o *Outputs) SelectedDescribe() (string, string, bool) {
	it, ok := o.selected()
	if !ok {
		return "", "", false
	}
	esc := tview.Escape
	var b strings.Builder
	fmt.Fprintf(&b, "[yellow]%s — %s[white]\n\n", esc(it.Integration), esc(it.EventType))
	fmt.Fprintf(&b, "status:     %s\n", esc(statusLabel(it)))
	fmt.Fprintf(&b, "attempted:  %s\n", output.HumanTimeSeconds(&it.AttemptedAt))
	fmt.Fprintf(&b, "duration:   %dms\n", it.DurationMs)
	if it.Error != "" {
		fmt.Fprintf(&b, "\n── error ──\n%s\n", esc(it.Error))
	}
	return it.Integration + " " + it.EventType, b.String(), true
}

func (o *Outputs) OnSelectionChange(cb func()) { o.onSelect = cb }

// Yankable

func (o *Outputs) SelectedYank() (string, bool) {
	it, ok := o.selected()
	if !ok {
		return "", false
	}
	raw, err := json.MarshalIndent(it, "", "  ")
	if err != nil {
		return "", false
	}
	return string(raw), true
}
