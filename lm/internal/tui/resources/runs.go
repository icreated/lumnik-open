package resources

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
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
	runStatusQueued             // ◌ sky
	runStatusRunning            // ▶ amber
	runStatusPartial            // ◐ yellow
	runStatusDisabled           // ─ grey
)

func (s runStatus) color() tcell.Color {
	switch s {
	case runStatusSuccess:
		return tcell.NewRGBColor(0x22, 0xC5, 0x5E)
	case runStatusFailure:
		return tcell.NewRGBColor(0xEF, 0x44, 0x44)
	case runStatusQueued:
		return tcell.NewRGBColor(0x38, 0xBD, 0xF8) // sky — accepted, not started
	case runStatusRunning:
		return tcell.NewRGBColor(0xFF, 0x8C, 0x00)
	case runStatusPartial:
		return tcell.NewRGBColor(0xEA, 0xB3, 0x08) // yellow — finished, but not whole
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
	case runStatusQueued:
		return "◌"
	case runStatusRunning:
		return "▶"
	case runStatusPartial:
		return "◐"
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
	case KindQueued:
		return runStatusQueued
	case KindRunning:
		return runStatusRunning
	case KindPartial:
		return runStatusPartial
	case KindCancelled:
		return runStatusDisabled
	}
	return runStatusUnknown
}

// runSortColumns are the sortable columns of the :runs table, in table order. One list, read by
// the page and by every test that builds a bare page: it was hand-written in eight places, and a
// ColumnSort shorter than the header row it labels renders a blank title over real data — so
// adding a column meant getting eight copies right, or shipping that.
var runSortColumns = []string{"CONNECTOR", "ENDPOINT", "STATUS", "STARTED", "DURATION"}

// The rendered cell of each column. The first five are runSortColumns in order — that identity is
// what lets a sort index address a cell — and RECORDS/TRIGGER are the un-sortable tail setHeader
// appends. Naming them keeps the sort switch and the render calls on ONE ladder: inserting a
// column is a constant, a case and a SetCell, not a renumbering of two lists that fails silently
// when only one is done.
const (
	colConnector = iota
	colEndpoint
	colStatus
	colStarted
	colDuration
	colRecords
	colTrigger
	colEvaluated // only when the hub answered for some run (#1351) — see types.AnyEvaluated
)

// ── Runs resource page ──

type Runs struct {
	table    *tview.Table
	client   *client.Client
	items    []types.Run
	all      []types.Run // every run the hub listed; items is what the / filter shows
	textFilter
	mu       sync.Mutex
	app      *tview.Application
	offLoop  offLoop
	inFlight inFlight // the guard on c (#1147)
	sort     *style.ColumnSort
	// The drill-downs, at most one of them set: :sources leads here by connector, :endpoints by
	// endpoint. They are two questions, not two halves of one — an endpoint already names its
	// connector — so setting either clears the other.
	filterID         string // connector_id filter, empty = no filter
	filterEndpointID string // endpoint_id filter, empty = no filter
	onSelect func()
	toast    func(string)
	onDead   func(runID string) // wired by App: D → :dlq filtered on the run
}

func NewRuns(app *tview.Application, c *client.Client, toast func(string)) *Runs {
	t := newListTable()
	t.SetTitle(" :runs ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	r := &Runs{
		table:  t,
		client: c,
		app:    app,
		toast:  toast,
		sort:   style.NewColumnSort(runSortColumns),
	}
	r.setHeader(false)
	t.SetInputCapture(r.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if r.onSelect != nil {
			r.onSelect()
		}
	})
	return r
}

func (r *Runs) View() tview.Primitive { return r.table }

// SetOnDeadLetters is wired by the App so D opens the selected run's dead letters (#1111).
func (r *Runs) SetOnDeadLetters(fn func(runID string)) { r.onDead = fn }

func (r *Runs) Hints() string {
	base := "d: describe  D: dead letters  y: yank  s: sort  c: cancel  r: refresh"
	if r.FilterActive() {
		base = "Esc: clear filter  " + base
	}
	return base
}

func (r *Runs) setHeader(showEvaluated bool) {
	headers := make([]string, 0, len(runSortColumns)+3)
	for i := range runSortColumns {
		headers = append(headers, r.sort.HeaderLabel(i))
	}
	headers = append(headers, "RECORDS", "TRIGGER")
	if showEvaluated {
		headers = append(headers, "EVALUATED")
	}
	for i, h := range headers {
		r.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1))
	}
}

// SetFilter restricts the listing to runs of a single connector, titled by name when known. Empty = all.
func (r *Runs) SetFilter(connectorID, name string) { r.setFilters(connectorID, "", "connector", name) }

// SetEndpointFilter restricts the listing to runs of a single ENDPOINT — the question the
// :endpoints drill-down asks. A connector fans out over its endpoints and writes one run row per
// endpoint, so the connector filter cannot answer it: this page used to receive an endpoint id
// through SetFilter and compare it against each run's connector id, which nothing satisfies.
func (r *Runs) SetEndpointFilter(endpointID, name string) { r.setFilters("", endpointID, "endpoint", name) }

func (r *Runs) setFilters(connectorID, endpointID, label, name string) {
	r.mu.Lock()
	r.filterID = connectorID
	r.filterEndpointID = endpointID
	r.mu.Unlock()
	r.table.SetTitle(drillTitle(":runs", label, connectorID+endpointID, name))
	r.Refresh()
}

// FilterActive returns true if either drill-down is in force.
func (r *Runs) FilterActive() bool {
	connectorID, endpointID := r.filters()
	return connectorID != "" || endpointID != ""
}

// filters reads the drill-downs under the lock: Refresh below carries them into the fetch
// goroutine, so they are no longer UI-thread-only fields.
func (r *Runs) filters() (connectorID, endpointID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.filterID, r.filterEndpointID
}

// ClearFilter wipes both drill-downs and refreshes.
func (r *Runs) ClearFilter() { r.setFilters("", "", "", "") }

// Refresh fetches OFF the UI thread — a blocking RunList on the main thread,
// re-triggered by every live SSE event, starved input and froze
// the TUI under an event burst (rehearsal carnet #9). offLoop coalesces a burst
// into one in-flight request; the render lands via QueueUpdate.
func (r *Runs) Refresh() {
	r.offLoop.run(r.app, func() func() {
		// Re-read the drill-downs each round: one that lands mid-flight sets the dirty flag,
		// and the extra pass it buys must ask the question the user is now asking.
		connectorID, endpointID := r.filters()
		list, err := r.client.RunList(connectorID, endpointID, client.RunsPageCap)
		return func() { r.renderList(list, err) }
	})
}

func (r *Runs) renderList(list []types.Run, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if err != nil {
		r.table.Clear()
		r.setHeader(false)
		r.table.SetCell(1, 0, dataCell(fmt.Sprintf("ERROR: %v", err)))
		return
	}
	prev := selectedKey(r.table, r.items, runKey)
	r.all = list
	r.items = filterRows(list, r.q, func(run types.Run) []string {
		return []string{run.ID, run.ConnectorLabel(), run.EndpointLabel(), run.Status, run.TriggeredBy}
	})
	r.applySort()
	r.render()
	reselect(r.table, r.items, runKey, prev)
}

// SetTextFilter narrows the table to the runs holding q (#1120); "" shows them all.
func (r *Runs) SetTextFilter(q string) {
	r.mu.Lock()
	all := r.all
	r.q = q
	r.mu.Unlock()
	r.renderList(all, nil)
}

func runKey(run types.Run) string { return run.ID }

func (r *Runs) applySort() {
	idx, desc, sorted := r.sort.Active()
	if !sorted {
		// Default: newest run on top (rehearsal feedback #6).
		sort.SliceStable(r.items, func(i, j int) bool {
			return newestFirst(r.items[i], r.items[j])
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
		case colConnector:
			return a.ConnectorLabel() < b.ConnectorLabel()
		case colEndpoint:
			return a.EndpointLabel() < b.EndpointLabel()
		case colStatus:
			return a.Status < b.Status
		case colStarted:
			return startedOf(a).Before(startedOf(b))
		case colDuration:
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
	showEvaluated := types.AnyEvaluated(r.items) // once: the header and every row follow the same answer
	r.setHeader(showEvaluated)
	now := time.Now()
	for i, run := range r.items {
		row := i + 1
		duration := "—"
		if run.EndedAt != nil && run.StartedAt != nil {
			duration = run.EndedAt.Sub(*run.StartedAt).Truncate(time.Millisecond).String()
		}
		r.table.SetCell(row, colConnector, dataCell(run.ConnectorLabel()).SetExpansion(1))
		r.table.SetCell(row, colEndpoint, dataCell(run.EndpointLabel()).SetExpansion(1))
		r.table.SetCell(row, colStatus, statusCell(run.Status, statusFor(run.Status)))
		r.table.SetCell(row, colStarted, tview.NewTableCell(startedCellLabel(run, now)).SetExpansion(1))
		r.table.SetCell(row, colDuration, tview.NewTableCell(duration).SetExpansion(1))
		r.table.SetCell(row, colRecords, tview.NewTableCell(fmt.Sprintf("%d/%d/%d", run.RecordsIn, run.RecordsOut, run.RecordsSkipped)).SetExpansion(1))
		r.table.SetCell(row, colTrigger, dataCell(run.TriggeredBy).SetExpansion(1))
		if showEvaluated {
			r.table.SetCell(row, colEvaluated, tview.NewTableCell(run.EvaluatedLabel()).SetExpansion(1))
		}
	}
}

func durationOf(run types.Run) time.Duration {
	if run.EndedAt == nil || run.StartedAt == nil {
		return 0
	}
	return run.EndedAt.Sub(*run.StartedAt)
}

// startedOf is a run's start as a time, zero while it is still queued (the hub reports it null).
func startedOf(r types.Run) time.Time {
	if r.StartedAt == nil {
		return time.Time{}
	}
	return *r.StartedAt
}

// startedCellLabel is the STARTED cell: the start in local time, or "queued" for a run that has none.
func startedCellLabel(r types.Run, now time.Time) string {
	if r.StartedAt == nil {
		return "queued"
	}
	return startedLabel(*r.StartedAt, now)
}

// newestFirst orders by start descending, a queued run (no start yet) before every started one.
func newestFirst(a, b types.Run) bool {
	if a.StartedAt == nil || b.StartedAt == nil {
		return a.StartedAt == nil && b.StartedAt != nil
	}
	return a.StartedAt.After(*b.StartedAt)
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
	if ev.Rune() == 'c' {
		r.cancel()
		return nil
	}
	if ev.Rune() == 'D' {
		if run, ok := r.selected(); ok && r.onDead != nil {
			r.onDead(run.ID)
		}
		return nil
	}
	return ev
}

// cancel requests cancellation of the selected run. A 202 only means the request
// was recorded — the chunk loop reads the flag at the next chunk boundary, so
// this never claims the run stopped (mirrors `lm run cancel`'s stderr wording).
// A refusal (already ended, not in flight, not found) carries the hub's own
// reason via APIError — toasted unchanged rather than replaced by a generic
// failure.
func (r *Runs) cancel() {
	run, ok := r.selected()
	if !ok {
		return
	}
	r.inFlight.background(r.app, "c", r.toast, func() func() {
		status, err := r.client.RunCancel(run.ID)
		return func() {
			switch {
			case err != nil:
				say(r.toast, err.Error())
			case status == "cancelled":
				say(r.toast, fmt.Sprintf("cancelled: %s %s — it had not started", run.ConnectorLabel(), shortRunID(run.ID)))
			default:
				say(r.toast, fmt.Sprintf("cancellation requested: %s %s — ends after the current chunk",
					run.ConnectorLabel(), shortRunID(run.ID)))
			}
		}
	})
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
	fmt.Fprintf(&b, "connector:       %s\n", esc(run.ConnectorLabel())) // the list's name for it (#1173)
	// The run read ONE endpoint of that connector; the counters below are that endpoint's, so
	// naming it is what makes them readable. Omitted, not blanked, for a run that has none.
	if label := run.EndpointLabel(); label != "" {
		fmt.Fprintf(&b, "endpoint:        %s\n", esc(label))
	}
	fmt.Fprintf(&b, "status:          %s %s\n", statusFor(run.Status).glyph(), esc(run.Status))
	if run.ErrorMessage != "" {
		// Why, right under the status and above the fold, as `lm run get` says it (#1173); a block
		// of its own, so a multi-line error cannot run into the lines below.
		fmt.Fprintf(&b, "error:\n%s\n\n", esc(run.ErrorMessage))
	}
	fmt.Fprintf(&b, "trigger:         %s\n", esc(run.TriggeredBy))
	if run.StartedAt == nil {
		fmt.Fprintf(&b, "started:         queued\n")
	} else {
		fmt.Fprintf(&b, "started:         %s\n", output.HumanTimeSeconds(run.StartedAt))
	}
	if run.EndedAt != nil {
		fmt.Fprintf(&b, "finished:        %s\n", output.HumanTimeSeconds(run.EndedAt))
		if run.StartedAt != nil {
			fmt.Fprintf(&b, "duration:        %s\n", run.EndedAt.Sub(*run.StartedAt).Truncate(time.Millisecond))
		}
	}
	fmt.Fprintf(&b, "records_in:      %d\n", run.RecordsIn)
	fmt.Fprintf(&b, "records_out:     %d\n", run.RecordsOut)
	fmt.Fprintf(&b, "records_skipped: %d\n", run.RecordsSkipped)
	fmt.Fprintf(&b, "dead_letters:    %d\n", run.DeadLetterCount)
	fmt.Fprintf(&b, "cell_errors:     %d\n", run.CellErrors)
	fmt.Fprintf(&b, "chunks:          %d\n", run.ChunkCount)
	for _, s := range run.Scripts { // #1430: which version of a Kotlin script produced the rows
		fmt.Fprintf(&b, "script:          %s  sha256 %s\n", esc(s.Name), esc(s.SHA256))
	}
	if run.Evaluated != nil {
		fmt.Fprintf(&b, "evaluated:       %s\n", run.EvaluatedLabel())
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
