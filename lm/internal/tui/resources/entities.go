package resources

import (
	"fmt"
	"strings"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// entityPage is the rows page size; the hub caps a page at 1000.
const entityPage = 100

// Entities is :entities — the entities the caller may read (#1114) and, on Enter, one entity's
// canonical rows, without writing a kind:View first. On a resolution entity the rows carry their
// identity (?identity=true), which is what the resolution actions act on (#1116): mark a row,
// assert it the same as / distinct from another, read its history, re-run the pass. Every hub
// call runs off the event loop — a resolve replays the whole entity.
type Entities struct {
	app      *tview.Application
	client   *client.Client
	table    *tview.Table
	mu       sync.Mutex
	offLoop  offLoop
	inFlight inFlight // the guard on Del, =, !, h and ^R (#1147)
	toast    func(string)
	onError  func(string)
	ask      Prompt
	confirm  ConfirmAs
	show     func(title, body string)
	onSelect func()
	onAmbig  func(entity string)

	list   []client.EntitySummary
	entity string // "" = the list; otherwise the entity whose rows are shown
	rows   []client.EntityRow
	offset int
	marked rowIdentity // the row m marked; zero for none
}

func NewEntities(app *tview.Application, c *client.Client, toast, onError func(string)) *Entities {
	t := newListTable()
	t.SetTitle(" :entities ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	p := &Entities{app: app, client: c, table: t, toast: toast, onError: onError}
	t.SetInputCapture(p.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if p.onSelect != nil {
			p.onSelect()
		}
	})
	return p
}

// Wired by the App; without them the gestures that need them do nothing.
func (p *Entities) SetAsk(fn Prompt)                      { p.ask = fn }
func (p *Entities) SetConfirm(fn ConfirmAs)               { p.confirm = fn }
func (p *Entities) SetShow(fn func(title, body string))   { p.show = fn }
func (p *Entities) SetOnAmbiguous(fn func(entity string)) { p.onAmbig = fn }

func (p *Entities) View() tview.Primitive { return p.table }

func (p *Entities) Hints() string {
	if p.entity == "" {
		return "Enter: rows  a: ambiguous identities  Del: delete the entity  d: describe  r: refresh"
	}
	return "Esc: ← entities  n/p: next/prev page  m: mark  =: same as marked  !: distinct from marked  h: history  ^R: resolve  d: describe  r: refresh"
}

// FilterActive / ClearFilter make the rows view a drill-down the App's Esc unwinds (Drillable).
func (p *Entities) FilterActive() bool { return p.entity != "" }

func (p *Entities) ClearFilter() {
	p.setView("", 0)
	p.rows, p.marked = nil, rowIdentity{}
	p.table.SetTitle(" :entities ")
	p.renderList()
	p.Refresh()
}

// setView picks what Refresh reads — the list ("") or an entity's page. Under the lock: the fetch
// goroutine reads them, and a dirty round must ask the question now on screen.
func (p *Entities) setView(entity string, offset int) {
	p.mu.Lock()
	p.entity, p.offset = entity, offset
	p.mu.Unlock()
}

func (p *Entities) Refresh() {
	p.offLoop.run(p.app, func() func() {
		p.mu.Lock()
		entity, offset := p.entity, p.offset
		p.mu.Unlock()
		if entity == "" {
			list, err := p.client.EntityList()
			return func() {
				if err != nil {
					say(p.onError, fmt.Sprintf("entities: %v", err))
					return
				}
				p.list = list
				p.renderList()
			}
		}
		rows, err := p.client.EntityRowsWithIdentity(entity, entityPage, offset)
		return func() {
			if err != nil {
				say(p.onError, fmt.Sprintf("%s: %v", entity, err))
				return
			}
			if p.entity != entity {
				return // Esc or another entity landed meanwhile
			}
			p.rows = rows
			p.renderRows()
		}
	})
}

func (p *Entities) renderList() {
	prev := selectedKey(p.table, p.list, func(e client.EntitySummary) string { return e.Name })
	headerRow(p.table, []string{"name", "sources", "scopes", "resolution"})
	if len(p.list) == 0 {
		p.table.SetCell(1, 0, tview.NewTableCell("No entities you may read — apply one from :new.").SetSelectable(false))
		return
	}
	for r, e := range p.list {
		for c, v := range []string{e.Name, strings.Join(e.Sources, ","), strings.Join(e.Scopes, ","), e.Mode()} {
			p.table.SetCell(r+1, c, dataCell(v).SetExpansion(1))
		}
	}
	reselect(p.table, p.list, func(e client.EntitySummary) string { return e.Name }, prev)
}

// shownColumns are the canonical columns, then the resolution state; the identity columns and
// the ledger stay in describe, where they are read in full.
func shownColumns(rows []client.EntityRow) []string {
	if len(rows) == 0 {
		return nil
	}
	var cols []string
	for _, c := range rows[0].Columns() {
		if c == "_entity_id" || c == "_handle" || c == "_conflicts" {
			continue
		}
		cols = append(cols, c)
	}
	return cols
}

func rowKey(r client.EntityRow) string {
	if id, ok := identityOf(r); ok {
		return id.label()
	}
	return strings.Join(cellsOf(r, r.Columns()), "\x00")
}

func cellsOf(r client.EntityRow, cols []string) []string {
	out := make([]string, len(cols))
	for i, c := range cols {
		out[i] = r.Cell(c)
	}
	return out
}

func (p *Entities) renderRows() {
	prev := selectedKey(p.table, p.rows, rowKey)
	cols := shownColumns(p.rows)
	headerRow(p.table, cols)
	title := fmt.Sprintf(" :entities [%s] rows %d–%d ", p.entity, p.offset+1, p.offset+len(p.rows))
	if len(p.rows) == 0 {
		title = fmt.Sprintf(" :entities [%s] past the last row (p: back) ", p.entity)
	}
	if !p.marked.zero() {
		title += "· marked " + p.marked.label() + " "
	}
	p.table.SetTitle(tview.Escape(title))
	if len(p.rows) == 0 {
		p.table.SetCell(1, 0, tview.NewTableCell("No rows on this page.").SetSelectable(false))
		return
	}
	for r, row := range p.rows {
		for c, v := range cellsOf(row, cols) {
			p.table.SetCell(r+1, c, dataCell(v).SetExpansion(1))
		}
	}
	if !reselect(p.table, p.rows, rowKey, prev) {
		p.table.Select(1, 0)
	}
}

func (p *Entities) selectedEntity() (client.EntitySummary, bool) {
	row, _ := p.table.GetSelection()
	if p.entity != "" || row < 1 || row-1 >= len(p.list) {
		return client.EntitySummary{}, false
	}
	return p.list[row-1], true
}

func (p *Entities) selectedRow() (client.EntityRow, bool) {
	row, _ := p.table.GetSelection()
	if p.entity == "" || row < 1 || row-1 >= len(p.rows) {
		return client.EntityRow{}, false
	}
	return p.rows[row-1], true
}

func (p *Entities) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	if p.entity == "" {
		return p.listKey(ev)
	}
	return p.rowsKey(ev)
}

func (p *Entities) listKey(ev *tcell.EventKey) *tcell.EventKey {
	e, ok := p.selectedEntity()
	switch {
	case ev.Key() == tcell.KeyEnter && ok:
		p.setView(e.Name, 0)
		p.marked = rowIdentity{}
		p.Refresh()
		return nil
	case ev.Key() == tcell.KeyDelete && ok:
		p.deleteEntity(e.Name)
		return nil
	case ev.Rune() == 'a' && ok && p.onAmbig != nil:
		p.onAmbig(e.Name)
		return nil
	case ev.Rune() == 'r':
		p.Refresh()
		return nil
	}
	return ev
}

func (p *Entities) rowsKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Key() == tcell.KeyCtrlR {
		p.resolve()
		return nil
	}
	switch ev.Rune() {
	case 'n':
		if len(p.rows) == entityPage {
			p.setView(p.entity, p.offset+entityPage)
			p.Refresh()
		}
		return nil
	case 'p':
		if p.offset > 0 {
			p.setView(p.entity, max(0, p.offset-entityPage))
			p.Refresh()
		}
		return nil
	case 'm':
		p.mark()
		return nil
	case '=':
		p.assert("same", "=", "the SAME real-world entity", p.client.EntityAssertSame)
		return nil
	case '!':
		p.assert("distinct", "!", "DIFFERENT real-world entities", p.client.EntityAssertDistinct)
		return nil
	case 'h':
		p.history()
		return nil
	case 'r':
		p.Refresh()
		return nil
	}
	return ev
}

// rowIdentity names one row of a resolution entity the way an assertion takes it: a row nothing has
// resolved by its SOURCE:ID handle, a resolved one — several source rows fused under one id, its
// handle NULL — by that entity id.
type rowIdentity struct{ handle, ref string }

func (id rowIdentity) zero() bool { return id.handle == "" && id.ref == "" }

func (id rowIdentity) label() string {
	if id.handle != "" {
		return id.handle
	}
	return "entity " + id.ref
}

// side fills one side of an assertion: a handle goes as left/right, an entity id as leftRef/rightRef.
func (id rowIdentity) side() (handle, ref string) { return id.handle, id.ref }

func identityOf(r client.EntityRow) (rowIdentity, bool) {
	if h := r.Cell("_handle"); h != "" {
		return rowIdentity{handle: h}, true
	}
	if ref := r.Cell("_entity_id"); ref != "" {
		return rowIdentity{ref: ref}, true
	}
	return rowIdentity{}, false
}

func (p *Entities) identityOf(r client.EntityRow) (rowIdentity, bool) {
	id, ok := identityOf(r)
	if !ok {
		say(p.onError, "this entity has no identity resolution: its rows carry no identity to assert or trace")
	}
	return id, ok
}

func (p *Entities) mark() {
	r, ok := p.selectedRow()
	if !ok {
		return
	}
	id, ok := p.identityOf(r)
	if !ok {
		return
	}
	p.marked = id
	p.renderRows()
	say(p.toast, "marked "+id.label()+" — now = (same) or ! (distinct) on another row")
}

func (p *Entities) assert(verb, key, subject string, send func(string, client.EntityAssertion) error) {
	r, ok := p.selectedRow()
	if !ok || p.ask == nil || p.confirm == nil {
		return
	}
	right, ok := p.identityOf(r)
	if !ok {
		return
	}
	left, entity := p.marked, p.entity
	if left.zero() {
		say(p.onError, "mark a row first (m), then press "+key+" on the other one")
		return
	}
	if left.label() == right.label() {
		say(p.onError, "the marked row and this one are the same row")
		return
	}
	if p.inFlight.busy(key, p.toast) { // no question for a press that would be dropped
		return
	}
	p.ask("Reason (optional, kept in the decision ledger): ", false, func(reason string) {
		p.confirm(fmt.Sprintf("Assert that %s and %s are %s in %q?", left.label(), right.label(), subject, entity), "Assert", func() {
			a := client.EntityAssertion{Reason: strings.TrimSpace(reason)}
			a.Left, a.LeftRef = left.side()
			a.Right, a.RightRef = right.side()
			p.inFlight.background(p.app, key, p.toast, func() func() {
				err := send(entity, a)
				return func() {
					if err != nil {
						say(p.onError, fmt.Sprintf("assert %s: %v", verb, err))
						return
					}
					p.marked = rowIdentity{}
					say(p.toast, fmt.Sprintf("recorded: %s and %s are %s — ^R replays the pass", left.label(), right.label(), verb))
					p.Refresh()
				}
			})
		})
	})
}

func (p *Entities) history() {
	r, ok := p.selectedRow()
	if !ok || p.show == nil {
		return
	}
	id, ok := p.identityOf(r)
	if !ok {
		return
	}
	if id.handle == "" {
		// The ledger is kept per source row; a resolved row fuses several of them under one id.
		say(p.onError, "a resolved row fuses several source rows — its history is per source row: h works on an unresolved row, or see the decisions in :ambiguous (a)")
		return
	}
	h := id.handle
	source, sourceID, _ := strings.Cut(h, ":") // a table name holds no ':', an identity may
	entity := p.entity
	p.inFlight.background(p.app, "h", p.toast, func() func() {
		events, err := p.client.EntityHistory(entity, source, sourceID)
		return func() {
			if err != nil {
				say(p.onError, fmt.Sprintf("history: %v", err))
				return
			}
			p.show(" history of "+tview.Escape(h)+" ", renderHistory(events))
		}
	})
}

func (p *Entities) resolve() {
	if p.inFlight.busy("^R", p.toast) {
		return // one pass at a time, like :ambiguous: a second ^R would replay the extent twice
	}
	entity := p.entity
	say(p.toast, "resolving "+entity+"…")
	p.inFlight.background(p.app, "^R", p.toast, func() func() {
		res, err := p.client.EntityResolve(entity)
		return func() {
			if err != nil {
				say(p.onError, "resolve: "+err.Error())
				return
			}
			say(p.toast, resolvedTally(entity, res))
			p.Refresh()
		}
	})
}

func (p *Entities) deleteEntity(name string) {
	if p.confirm == nil || p.inFlight.busy("Del", p.toast) { // no question for a press that would be dropped
		return
	}
	p.confirm(fmt.Sprintf("Delete entity %q? Its view is dropped for every tenant, every search index over it goes, and its connectors are freed.", name), "Delete", func() {
		p.inFlight.background(p.app, "Del", p.toast, func() func() {
			err := p.client.EntityDelete(name)
			return func() {
				if err != nil {
					say(p.onError, fmt.Sprintf("delete %s: %v", name, err))
					return
				}
				say(p.toast, "deleted: "+name)
				p.Refresh()
			}
		})
	})
}

// Describable — the whole row, identity and ledger included; or the entity's declaration.

func (p *Entities) SelectedDescribe() (string, string, bool) {
	esc := tview.Escape
	var b strings.Builder
	if e, ok := p.selectedEntity(); ok {
		fmt.Fprintf(&b, "name:       %s\n", esc(e.Name))
		fmt.Fprintf(&b, "label:      %s\n", esc(e.Label))
		fmt.Fprintf(&b, "scopes:     %s\n", esc(strings.Join(e.Scopes, ", ")))
		fmt.Fprintf(&b, "sources:    %s\n", esc(strings.Join(e.Sources, ", ")))
		fmt.Fprintf(&b, "resolution: %v\n", e.Resolution)
		fmt.Fprintf(&b, "columns:    %s\n", esc(strings.Join(e.Columns, ", ")))
		return e.Name, b.String(), true
	}
	r, ok := p.selectedRow()
	if !ok {
		return "", "", false
	}
	for _, c := range r.Columns() {
		fmt.Fprintf(&b, "%-14s %s\n", esc(c)+":", esc(r.Cell(c)))
	}
	return p.entity, b.String(), true
}

func (p *Entities) OnSelectionChange(cb func()) { p.onSelect = cb }
