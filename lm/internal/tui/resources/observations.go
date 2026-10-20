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

// Observations is the workflow tape: what has NOT moved, stillest first. The order is the
// server's promise (ORDER BY since ASC) — this page renders it, it never re-derives it.
type Observations struct {
	app      *tview.Application
	table    *tview.Table
	client   *client.Client
	items    []client.Observation
	offLoop  offLoop
	mu       sync.Mutex
	onError  func(string)
	onSelect func()
}

func NewObservations(app *tview.Application, c *client.Client, onError func(string)) *Observations {
	t := newListTable()
	t.SetTitle(" :observations ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	o := &Observations{app: app, table: t, client: c, onError: onError}
	o.setHeader()
	t.SetInputCapture(o.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if o.onSelect != nil {
			o.onSelect()
		}
	})
	return o
}

func (o *Observations) View() tview.Primitive { return o.table }

func (o *Observations) Hints() string {
	return "d: describe  y: yank  r: refresh"
}

func (o *Observations) setHeader() {
	for i, h := range []string{"WORKFLOW", "DOC", "STATE", "SINCE", "ALERTED"} {
		o.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1))
	}
}

// Refresh fetches the tape and its stats (`lm observations stats`, #1138) off the event loop. The
// stats ride in the title: a refused stats call leaves the tape shown and says why.
func (o *Observations) Refresh() {
	o.offLoop.run(o.app, func() func() {
		list, err := o.client.ObservationList(200)
		stats, statsErr := o.client.ObservationStats()
		return func() {
			o.mu.Lock()
			defer o.mu.Unlock()
			if err != nil {
				say(o.onError, fmt.Sprintf("observations: %v", err))
				return
			}
			o.items = list
			o.render()
			o.table.SetTitle(observationsTitle(stats))
			if statsErr != nil {
				say(o.onError, fmt.Sprintf("observations stats: %v", statsErr))
			}
		}
	})
}

// observationsTitle is the page title with each workflow's numbers; the median detection is said
// only when something is dormant — absent, not zero.
func observationsTitle(stats []client.ObservationStatsRow) string {
	parts := make([]string, 0, len(stats))
	for _, s := range stats {
		p := fmt.Sprintf("%s %d watched · %d dormant", tview.Escape(s.Workflow), s.Watched, s.Dormant)
		if s.MedianDetectionSeconds != nil {
			p += " · median detection " + humanizeSince(time.Duration(*s.MedianDetectionSeconds)*time.Second)
		}
		parts = append(parts, p)
	}
	if len(parts) == 0 {
		return " :observations "
	}
	return " :observations — " + strings.Join(parts, " | ") + " "
}

func (o *Observations) render() {
	o.table.Clear()
	o.setHeader()
	now := time.Now()
	for i, it := range o.items {
		row := i + 1
		alerted := "—"
		if it.AlertedAt != nil {
			alerted = "● " + humanizeSince(now.Sub(*it.AlertedAt))
		}
		cells := []*tview.TableCell{
			dataCell(it.Workflow).SetExpansion(1),
			dataCell(it.DocKey).SetExpansion(1),
			dataCell(it.State).SetExpansion(1),
			dataCell(humanizeSince(now.Sub(it.Since))).SetExpansion(1),
			dataCell(alerted).SetExpansion(1),
		}
		for col, cell := range cells {
			// A declared-dormant document is a warning, so the row wears the semantic
			// yellow — never the brand gold, which is chrome (the header rule).
			if it.AlertedAt != nil && style.ColorsEnabled() {
				cell.SetTextColor(tcell.ColorYellow)
			}
			o.table.SetCell(row, col, cell)
		}
	}
}

// humanizeSince renders how long a document has sat unmoved. Terse and English, for a
// column; the CLI prints the raw timestamp instead, so nothing is duplicated.
func humanizeSince(d time.Duration) string {
	switch {
	case d < time.Minute:
		return "now"
	case d < time.Hour:
		return fmt.Sprintf("%dmin", int(d.Minutes()))
	case d < 24*time.Hour:
		return fmt.Sprintf("%dh", int(d.Hours()))
	default:
		return fmt.Sprintf("%dd", int(d.Hours()/24))
	}
}

func (o *Observations) selected() (client.Observation, bool) {
	row, _ := o.table.GetSelection()
	if row < 1 || row-1 >= len(o.items) {
		return client.Observation{}, false
	}
	return o.items[row-1], true
}

func (o *Observations) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Rune() == 'r' {
		o.Refresh()
		return nil
	}
	return ev
}

// Describable

func (o *Observations) SelectedDescribe() (string, string, bool) {
	it, ok := o.selected()
	if !ok {
		return "", "", false
	}
	esc := tview.Escape
	var b strings.Builder
	fmt.Fprintf(&b, "[yellow]%s — %s[white]\n\n", esc(it.Workflow), esc(it.DocKey))
	fmt.Fprintf(&b, "state:           %s\n", esc(it.State))
	fmt.Fprintf(&b, "since:           %s\n", output.HumanTimeSeconds(&it.Since))
	fmt.Fprintf(&b, "first observed:  %s\n", output.HumanTimeSeconds(&it.FirstObservedAt))
	if it.AlertedAt != nil {
		fmt.Fprintf(&b, "alerted:         %s\n", output.HumanTimeSeconds(it.AlertedAt))
	} else {
		b.WriteString("alerted:         —\n")
	}
	b.WriteString("\nsince = when WE started observing this state — never a claim about\nthe source's own history.\n")
	return it.Workflow + " " + it.DocKey, b.String(), true
}

func (o *Observations) OnSelectionChange(cb func()) { o.onSelect = cb }

// Yankable

func (o *Observations) SelectedYank() (string, bool) {
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
