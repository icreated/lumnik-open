package resources

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

type DLQ struct {
	table  *tview.Table
	client *client.Client
	items  []types.DeadLetter
	all    []types.DeadLetter // every letter the hub listed; items is what the / filter shows
	textFilter
	mu       sync.Mutex
	app      *tview.Application
	offLoop  offLoop
	inFlight inFlight // the guard on ^R and ^D (#1147)
	onError  func(string)
	sortCol  *style.ColumnSort
	onSelect func()
	// The drill-downs, at most one set: :runs leads here by run, :sources by connector.
	filterConnector string
	filterRun       string
	ask             Prompt // wired by the App: ^R asks which run carried the replay
}

// SetAsk is wired by the App; without it ^R records the replay with no run, as before #1127.
func (d *DLQ) SetAsk(fn Prompt) { d.ask = fn }

// dlqPageCap is the page the hub serves (it clamps limit to 200).
const dlqPageCap = 200

func NewDLQ(app *tview.Application, c *client.Client, onError func(string)) *DLQ {
	t := newListTable()
	t.SetTitle(" :dlq ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	d := &DLQ{
		table:   t,
		client:  c,
		app:     app,
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
	base := "^R: replay  ^D: discard  d: describe  y: yank  s: sort  r: refresh"
	if d.FilterActive() {
		base = "Esc: clear filter  " + base
	}
	return base
}

func (d *DLQ) setHeader() {
	headers := []string{
		"ID",
		d.sortCol.HeaderLabel(0),
		"RUN",
		d.sortCol.HeaderLabel(2),
		"ATTEMPTS",
		"RESOLUTION",
		"MESSAGE",
	}
	for i, h := range headers {
		d.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1))
	}
}

// Refresh lists off the event loop (offLoop): the live ticker and every run event call it while
// the page is in front (#1109).
func (d *DLQ) Refresh() {
	d.offLoop.run(d.app, func() func() {
		connectorID, runID := d.filters()
		list, err := d.client.DlqList(connectorID, runID, dlqPageCap)
		return func() { d.renderList(list, err) }
	})
}

func (d *DLQ) renderList(list []types.DeadLetter, err error) {
	if err != nil {
		if d.onError != nil {
			d.onError(fmt.Sprintf("dlq list: %v", err))
		}
		return
	}
	d.setTitle(len(list) >= dlqPageCap)
	d.mu.Lock()
	prev := selectedKey(d.table, d.items, dlqKey)
	d.all = list
	d.items = filterRows(list, d.q, func(it types.DeadLetter) []string {
		return []string{it.ID, it.ConnectorLabel(), it.RunID, it.SkipReason, it.ErrorMessage}
	})
	d.applySort()
	d.render()
	reselect(d.table, d.items, dlqKey, prev)
	d.mu.Unlock()
	if d.onSelect != nil { // an open describe panel follows the fresh row
		d.onSelect()
	}
}

// SetTextFilter narrows the table to the letters holding q (#1120); "" shows them all.
func (d *DLQ) SetTextFilter(q string) { d.q = q; d.renderList(d.all, nil) }

func dlqKey(it types.DeadLetter) string { return it.ID }

// SetRunFilter narrows the queue to one run's dead letters (#1111). Empty = all.
func (d *DLQ) SetRunFilter(runID string) { d.setFilters("", runID) }

// SetConnectorFilter narrows the queue to one connector's dead letters. Empty = all.
func (d *DLQ) SetConnectorFilter(connectorID string) { d.setFilters(connectorID, "") }

// ClearFilter shows the whole queue again.
func (d *DLQ) ClearFilter() { d.setFilters("", "") }

// FilterActive reports whether a drill-down is in force.
func (d *DLQ) FilterActive() bool {
	connectorID, runID := d.filters()
	return connectorID != "" || runID != ""
}

func (d *DLQ) setFilters(connectorID, runID string) {
	d.mu.Lock()
	d.filterConnector, d.filterRun = connectorID, runID
	d.mu.Unlock()
	d.setTitle(false)
	d.Refresh()
}

// filters reads the drill-downs under the lock: Refresh carries them into the fetch goroutine.
func (d *DLQ) filters() (connectorID, runID string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.filterConnector, d.filterRun
}

// setTitle names the drill-down in force and, on a full page, that the queue goes on beyond it:
// the hub serves at most dlqPageCap rows, and a page of exactly that many must not pass for the
// whole queue.
func (d *DLQ) setTitle(capped bool) {
	connectorID, runID := d.filters()
	title := " :dlq "
	switch {
	case runID != "":
		title = fmt.Sprintf(" :dlq [run=%s] ", shortDlqID(runID))
	case connectorID != "":
		title = fmt.Sprintf(" :dlq [conn=%s] ", shortDlqID(connectorID))
	}
	if capped {
		title += fmt.Sprintf("(first %d) ", dlqPageCap)
	}
	d.table.SetTitle(title)
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
			return a.ConnectorLabel() < b.ConnectorLabel()
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
		d.table.SetCell(row, 1, dataCell(it.ConnectorLabel()).SetExpansion(1))
		d.table.SetCell(row, 2, dataCell(shortDlqID(it.RunID)).SetExpansion(1))
		d.table.SetCell(row, 3, dataCell(it.SkipReason).SetExpansion(1))
		d.table.SetCell(row, 4, tview.NewTableCell(fmt.Sprintf("%d", it.Attempts)).SetExpansion(1))
		d.table.SetCell(row, 5, dataCell(it.Resolution).SetExpansion(1))
		// The same message, the same width, the same rule as `lm dlq list` — this used to slice
		// BYTES and could cut a rune in half (#498).
		d.table.SetCell(row, 6,
			dataCell(output.Truncate(it.ErrorMessage, output.DlqMessageWidth)).SetExpansion(2))
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
			d.replayed(it)
		}
		return nil
	case tcell.KeyCtrlD:
		if it, ok := d.selected(); ok {
			d.inFlight.background(d.app, "^D", d.onError, func() func() {
				err := d.client.DlqDiscard(it.ID)
				return func() {
					if err != nil {
						say(d.onError, fmt.Sprintf("discard: %v", err))
					}
					d.Refresh()
				}
			})
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

// Describable

func (d *DLQ) SelectedDescribe() (string, string, bool) {
	it, ok := d.selected()
	if !ok {
		return "", "", false
	}
	esc := tview.Escape
	var b strings.Builder
	fmt.Fprintf(&b, "id:          %s\n", esc(it.ID))
	fmt.Fprintf(&b, "connector:   %s\n", esc(it.ConnectorNameAndID()))
	fmt.Fprintf(&b, "run:         %s\n", esc(it.RunID))
	fmt.Fprintf(&b, "reason:      %s\n", esc(it.SkipReason))
	fmt.Fprintf(&b, "attempts:    %d\n", it.Attempts)
	fmt.Fprintf(&b, "captured:    %s\n", output.HumanTimeSeconds(&it.CapturedAt))
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

const noRunToReplay = "replayed: name the run that carried the row — no run carried it? ^D discards it"

// replayed marks a letter replayed and names the run that carried it (#1127): it offers the
// letter's connector's latest run — Enter takes it, anything else is the run typed. There is no
// replay without a run (#1377): with none to offer and none typed, it says to discard instead.
// The lookup and the mark run off the event loop, each under ^R: a ^R pressed while either is
// out is dropped.
func (d *DLQ) replayed(it types.DeadLetter) {
	mark := func(runID string) {
		d.inFlight.background(d.app, "^R", d.onError, func() func() {
			err := d.client.DlqReplayed(it.ID, runID)
			return func() {
				if err != nil {
					say(d.onError, fmt.Sprintf("replayed: %v", err))
				}
				d.Refresh()
			}
		})
	}
	if d.ask == nil { // no prompt to name the run with: there is no replay without one
		say(d.onError, noRunToReplay)
		return
	}
	d.inFlight.background(d.app, "^R", d.onError, func() func() {
		latest := latestRun(d.client, it.ConnectorID)
		return func() {
			label := "Run that carried the replay: "
			if latest != "" {
				label = fmt.Sprintf("Run that carried the replay (Enter: %s, the connector's latest): ", shortRunID(latest))
			}
			d.ask(label, false, func(answer string) {
				if answer = strings.TrimSpace(answer); answer == "" {
					answer = latest
				}
				if answer == "" {
					say(d.onError, noRunToReplay)
					return
				}
				mark(answer)
			})
		}
	})
}

// latestRun is the newest run of a connector, "" when it has none or the hub cannot say.
func latestRun(c *client.Client, connectorID string) string {
	runs, err := c.RunList(connectorID, "", 20)
	if err != nil {
		return ""
	}
	var best types.Run
	for _, r := range runs {
		if best.ID == "" || newestFirst(r, best) {
			best = r
		}
	}
	return best.ID
}
