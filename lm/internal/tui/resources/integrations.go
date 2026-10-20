package resources

import (
	"fmt"
	"strconv"
	"strings"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// healthWindow is how many of a target's latest deliveries its health is read from.
const healthWindow = 10

// Integrations is :integrations — the webhook targets :outputs delivers to, each with its recent
// delivery health (#1133). A webhook URL is often the credential itself (a chat tool's incoming
// hook carries its token in the path), so the page shows of it only the scheme and the host, and
// asks it masked; the signing secret is write-only on the hub and typed masked too.
type Integrations struct {
	app    *tview.Application
	client *client.Client
	table  *tview.Table
	items  []client.Integration
	all    []client.Integration // every target the hub listed; items is what the / filter shows
	recent map[int64]deliveries
	textFilter
	mu       sync.Mutex
	offLoop  offLoop
	inFlight inFlight // the guard on n, T and Del (#1147)
	toast    func(string)
	onError  func(string)
	ask      Prompt
	confirm  ConfirmAs
	onSelect func()
}

// deliveries is one target's latest attempts, newest first, or why they could not be read.
type deliveries struct {
	rows []client.IntegrationDelivery
	err  error
}

func NewIntegrations(app *tview.Application, c *client.Client, toast, onError func(string)) *Integrations {
	t := newListTable()
	t.SetTitle(" :integrations ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	p := &Integrations{app: app, client: c, table: t, toast: toast, onError: onError}
	t.SetInputCapture(p.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if p.onSelect != nil {
			p.onSelect()
		}
	})
	return p
}

// SetAsk and SetConfirm are wired by the App; without them n/T and Del do nothing.
func (p *Integrations) SetAsk(fn Prompt)        { p.ask = fn }
func (p *Integrations) SetConfirm(fn ConfirmAs) { p.confirm = fn }

func (p *Integrations) View() tview.Primitive { return p.table }

func (p *Integrations) Hints() string {
	return "n: new target  T: test  Del: remove  d: describe  r: refresh"
}

// Refresh reads the targets, then each one's latest deliveries — one call per target: the hub
// has no door that answers health for all of them at once.
func (p *Integrations) Refresh() {
	p.offLoop.run(p.app, func() func() {
		list, err := p.client.IntegrationList()
		recent := map[int64]deliveries{}
		for _, it := range list {
			rows, derr := p.client.IntegrationDeliveries(it.ID, healthWindow)
			recent[it.ID] = deliveries{rows, derr}
		}
		return func() { p.renderList(list, recent, err) }
	})
}

func (p *Integrations) renderList(list []client.Integration, recent map[int64]deliveries, err error) {
	if err != nil {
		say(p.onError, fmt.Sprintf("integrations: %v", err))
		return
	}
	p.mu.Lock()
	prev := selectedKey(p.table, p.items, integrationKey)
	p.all, p.recent = list, recent
	p.items = filterRows(list, p.q, p.columns)
	p.render()
	reselect(p.table, p.items, integrationKey, prev)
	p.mu.Unlock()
	if p.onSelect != nil {
		p.onSelect()
	}
}

// SetTextFilter narrows the table to the targets holding q; "" shows them all.
func (p *Integrations) SetTextFilter(q string) { p.q = q; p.renderList(p.all, p.recent, nil) }

func integrationKey(i client.Integration) string { return strconv.FormatInt(i.ID, 10) }

// columns is what a row shows — and so all the / filter searches: never the full URL.
func (p *Integrations) columns(i client.Integration) []string {
	return []string{i.Name, output.TargetHost(i.TargetURL), strings.Join(i.EventTypes, ","), i.AuthMode,
		yesNo(i.Enabled, "yes", "no"), health(p.recent[i.ID])}
}

func (p *Integrations) render() {
	headerRow(p.table, []string{"NAME", "TARGET", "EVENTS", "AUTH", "ENABLED", "RECENT DELIVERIES"})
	if len(p.items) == 0 {
		p.table.SetCell(1, 0, tview.NewTableCell("No integrations yet — press n to register a webhook target.").SetSelectable(false))
		return
	}
	for r, it := range p.items {
		for c, v := range p.columns(it) {
			p.table.SetCell(r+1, c, dataCell(v).SetExpansion(1))
		}
	}
}

// health says how a target's latest deliveries went: the last one, then how many arrived.
func health(d deliveries) string {
	switch {
	case d.err != nil:
		return "unavailable"
	case len(d.rows) == 0:
		return "none yet"
	}
	ok := 0
	for _, r := range d.rows {
		if r.Succeeded() {
			ok++
		}
	}
	return fmt.Sprintf("last %s · %d/%d ok", attempt(d.rows[0]), ok, len(d.rows))
}

func attempt(d client.IntegrationDelivery) string {
	mark := "✗"
	if d.Succeeded() {
		mark = "✓"
	}
	if d.HTTPStatus == nil {
		return mark + " no response"
	}
	return fmt.Sprintf("%s %d", mark, *d.HTTPStatus)
}

func (p *Integrations) selected() (client.Integration, bool) {
	row, _ := p.table.GetSelection()
	if row < 1 || row-1 >= len(p.items) {
		return client.Integration{}, false
	}
	return p.items[row-1], true
}

func (p *Integrations) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Key() == tcell.KeyDelete {
		p.remove()
		return nil
	}
	switch ev.Rune() {
	case 'n':
		p.create()
		return nil
	case 'T':
		p.test()
		return nil
	case 'r':
		p.Refresh()
		return nil
	}
	return ev
}

// create registers a target, the guided twin of `lm integration add`: the URL and the signing
// secret are asked masked. The hub takes the secret's value itself — it cannot refer to a secret
// stored in :secrets.
func (p *Integrations) create() {
	if p.ask == nil || p.inFlight.busy("n", p.toast) { // no question for a press that would be dropped
		return
	}
	askAll(p.ask, []step{
		{"Integration name: ", false, true},
		{"Target URL (hidden — a webhook URL can be the credential): ", true, true},
		{"Event types, comma-separated (Enter: decision.*): ", false, false},
		{"Signing secret (hidden): ", true, true}, // the hub refuses a signing mode without one (#1239)
	}, func(a []string) {
		name, target := a[0], strings.TrimSpace(a[1])
		events := client.SplitScopes(a[2]) // the one comma-list parser; nothing typed is the CLI's default
		if len(events) == 0 {
			events = []string{"decision.*"}
		}
		in := client.IntegrationCreate{Name: name, TargetURL: target, EventTypes: events, Secret: a[3]}
		p.inFlight.background(p.app, "n", p.toast, func() func() {
			created, err := p.client.IntegrationCreate(in)
			return func() {
				if err != nil {
					say(p.onError, fmt.Sprintf("register %s: %s", name, output.ScrubURL(err.Error(), target)))
					return
				}
				say(p.toast, fmt.Sprintf("✓ registered: %s (id %d) — forwards subscribed events from now on", created.Name, created.ID))
				p.Refresh()
			}
		})
	})
}

// test fires a catalog event at the selected target and says how the delivery went.
func (p *Integrations) test() {
	it, ok := p.selected()
	if !ok || p.ask == nil || p.inFlight.busy("T", p.toast) {
		return
	}
	p.ask("Event type to fire at "+it.Name+" (Enter: org.created): ", false, func(event string) {
		if event = strings.TrimSpace(event); event == "" {
			event = "org.created"
		}
		p.inFlight.background(p.app, "T", p.toast, func() func() {
			res, err := p.client.IntegrationTest(it.ID, event)
			return func() {
				head := fmt.Sprintf("test %s → %s", event, it.Name)
				switch {
				case err != nil:
					say(p.onError, head+": "+output.ScrubURL(err.Error(), it.TargetURL))
				case !res.OK:
					status := "no response"
					if res.HTTPStatus != nil {
						status = fmt.Sprintf("HTTP %d", *res.HTTPStatus)
					}
					say(p.onError, fmt.Sprintf("%s: delivery failed (%s): %s", head, status, output.ScrubURL(res.Error, it.TargetURL)))
				default:
					say(p.toast, fmt.Sprintf("✓ %s: delivered in %dms (HTTP %d)", head, res.DurationMs, *res.HTTPStatus))
				}
				p.Refresh()
			}
		})
	})
}

func (p *Integrations) remove() {
	it, ok := p.selected()
	if !ok || p.confirm == nil || p.inFlight.busy("Del", p.toast) {
		return
	}
	q := fmt.Sprintf("Remove integration %q (%s)? It receives nothing more; its delivery history is kept.",
		it.Name, output.TargetHost(it.TargetURL))
	p.confirm(q, "Remove", func() {
		p.inFlight.background(p.app, "Del", p.toast, func() func() {
			err := p.client.IntegrationDelete(it.ID)
			return func() {
				if err != nil {
					say(p.onError, fmt.Sprintf("remove %s: %v", it.Name, err))
					return
				}
				say(p.toast, "removed: "+it.Name)
				p.Refresh()
			}
		})
	})
}

// Describable — the registration (URL reduced to its host) and the latest deliveries.

func (p *Integrations) SelectedDescribe() (string, string, bool) {
	it, ok := p.selected()
	if !ok {
		return "", "", false
	}
	esc := tview.Escape
	var b strings.Builder
	fmt.Fprintf(&b, "name:           %s (id %d)\n", esc(it.Name), it.ID)
	fmt.Fprintf(&b, "target:         %s\n", esc(output.TargetHost(it.TargetURL)))
	fmt.Fprintf(&b, "platform:       %s\n", esc(it.Platform))
	fmt.Fprintf(&b, "auth:           %s\n", esc(it.AuthMode))
	fmt.Fprintf(&b, "events:         %s\n", esc(strings.Join(it.EventTypes, ", ")))
	fmt.Fprintf(&b, "enabled:        %t\n", it.Enabled)
	secret := "none"
	if it.HasSecret {
		secret = "set"
	}
	fmt.Fprintf(&b, "signing secret: %s\n", secret)
	b.WriteString("\nOnly the scheme and host of the URL are shown: its path can be the credential.\n")
	d := p.recent[it.ID]
	fmt.Fprintf(&b, "\n── latest deliveries (up to %d) ──\n", healthWindow)
	switch {
	case d.err != nil:
		fmt.Fprintf(&b, "unavailable: %s\n", esc(output.ScrubURL(d.err.Error(), it.TargetURL)))
	case len(d.rows) == 0:
		b.WriteString("none yet — T fires a test event\n")
	}
	for _, r := range d.rows {
		fmt.Fprintf(&b, "%s  %-14s %s  %dms", r.AttemptedAt.Local().Format("2006-01-02 15:04:05"),
			attempt(r), esc(r.EventType), r.DurationMs)
		if r.Error != "" {
			fmt.Fprintf(&b, "  %s", esc(output.ScrubURL(r.Error, it.TargetURL)))
		}
		b.WriteString("\n")
	}
	return it.Name, b.String(), true
}

func (p *Integrations) OnSelectionChange(cb func()) { p.onSelect = cb }
