package resources

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

type DLQ struct {
	table    *tview.Table
	client   *client.Client
	items    []types.DeadLetter
	mu       sync.Mutex
	onError  func(string)
	sortCol  *style.ColumnSort
	onSelect func()
}

func NewDLQ(c *client.Client, onError func(string)) *DLQ {
	t := tview.NewTable().SetBorders(false).SetSelectable(true, false)
	t.SetTitle(" :dlq ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	d := &DLQ{
		table:   t,
		client:  c,
		onError: onError,
		sortCol: style.NewColumnSort([]string{"CONNECTOR", "CAPTURED", "REASON"}),
	}
	d.setHeader()
	t.SetInputCapture(d.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if d.onSelect != nil {
			d.onSelect()
		}
	})
	return d
}

func (d *DLQ) View() tview.Primitive { return d.table }

func (d *DLQ) Hints() string {
	return "^R: replay  ^D: discard  d: describe  y: yank  s: sort  r: refresh"
}

func (d *DLQ) setHeader() {
	headers := []string{
		"ID",
		d.sortCol.HeaderLabel(0),
		"RUN",
		d.sortCol.HeaderLabel(2),
		"ATTEMPTS",
		"MESSAGE",
	}
	for i, h := range headers {
		d.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1))
	}
}

func (d *DLQ) Refresh() {
	d.mu.Lock()
	defer d.mu.Unlock()
	list, err := d.client.DlqList("", "", 200)
	if err != nil {
		if d.onError != nil {
			d.onError(fmt.Sprintf("dlq list: %v", err))
		}
		return
	}
	d.items = list
	d.applySort()
	d.render()
}

func (d *DLQ) applySort() {
	idx, desc, sorted := d.sortCol.Active()
	if !sorted {
		return
	}
	sort.SliceStable(d.items, func(i, j int) bool {
		// Descending swaps the OPERANDS, never negates the result — see sources.go.
		a, b := d.items[i], d.items[j]
		if desc {
			a, b = b, a
		}
		switch idx {
		case 0:
			return a.ConnectorID < b.ConnectorID
		case 1:
			return a.CapturedAt.Before(b.CapturedAt)
		case 2:
			return a.SkipReason < b.SkipReason
		}
		return false
	})
}

func (d *DLQ) render() {
	d.table.Clear()
	d.setHeader()
	errorColor := tcell.NewRGBColor(0xEF, 0x44, 0x44)
	for i, it := range d.items {
		row := i + 1
		idCell := dataCell("● " + shortDlqID(it.ID)).SetExpansion(1)
		if style.ColorsEnabled() {
			idCell.SetTextColor(errorColor)
		}
		d.table.SetCell(row, 0, idCell)
		d.table.SetCell(row, 1, dataCell(shortDlqID(it.ConnectorID)).SetExpansion(1))
		d.table.SetCell(row, 2, dataCell(shortDlqID(it.RunID)).SetExpansion(1))
		d.table.SetCell(row, 3, dataCell(it.SkipReason).SetExpansion(1))
		d.table.SetCell(row, 4, tview.NewTableCell(fmt.Sprintf("%d", it.Attempts)).SetExpansion(1))
		msg := it.ErrorMessage
		if len(msg) > 60 {
			msg = msg[:60] + "…"
		}
		d.table.SetCell(row, 5, dataCell(msg).SetExpansion(2))
	}
}

func (d *DLQ) selected() (types.DeadLetter, bool) {
	row, _ := d.table.GetSelection()
	if row < 1 || row-1 >= len(d.items) {
		return types.DeadLetter{}, false
	}
	return d.items[row-1], true
}

func (d *DLQ) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	switch ev.Key() {
	case tcell.KeyCtrlR:
		if it, ok := d.selected(); ok {
			if err := d.client.DlqReplayed(it.ID, ""); err != nil && d.onError != nil {
				d.onError(fmt.Sprintf("replayed: %v", err))
			}
			d.Refresh()
		}
		return nil
	case tcell.KeyCtrlD:
		if it, ok := d.selected(); ok {
			if err := d.client.DlqDiscard(it.ID); err != nil && d.onError != nil {
				d.onError(fmt.Sprintf("discard: %v", err))
			}
			d.Refresh()
		}
		return nil
	}
	switch ev.Rune() {
	case 's':
		d.sortCol.Next()
		d.Refresh()
		return nil
	case 'r':
		d.Refresh()
		return nil
	}
	return ev
}

func (d *DLQ) ApplyEvent(_ map[string]any) { d.Refresh() }

// Describable

func (d *DLQ) SelectedDescribe() (string, string, bool) {
	it, ok := d.selected()
	if !ok {
		return "", "", false
	}
	esc := tview.Escape
	var b strings.Builder
	fmt.Fprintf(&b, "id:          %s\n", esc(it.ID))
	fmt.Fprintf(&b, "connector:   %s\n", esc(it.ConnectorID))
	fmt.Fprintf(&b, "run:         %s\n", esc(it.RunID))
	fmt.Fprintf(&b, "reason:      %s\n", esc(it.SkipReason))
	fmt.Fprintf(&b, "attempts:    %d\n", it.Attempts)
	fmt.Fprintf(&b, "captured:    %s\n", it.CapturedAt.Format("2006-01-02 15:04:05"))
	fmt.Fprintf(&b, "\n── error ──\n%s\n", esc(it.ErrorMessage))
	if it.Payload != "" {
		payload := it.Payload
		if lines := strings.Split(payload, "\n"); len(lines) > 50 {
			payload = strings.Join(lines[:50], "\n") + "\n… (truncated; yank for full payload)"
		}
		fmt.Fprintf(&b, "\n── payload ──\n%s\n", esc(payload))
	}
	return shortDlqID(it.ID), b.String(), true
}

func (d *DLQ) OnSelectionChange(cb func()) { d.onSelect = cb }

// Yankable

func (d *DLQ) SelectedYank() (string, bool) {
	it, ok := d.selected()
	if !ok {
		return "", false
	}
	type out struct {
		ID          string `json:"id"`
		ConnectorID string `json:"connectorId"`
		RunID       string `json:"runId"`
		Reason      string `json:"reason"`
		Error       string `json:"error"`
		Payload     string `json:"payload,omitempty"`
	}
	data, err := json.MarshalIndent(out{
		ID:          it.ID,
		ConnectorID: it.ConnectorID,
		RunID:       it.RunID,
		Reason:      it.SkipReason,
		Error:       it.ErrorMessage,
		Payload:     it.Payload,
	}, "", "  ")
	if err != nil {
		return "", false
	}
	return string(data), true
}

func shortDlqID(s string) string {
	if len(s) > 8 {
		return s[:8]
	}
	return s
}
