package resources

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

// ── status ──

type runStatus int

const (
	runStatusUnknown  runStatus = iota
	runStatusSuccess            // ● green
	runStatusFailure            // ● red
	runStatusRunning            // ▶ amber
	runStatusDisabled           // ─ grey
)

func (s runStatus) color() tcell.Color {
	switch s {
	case runStatusSuccess:
		return tcell.NewRGBColor(0x22, 0xC5, 0x5E)
	case runStatusFailure:
		return tcell.NewRGBColor(0xEF, 0x44, 0x44)
	case runStatusRunning:
		return tcell.NewRGBColor(0xFF, 0x8C, 0x00)
	case runStatusDisabled:
		return tcell.NewRGBColor(0x6B, 0x72, 0x80)
	}
	return tcell.ColorDefault
}

func (s runStatus) glyph() string {
	switch s {
	case runStatusSuccess:
		return "●"
	case runStatusFailure:
		return "●"
	case runStatusRunning:
		return "▶"
	case runStatusDisabled:
		return "─"
	}
	return " "
}

func statusCell(text string, s runStatus) *tview.TableCell {
	c := dataCell(s.glyph() + " " + text).SetExpansion(1)
	if style.ColorsEnabled() {
		c.SetTextColor(s.color())
	}
	return c
}

func statusFor(s string) runStatus {
	switch runStatusKind(s) {
	case KindCompleted:
		return runStatusSuccess
	case KindFailed:
		return runStatusFailure
	case KindRunning:
		return runStatusRunning
	case KindCancelled:
		return runStatusDisabled
	}
	return runStatusUnknown
}

// ── Runs resource page ──

type Runs struct {
	table    *tview.Table
	client   *client.Client
	items    []types.Run
	mu       sync.Mutex
	app      *tview.Application
	fetching atomic.Bool
	dirty    atomic.Bool
	sort     *style.ColumnSort
	filterID string // connector_id filter, empty = no filter
	onSelect func()
}

func NewRuns(app *tview.Application, c *client.Client) *Runs {
	t := tview.NewTable().SetBorders(false).SetSelectable(true, false)
	t.SetTitle(" :runs ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	r := &Runs{
		table:  t,
		client: c,
		app:    app,
		sort:   style.NewColumnSort([]string{"CONNECTOR", "STATUS", "STARTED", "DURATION"}),
	}
	r.setHeader()
	t.SetInputCapture(r.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if r.onSelect != nil {
			r.onSelect()
		}
	})
	return r
}

func (r *Runs) View() tview.Primitive { return r.table }

func (r *Runs) Hints() string {
	base := "d: describe  y: yank  s: sort  r: refresh"
	if r.filterID != "" {
		base = "Esc: clear filter  " + base
	}
	return base
}

func (r *Runs) setHeader() {
	headers := []string{
		r.sort.HeaderLabel(0),
		r.sort.HeaderLabel(1),
		r.sort.HeaderLabel(2),
		r.sort.HeaderLabel(3),
		"RECORDS",
		"TRIGGER",
	}
	for i, h := range headers {
		r.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1))
	}
}

func (r *Runs) ApplyEvent(_ map[string]any) { r.Refresh() }

// SetFilter restricts the listing to runs of a single connector. Empty = all.
func (r *Runs) SetFilter(connectorID string) {
	r.filterID = connectorID
	if connectorID != "" {
		short := connectorID
		if len(short) > 8 {
			short = short[:8]
		}
		r.table.SetTitle(fmt.Sprintf(" :runs [conn=%s] ", short))
	} else {
		r.table.SetTitle(" :runs ")
	}
	r.Refresh()
}

// FilterActive returns true if a connector filter is set.
func (r *Runs) FilterActive() bool { return r.filterID != "" }

// ClearFilter wipes the filter and refreshes.
func (r *Runs) ClearFilter() { r.SetFilter("") }

// Refresh fetches OFF the UI thread — a blocking RunList on the main thread,
// re-triggered by every live SSE event (ApplyEvent), starved input and froze
// the TUI under an event burst (rehearsal carnet #9). The fetching guard
// coalesces a burst into one in-flight request; the render lands via QueueUpdate.
func (r *Runs) Refresh() {
	if r.app == nil { // tests construct a bare page
		list, err := r.client.RunList(200)
		r.renderList(list, err)
		return
	}
	if !r.fetching.CompareAndSwap(false, true) {
		r.dirty.Store(true) // a refresh landed mid-flight — don't drop it
		return
	}
	go func() {
		defer r.fetching.Store(false)
		for {
			list, err := r.client.RunList(200)
			QueueUpdate(r.app, func() { r.renderList(list, err) })
			// If any refresh was requested during this fetch, do exactly one more
			// so the last event's state is never left unrendered.
			if !r.dirty.CompareAndSwap(true, false) {
				return
			}
		}
	}()
}

func (r *Runs) renderList(list []types.Run, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if err != nil {
		r.table.Clear()
		r.setHeader()
		r.table.SetCell(1, 0, dataCell(fmt.Sprintf("ERROR: %v", err)))
		return
	}
	if r.filterID != "" {
		filtered := list[:0]
		for _, run := range list {
			if run.ConnectorID == r.filterID {
				filtered = append(filtered, run)
			}
		}
		list = filtered
	}
	r.items = list
	r.applySort()
	r.render()
}

func (r *Runs) applySort() {
	idx, desc, sorted := r.sort.Active()
	if !sorted {
		// Default: newest run on top (rehearsal feedback #6).
		sort.SliceStable(r.items, func(i, j int) bool {
			return r.items[i].StartedAt.After(r.items[j].StartedAt)
		})
		return
	}
	sort.SliceStable(r.items, func(i, j int) bool {
		// Descending swaps the OPERANDS, never negates the result — see sources.go.
		a, b := r.items[i], r.items[j]
		if desc {
			a, b = b, a
		}
		switch idx {
		case 0:
			return a.ConnectorLabel() < b.ConnectorLabel()
		case 1:
			return a.Status < b.Status
		case 2:
			return a.StartedAt.Before(b.StartedAt)
		case 3:
			return durationOf(a) < durationOf(b)
		}
		return false
	})
}

// startedLabel renders a run's start in LOCAL time: today shows the time only,
// an older run prefixes the month-day so runs across days are distinguishable
// (rehearsal feedback #6 — UTC time-only was unreadable).
func startedLabel(started, now time.Time) string {
	lt := started.In(now.Location())
	if lt.Year() == now.Year() && lt.YearDay() == now.YearDay() {
		return lt.Format("15:04:05")
	}
	return lt.Format("01-02 15:04")
}

func (r *Runs) render() {
	r.table.Clear()
	r.setHeader()
	now := time.Now()
	for i, run := range r.items {
		row := i + 1
		duration := "—"
		if run.EndedAt != nil {
			duration = run.EndedAt.Sub(run.StartedAt).Truncate(time.Millisecond).String()
		}
		r.table.SetCell(row, 0, dataCell(run.ConnectorLabel()).SetExpansion(1))
		r.table.SetCell(row, 1, statusCell(run.Status, statusFor(run.Status)))
		r.table.SetCell(row, 2, tview.NewTableCell(startedLabel(run.StartedAt, now)).SetExpansion(1))
		r.table.SetCell(row, 3, tview.NewTableCell(duration).SetExpansion(1))
		r.table.SetCell(row, 4, tview.NewTableCell(fmt.Sprintf("%d/%d/%d", run.RecordsIn, run.RecordsOut, run.RecordsSkipped)).SetExpansion(1))
		r.table.SetCell(row, 5, dataCell(run.TriggeredBy).SetExpansion(1))
	}
}

func durationOf(run types.Run) time.Duration {
	if run.EndedAt == nil {
		return 0
	}
	return run.EndedAt.Sub(run.StartedAt)
}

func (r *Runs) selected() (types.Run, bool) {
	row, _ := r.table.GetSelection()
	if row < 1 || row-1 >= len(r.items) {
		return types.Run{}, false
	}
	return r.items[row-1], true
}

func (r *Runs) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Rune() == 's' {
		r.sort.Next()
		r.Refresh()
		return nil
	}
	if ev.Rune() == 'r' {
		r.Refresh()
		return nil
	}
	return ev
}

// Describable

func (r *Runs) SelectedDescribe() (string, string, bool) {
	run, ok := r.selected()
	if !ok {
		return "", "", false
	}
	esc := tview.Escape
	var b strings.Builder
	fmt.Fprintf(&b, "id:              %s\n", esc(run.ID))
	fmt.Fprintf(&b, "connector:       %s\n", esc(run.ConnectorID))
	fmt.Fprintf(&b, "status:          %s %s\n", statusFor(run.Status).glyph(), esc(run.Status))
	fmt.Fprintf(&b, "trigger:         %s\n", esc(run.TriggeredBy))
	fmt.Fprintf(&b, "started:         %s\n", run.StartedAt.Format(time.RFC3339))
	if run.EndedAt != nil {
		fmt.Fprintf(&b, "finished:        %s\n", run.EndedAt.Format(time.RFC3339))
		fmt.Fprintf(&b, "duration:        %s\n", run.EndedAt.Sub(run.StartedAt).Truncate(time.Millisecond))
	}
	fmt.Fprintf(&b, "records_in:      %d\n", run.RecordsIn)
	fmt.Fprintf(&b, "records_out:     %d\n", run.RecordsOut)
	fmt.Fprintf(&b, "records_skipped: %d\n", run.RecordsSkipped)
	fmt.Fprintf(&b, "cell_errors:     %d\n", run.CellErrors)
	fmt.Fprintf(&b, "chunks:          %d\n", run.ChunkCount)
	if run.ErrorMessage != "" {
		// Last, and unmissable: a multi-line error must not sandwich the counters
		// above it (rehearsal — the cause was hiding below the fold).
		fmt.Fprintf(&b, "\nerror:\n%s\n", esc(run.ErrorMessage))
	}
	return shortRunID(run.ID), b.String(), true
}

func (r *Runs) OnSelectionChange(cb func()) { r.onSelect = cb }

// Yankable

func (r *Runs) SelectedYank() (string, bool) {
	run, ok := r.selected()
	if !ok {
		return "", false
	}
	data, err := json.MarshalIndent(run, "", "  ")
	if err != nil {
		return "", false
	}
	return string(data), true
}

func shortRunID(s string) string {
	if len(s) > 8 {
		return s[:8]
	}
	return s
}
