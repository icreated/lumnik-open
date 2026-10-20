package resources

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// exportsIntegratorOnly is what an lm_user is told: /api/exports/* answers 403 to anyone without
// lm_integrator, and an empty table would read as "no exports declared".
const exportsIntegratorOnly = "Exports are integrator-only: /api/exports/* needs the lm_integrator role, which this login does not hold."

// exportRow is one declared export and its newest run, nil when it never ran — or when the hub
// would not say (lastUnknown).
type exportRow struct {
	client.ExportSummary
	last        *client.ExportRun
	lastUnknown bool
}

// lastRunFanOut bounds the per-export last-run reads in flight at once.
const lastRunFanOut = 8

// Exports is :exports — the declared kind:Export manifests and their runs (#1131). Not built on
// the Manifests page: its gestures are a run ledger (Enter: the runs, R: run now, ^R: retry a
// failed run), not the stored-manifest doors Manifests offers, and the hub has no export delete
// or enable door for the doors Manifests does share.
type Exports struct {
	app    *tview.Application
	client *client.Client
	table  *tview.Table
	all    []exportRow
	items  []exportRow // what the / filter shows
	textFilter
	offLoop  offLoop
	inFlight inFlight // the guard on e, R and ^R (#1147)
	listErr  error
	export   string // "" lists the exports; a name shows its runs
	allRuns  []client.ExportRun
	runs     []client.ExportRun // what the / filter shows of allRuns
	toast    func(string)
	onError  func(string)
	onEdit   func(kind string, yaml []byte)
	onHints  func(string)
	onSelect func()
}

func NewExports(app *tview.Application, c *client.Client, toast, onError func(string)) *Exports {
	t := newListTable()
	t.SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	p := &Exports{app: app, client: c, table: t, toast: toast, onError: onError}
	t.SetInputCapture(p.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if p.onSelect != nil {
			p.onSelect()
		}
	})
	return p
}

// SetOnEdit is wired by the App: e re-opens the stored manifest in :new.
func (p *Exports) SetOnEdit(fn func(kind string, yaml []byte)) { p.onEdit = fn }

// SetOnHints is wired by the App: Enter changes the gestures the footer must offer.
func (p *Exports) SetOnHints(fn func(string)) { p.onHints = fn }
func (p *Exports) View() tview.Primitive      { return p.table }

func (p *Exports) Hints() string {
	if p.export != "" {
		return "^R: retry a failed run  R: run now  d: describe  r: refresh  Esc: back to :exports"
	}
	return "Enter: its runs  R: run now  e: edit the stored manifest  d: describe  r: refresh"
}

// Drillable — Enter narrows the page to one export's runs; Esc comes back to the list it still
// holds, at once, and refreshes it behind.
func (p *Exports) FilterActive() bool { return p.export != "" }
func (p *Exports) ClearFilter() {
	p.export, p.allRuns = "", nil
	p.render()
	p.Refresh()
}

func (p *Exports) Refresh() {
	p.offLoop.run(p.app, func() func() {
		if name := p.export; name != "" {
			runs, err := p.client.ExportRuns(context.Background(), name, 50)
			return func() {
				if name != p.export {
					return // Esc landed meanwhile: these runs belong to a page no longer shown
				}
				if err != nil {
					say(p.onError, fmt.Sprintf("runs of %s: %v", name, exportErr(err)))
					return
				}
				prev := selectedKey(p.table, p.runs, exportRunKey)
				p.allRuns = runs
				p.render()
				reselect(p.table, p.runs, exportRunKey, prev)
			}
		}
		rows, err := p.listWithLastRun()
		return func() {
			if p.export != "" {
				return
			}
			prev := selectedKey(p.table, p.items, exportRowKey)
			p.all, p.listErr = rows, err
			p.render()
			reselect(p.table, p.items, exportRowKey, prev)
		}
	})
}

// listWithLastRun reads the exports, then each one's newest run: the hub's list carries no run
// status, so the LAST RUN column costs one runs?limit=1 per export (off the loop), lastRunFanOut
// at a time.
func (p *Exports) listWithLastRun() ([]exportRow, error) {
	list, err := p.client.ExportList()
	if err != nil {
		return nil, err
	}
	rows := make([]exportRow, len(list))
	var wg sync.WaitGroup
	slots := make(chan struct{}, lastRunFanOut)
	for i, s := range list {
		rows[i].ExportSummary = s
		slots <- struct{}{} // taken before go: at most lastRunFanOut goroutines exist, not one per export
		wg.Add(1)
		go func(r *exportRow) {
			defer wg.Done()
			defer func() { <-slots }()
			runs, err := p.client.ExportRuns(context.Background(), r.Name, 1)
			if err != nil {
				r.lastUnknown = true
			} else if len(runs) > 0 {
				r.last = &runs[0]
			}
		}(&rows[i])
	}
	wg.Wait()
	return rows, nil
}

func exportRowKey(r exportRow) string        { return r.Name }
func exportRunKey(r client.ExportRun) string { return r.ID }

func exportCells(r exportRow) []string {
	return []string{r.Name, r.Entity, exportSchedule(r), r.DestinationKind, exportLastRun(r)}
}

func exportSchedule(r exportRow) string {
	if r.Schedule == "" {
		return "manual"
	}
	return r.Schedule
}

func exportLastRun(r exportRow) string {
	switch {
	case r.lastUnknown:
		return "unknown"
	case r.last != nil:
		return strings.TrimSpace(r.last.Status + " " + runAge(r.last))
	}
	return "never ran"
}

func exportRunCells(r client.ExportRun) []string {
	rows, failure := "", ""
	if r.RowCount != nil {
		rows = fmt.Sprint(*r.RowCount)
	}
	if r.FailureCode != "" {
		failure = r.FailureCode + ": " + r.FailureReason
	}
	return []string{shortRunID(r.ID), r.Status, r.Trigger, fmt.Sprint(r.Attempt), runAge(&r), rows, failure}
}

// runAge is how long ago the run ended, or started while it has not.
func runAge(r *client.ExportRun) string {
	at := r.EndedAt
	if at == nil {
		at = r.StartedAt
	}
	if at == nil {
		return ""
	}
	return humanizeSince(time.Since(*at))
}

func (p *Exports) render() {
	if p.export != "" {
		p.table.SetTitle(" :exports › " + tview.Escape(p.export) + " runs ")
		headerRow(p.table, []string{"run", "status", "trigger", "attempt", "when", "rows", "failure"})
		p.runs = filterRows(p.allRuns, p.q, exportRunCells)
		switch {
		case p.allRuns == nil: // Enter just landed: the runs are on their way
			p.table.SetCell(1, 0, tview.NewTableCell("Loading the runs…").SetSelectable(false))
		case len(p.allRuns) == 0:
			p.table.SetCell(1, 0, tview.NewTableCell("No run yet — R runs it now.").SetSelectable(false))
		}
		for r, run := range p.runs {
			for c, v := range exportRunCells(run) {
				p.table.SetCell(r+1, c, dataCell(v))
			}
		}
		return
	}
	p.table.SetTitle(" :exports ")
	p.items = filterRows(p.all, p.q, exportCells)
	headerRow(p.table, []string{"name", "entity", "schedule", "destination", "last run"})
	switch {
	case p.listErr != nil:
		p.table.SetCell(1, 0, dataCell(exportErr(p.listErr).Error()).SetSelectable(false))
	case len(p.all) == 0:
		p.table.SetCell(1, 0, tview.NewTableCell("None declared — create one from :new.").SetSelectable(false))
	}
	for r, it := range p.items {
		for c, v := range exportCells(it) {
			p.table.SetCell(r+1, c, dataCell(v))
		}
	}
}

// exportErr says integrator-only for a 403 — the hub's refusal for an lm_user — and passes every
// other answer through.
func exportErr(err error) error {
	var api *client.APIError
	if errors.As(err, &api) && api.Status == 403 {
		return errors.New(exportsIntegratorOnly)
	}
	return err
}

// SetTextFilter narrows the list (or the runs shown) to the rows holding q (#1120).
func (p *Exports) SetTextFilter(q string) { p.q = q; p.render() }

func (p *Exports) selectedExport() (exportRow, bool) {
	row, _ := p.table.GetSelection()
	if p.export != "" || row < 1 || row-1 >= len(p.items) {
		return exportRow{}, false
	}
	return p.items[row-1], true
}

func (p *Exports) selectedRun() (client.ExportRun, bool) {
	row, _ := p.table.GetSelection()
	if p.export == "" || row < 1 || row-1 >= len(p.runs) {
		return client.ExportRun{}, false
	}
	return p.runs[row-1], true
}

func (p *Exports) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	switch {
	case ev.Key() == tcell.KeyEnter:
		if it, ok := p.selectedExport(); ok {
			p.export, p.allRuns = it.Name, nil
			p.render()
			say(p.onHints, p.Hints())
			p.Refresh()
		}
		return nil
	case ev.Rune() == 'R':
		name := p.export
		if it, ok := p.selectedExport(); ok {
			name = it.Name
		}
		if name != "" {
			p.start("R", "run "+name, func() (string, error) { return p.client.ExportRunStart(context.Background(), name) })
		}
		return nil
	case ev.Key() == tcell.KeyCtrlR:
		run, ok := p.selectedRun()
		if !ok {
			return nil
		}
		if run.Status != client.ExportRunFailed {
			say(p.toast, "only a Failed run can be retried — this one is "+run.Status)
			return nil
		}
		name := p.export
		p.start("^R", "retry "+shortRunID(run.ID), func() (string, error) {
			return p.client.ExportRunRetry(context.Background(), name, run.ID)
		})
		return nil
	case ev.Rune() == 'e':
		if it, ok := p.selectedExport(); ok && p.onEdit != nil {
			p.inFlight.background(p.app, "e", p.toast, func() func() {
				yaml, err := p.client.ExportManifest(it.Name)
				return func() {
					if err != nil {
						say(p.onError, fmt.Sprintf("%s: %v", it.Name, exportErr(err)))
						return
					}
					p.onEdit("Export", yaml)
				}
			})
		}
		return nil
	}
	return ev
}

// start asks the hub for a run (or a retry) off the loop. The hub answers once the run is CLAIMED,
// not delivered: the toast says so, and the runs list shows how it ends. A second press of key is
// dropped while the first is out.
func (p *Exports) start(key, what string, call func() (string, error)) {
	p.inFlight.background(p.app, key, p.toast, func() func() {
		id, err := call()
		return func() {
			if err != nil {
				if inFlight, busy := client.InFlightExportRun(err); busy && inFlight != "" {
					say(p.onError, fmt.Sprintf("%s: a run is already in flight (%s)", what, shortRunID(inFlight)))
					return
				}
				say(p.onError, fmt.Sprintf("%s: %v", what, exportErr(err)))
				return
			}
			say(p.toast, fmt.Sprintf("✓ %s: run %s claimed — r refreshes its status", what, shortRunID(id)))
			p.Refresh()
		}
	})
}

// Describable — the export as listed, or the run in full (its receipt or its failure).

func (p *Exports) SelectedDescribe() (string, string, bool) {
	esc := tview.Escape
	var b strings.Builder
	if run, ok := p.selectedRun(); ok {
		fmt.Fprintf(&b, "run:      %s\nstatus:   %s\ntrigger:  %s\nattempt:  %d\n",
			esc(run.ID), esc(run.Status), esc(run.Trigger), run.Attempt)
		if run.RetryOf != "" {
			fmt.Fprintf(&b, "retry of: %s\n", esc(run.RetryOf))
		}
		if run.Location != "" {
			fmt.Fprintf(&b, "location: %s\nsha256:   %s\n", esc(run.Location), esc(run.Sha256))
		}
		if run.FailureCode != "" {
			fmt.Fprintf(&b, "\n── failure ──\n%s: %s\n", esc(run.FailureCode), esc(run.FailureReason))
		}
		return run.ID, b.String(), true
	}
	it, ok := p.selectedExport()
	if !ok {
		return "", "", false
	}
	fmt.Fprintf(&b, "name:        %s\nscope:       %s\nentity:      %s\nschedule:    %s\ndestination: %s\nlast run:    %s\n",
		esc(it.Name), esc(it.Scope), esc(it.Entity), esc(exportSchedule(it)), esc(it.DestinationKind), esc(exportLastRun(it)))
	return it.Name, b.String(), true
}

func (p *Exports) OnSelectionChange(cb func()) { p.onSelect = cb }
