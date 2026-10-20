package resources

import (
	"fmt"
	"strings"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

type Endpoints struct {
	client    *client.Client
	app       *tview.Application
	offLoop   offLoop
	inFlight  inFlight // the guard on ^P, C, Del, T and P (#1147)
	table     *tview.Table
	endpoints []types.Endpoint
	all       []types.Endpoint // every endpoint the hub listed; endpoints is what the / filter shows
	textFilter
	mu          sync.Mutex
	connectorID string
	onEnter     func(endpointID, name string)
	onSelect    func()
	toast       func(string)
	onError     func(string)
	confirm     ConfirmAs
	ask         Prompt
	show        func(title, body string)
	isFront     func() bool // true while :endpoints is in front of a live app; gates Del's question (#1183)
}

// SetFront is wired by the App: Del's dry run answers off the loop, and its question opens only
// while the page is still in front.
func (p *Endpoints) SetFront(fn func() bool) { p.isFront = fn }

// SetAsk and SetShow are wired by the App for P (the path to probe) and T/P's verdicts.
func (p *Endpoints) SetAsk(fn Prompt)                    { p.ask = fn }
func (p *Endpoints) SetShow(fn func(title, body string)) { p.show = fn }

func NewEndpoints(app *tview.Application, c *client.Client, toast, onError func(string)) *Endpoints {
	t := newListTable()
	t.SetTitle(" :endpoints ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	p := &Endpoints{client: c, app: app, table: t, toast: toast, onError: onError}
	t.SetInputCapture(p.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if p.onSelect != nil {
			p.onSelect()
		}
	})
	return p
}

func (p *Endpoints) View() tview.Primitive { return p.table }

func (p *Endpoints) Hints() string {
	return "Enter: → runs · T: test · P: probe a path · ^P: enable/disable · C: reset cursor · Del: purge rows · d: describe · y: yank · r: refresh · Esc: ← sources"
}

// SetFilter narrows the page to one connector's endpoints; name, when known, titles it (#1175).
func (p *Endpoints) SetFilter(connectorID, name string) {
	p.mu.Lock()
	p.connectorID = connectorID
	p.mu.Unlock()
	p.table.SetTitle(drillTitle(":endpoints", "", connectorID, name))
}

func (p *Endpoints) SetOnEnter(cb func(endpointID, name string)) { p.onEnter = cb }

// SetConfirm is wired by the App: reset and purge ask through it, and do nothing without one.
func (p *Endpoints) SetConfirm(fn ConfirmAs) { p.confirm = fn }

// Refresh lists off the event loop (offLoop): the live ticker calls it while the page is in front
// (#1109).
func (p *Endpoints) Refresh() {
	p.offLoop.run(p.app, func() func() {
		connectorID := p.filter()
		if connectorID == "" {
			return func() { p.renderList("", nil, nil) }
		}
		eps, err := p.client.Endpoints(connectorID)
		return func() { p.renderList(connectorID, eps, err) }
	})
}

// filter reads the drill-down under the lock: Refresh carries it into the fetch goroutine.
func (p *Endpoints) filter() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.connectorID
}

func (p *Endpoints) renderList(connectorID string, eps []types.Endpoint, err error) {
	if connectorID == "" || err != nil {
		p.endpoints = nil
		p.table.Clear()
		p.renderHeader()
		switch {
		case err != nil:
			p.table.SetCell(1, 0, dataCell(fmt.Sprintf("error: %v", err)))
		case connectorID == "": // opened from the palette: nothing to list yet (#1175)
			p.table.SetCell(1, 0, tview.NewTableCell("Enter on a :sources row lists its endpoints.").SetSelectable(false))
		}
		return
	}
	prev := selectedKey(p.table, p.endpoints, endpointKey)
	p.all = eps
	p.endpoints = filterRows(eps, p.q, func(e types.Endpoint) []string {
		return []string{e.Name, e.Method, e.Path, e.Schedule, e.LastRunStatus}
	})
	p.render()
	if !reselect(p.table, p.endpoints, endpointKey, prev) && p.table.GetRowCount() > 1 {
		p.table.Select(1, 0) // a new drill-down, or the endpoint is gone: start at the top
	}
}

func (p *Endpoints) renderHeader() {
	headers := []string{"NAME", "METHOD", "PATH", "SCHEDULE", "LAST_RUN", "STATUS", "ENABLED"}
	for c, h := range headers {
		p.table.SetCell(0, c, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1).SetTextColor(tcell.ColorYellow))
	}
}

func (p *Endpoints) renderRows() [][]string {
	rows := make([][]string, 0, len(p.endpoints))
	for _, e := range p.endpoints {
		// Minute precision, matching every other table cell (runs, connector, migrations,
		// scopes) — endpoints was the one table still at second precision, for no reason
		// tied to this column's meaning.
		lastRun := output.HumanTime(e.LastRunAt)
		enabled := "yes"
		if !e.Enabled {
			enabled = "no"
		}
		rows = append(rows, []string{e.Name, e.Method, e.Path, e.Schedule, lastRun, e.LastRunStatus, enabled})
	}
	return rows
}

func (p *Endpoints) render() {
	p.table.Clear()
	p.renderHeader()
	for r, row := range p.renderRows() {
		for c, v := range row {
			cell := dataCell(v).SetExpansion(1)
			if c == 5 && style.ColorsEnabled() { // STATUS column
				cell.SetTextColor(endpointStatusColor(v))
			}
			p.table.SetCell(r+1, c, cell)
		}
	}
}

// SetTextFilter narrows the table to the endpoints holding q (#1120); "" shows them all.
func (p *Endpoints) SetTextFilter(q string) { p.q = q; p.renderList(p.filter(), p.all, nil) }

func endpointKey(e types.Endpoint) string { return e.ID }

func endpointStatusColor(status string) tcell.Color {
	switch runStatusKind(status) {
	case KindCompleted:
		return tcell.NewRGBColor(0x22, 0xC5, 0x5E) // green
	case KindFailed:
		return tcell.NewRGBColor(0xEF, 0x44, 0x44) // red
	case KindQueued:
		return tcell.NewRGBColor(0x38, 0xBD, 0xF8) // sky — accepted, not started
	case KindRunning:
		return tcell.NewRGBColor(0xFF, 0x8C, 0x00) // amber
	case KindPartial:
		return tcell.NewRGBColor(0xEA, 0xB3, 0x08) // yellow — finished, but not whole
	case KindCancelled:
		return tcell.NewRGBColor(0x6B, 0x72, 0x80) // gray — didn't finish
	}
	return tcell.ColorDefault
}

func (p *Endpoints) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	switch ev.Key() {
	case tcell.KeyEnter:
		if p.onEnter != nil {
			if ep, ok := p.selectedItem(); ok {
				p.onEnter(ep.ID, ep.Name)
			}
		}
		return nil
	case tcell.KeyCtrlP:
		p.toggle()
		return nil
	case tcell.KeyDelete:
		p.purge()
		return nil
	}
	switch ev.Rune() {
	case 'r':
		p.Refresh()
		return nil
	case 'C':
		p.resetCursor()
		return nil
	case 'T':
		p.preflight()
		return nil
	case 'P':
		p.probe()
		return nil
	}
	return ev
}

// The controls of lm endpoint enable|disable|reset-cursor|purge (#1112). Each captures the
// endpoint when the key is pressed, so a live refresh in between cannot redirect it.

func (p *Endpoints) toggle() {
	ep, ok := p.selectedItem()
	if !ok {
		return
	}
	connectorID := p.filter()
	p.inFlight.background(p.app, "^P", p.toast, func() func() {
		err := p.client.EndpointEnable(connectorID, ep.ID, !ep.Enabled)
		return func() {
			if err != nil {
				say(p.onError, fmt.Sprintf("enable/disable: %v", err))
				return
			}
			verb := "enabled"
			if ep.Enabled {
				verb = "disabled"
			}
			say(p.toast, fmt.Sprintf("%s: %s", verb, ep.Name))
			p.Refresh()
		}
	})
}

func (p *Endpoints) resetCursor() {
	ep, ok := p.selectedItem()
	if !ok || p.confirm == nil {
		return
	}
	if p.inFlight.busy("C", p.toast) { // no question for a press that would be dropped
		return
	}
	connectorID := p.filter()
	p.confirm(fmt.Sprintf("Reset the cursor of endpoint %q? Its next run re-reads from the start.", ep.Name), "Reset", func() {
		p.inFlight.background(p.app, "C", p.toast, func() func() {
			err := p.client.EndpointResetCursor(connectorID, ep.ID)
			return func() {
				if err != nil {
					say(p.onError, fmt.Sprintf("reset cursor: %v", err))
					return
				}
				say(p.toast, "cursor reset: "+ep.Name+" — the next run re-reads from the start")
			}
		})
	})
}

func (p *Endpoints) purge() {
	ep, ok := p.selectedItem()
	if !ok || p.confirm == nil {
		return
	}
	connectorID := p.filter()
	// The dry run first, as lm endpoint purge (#1183): a refusal arrives before any question, and
	// the question can say what it is about to delete.
	p.inFlight.background(p.app, "Del", p.toast, func() func() {
		n, err := p.client.EndpointPurgeDryRun(connectorID, ep.ID)
		return func() {
			switch {
			case p.isFront != nil && !p.isFront(): // left — or the whole hub left (:context)
				say(p.toast, "Del: :endpoints was left before the hub answered — nothing purged")
			case err != nil:
				say(p.onError, fmt.Sprintf("purge: %v", err))
			case n == 0:
				say(p.toast, "nothing to purge: "+ep.Name+" has no rows")
			default:
				p.confirmPurge(connectorID, ep, n)
			}
		}
	})
}

func (p *Endpoints) confirmPurge(connectorID string, ep types.Endpoint, n int) {
	p.confirm(fmt.Sprintf("Purge %s endpoint %q has landed?", output.RowCount(n), ep.Name), "Purge", func() {
		say(p.toast, "purging "+ep.Name+"…")
		p.inFlight.background(p.app, "Del", p.toast, func() func() {
			n, err := p.client.EndpointPurge(connectorID, ep.ID)
			return func() {
				if err != nil {
					say(p.onError, fmt.Sprintf("purge: %v", err))
					return
				}
				say(p.toast, fmt.Sprintf("purged: %s of %s", output.RowCount(n), ep.Name))
			}
		})
	})
}

func (p *Endpoints) selectedItem() (types.Endpoint, bool) {
	row, _ := p.table.GetSelection()
	if row < 1 || row-1 >= len(p.endpoints) {
		return types.Endpoint{}, false
	}
	return p.endpoints[row-1], true
}

// Describable

func (p *Endpoints) SelectedDescribe() (string, string, bool) {
	ep, ok := p.selectedItem()
	if !ok {
		return "", "", false
	}
	lastRun := output.HumanTimeSeconds(ep.LastRunAt)
	enabled := "false"
	if ep.Enabled {
		enabled = "true"
	}
	esc := tview.Escape
	body := fmt.Sprintf("id:              %s\nconnector:       %s\nname:            %s\nmethod:          %s\npath:            %s\nschedule:        %s\nenabled:         %s\nlast_run_at:     %s\nlast_run_status: %s\n",
		esc(ep.ID), esc(ep.ConnectorID), esc(ep.Name), esc(ep.Method), esc(ep.Path),
		esc(ep.Schedule), enabled, lastRun, esc(ep.LastRunStatus))
	return "endpoint/" + ep.Name, body, true
}

func (p *Endpoints) OnSelectionChange(cb func()) { p.onSelect = cb }

// Yankable

func (p *Endpoints) SelectedYank() (string, bool) {
	ep, ok := p.selectedItem()
	if !ok {
		return "", false
	}
	return strings.TrimSpace(fmt.Sprintf("- name: %s\n  method: %s\n  path: %s\n  schedule: %s\n", ep.Name, ep.Method, ep.Path, ep.Schedule)), true
}

// preflight is T: the one request a run of the endpoint would send, nothing written (#1117).
func (p *Endpoints) preflight() {
	ep, ok := p.selectedItem()
	if !ok || p.show == nil {
		return
	}
	connectorID := p.filter()
	p.inFlight.background(p.app, "T", p.toast, func() func() {
		v, err := p.client.EndpointTest(connectorID, ep.ID)
		return func() {
			if err != nil {
				say(p.onError, fmt.Sprintf("test %s: %v", ep.Name, err))
				return
			}
			verdict := "[green]ok[white]"
			if !v.OK {
				verdict = "[red]refused[white]"
			}
			body := fmt.Sprintf("%s  %s %s\nstatus: %d · items: %d\n", verdict, tview.Escape(v.Method), tview.Escape(v.URL), v.Status, v.Items)
			if v.Detail != "" {
				body += "\n" + tview.Escape(v.Detail) + "\n"
			}
			if v.Sample != "" {
				body += "\n── sample ──\n" + tview.Escape(v.Sample) + "\n"
			}
			p.show(" pre-flight of "+tview.Escape(ep.Name)+" — nothing written ", body)
		}
	})
}

// probe is P: sample one path of the connector's API and draft its endpoint fragment (#1117).
func (p *Endpoints) probe() {
	connectorID := p.filter()
	if p.ask == nil || p.show == nil || connectorID == "" || p.inFlight.busy("P", p.toast) {
		return
	}
	p.ask("Path to probe (e.g. /v1/customers): ", false, func(path string) {
		path = strings.TrimSpace(path)
		if path == "" {
			return
		}
		p.inFlight.background(p.app, "P", p.toast, func() func() {
			draft, err := p.client.RestProbe(connectorID, path)
			return func() {
				if err != nil {
					say(p.onError, fmt.Sprintf("probe %s: %v", path, err))
					return
				}
				p.show(" endpoint draft for "+tview.Escape(path)+" — paste it under spec.endpoints (e on :sources) ", tview.Escape(string(draft)))
			}
		})
	})
}
