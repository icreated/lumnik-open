package resources

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// Ambiguous is the :ambiguous punch-list (#646, half of #613 lot #622's TUI): identities one
// entity's resolution pass could not settle. Unlike :conflicts (/api/entities/conflicts,
// aggregated across every entity), the ambiguity/history/resolve doors are all scoped
// /api/entities/{name}/... with no list-all-entities door on the hub side — so this page asks
// for an entity name the same way :ask asks for a question, rather than browsing one.
//
// assert-same/assert-distinct get no gesture here — a deliberate, ticket-sanctioned deferral:
// they need the operator to name a SECOND, unrelated identity (source + sourceId) plus a
// reason, and a candidate in an ambiguous row is a bare entity ID with no door to browse or
// pick an identity by it. That would be blind free-text with nothing to validate against until
// submit — exactly the "corrective-action UX with no second use case to shape it against" #646
// itself warns against. `lm entity assert-same`/`assert-distinct` remain CLI-only.
// ambiguousActive is the entity name + rows currently on screen, replaced as ONE unit only
// after a successful list — never a bare entity write ahead of items, or vice versa. That
// atomicity (not a "remember to update both, in this order" discipline) is what guarantees ^R
// and Enter always act on the entity actually shown: review round 1 on PR #667 found that a
// failed switch (list entity A, then a 404/500 while switching to B) left the OLD entity's rows
// on screen while ^R and Enter already targeted the new (unlisted) B — a corrective action
// aimed at a resource nobody could see.
type ambiguousActive struct {
	entity string
	items  []client.AmbiguousIdentity
}

// ambiguousHistoryPlaceholder is shown before any row's history has been loaded, and again
// after every successful entity switch — a lingering history panel from the PREVIOUS entity
// must never look current (same review round: two entity manifests can share a source
// identity, so a stale response landing late could otherwise read as the new entity's own).
const ambiguousHistoryPlaceholder = "[grey]select a row and press Enter to load its full history[white]"

type Ambiguous struct {
	app     *tview.Application
	client  *client.Client
	flex    *tview.Flex
	input   *tview.InputField
	table   *tview.Table
	history *tview.TextView

	active  ambiguousActive
	sortCol *style.ColumnSort

	// submitGen is bumped on every submit() call and captured by each in-flight fetch — the
	// ONLY response applied is the one whose generation still matches, so a same-name resubmit
	// (press r twice) is guarded exactly like an A->B switch: by request identity, not by
	// name, which two requests for the same entity would share (review round 2, PR #667).
	submitGen      int
	resolving      bool
	historyLoading bool

	spawn   func(func()) // seam: `go` in prod, inline in tests
	enqueue func(func()) // seam: QueueUpdate in prod, inline in tests

	showError func(string)
	toast     func(string)
	onSelect  func()
}

func NewAmbiguous(app *tview.Application, c *client.Client, showError, toast func(string)) *Ambiguous {
	a := &Ambiguous{
		app: app, client: c, showError: showError, toast: toast,
		spawn:   func(f func()) { go f() },
		sortCol: style.NewColumnSort([]string{"SOURCE", "OCCURRED_AT"}),
	}
	a.enqueue = func(f func()) { QueueUpdate(app, f) }

	a.input = tview.NewInputField().SetLabel(" Entity: ").SetFieldWidth(40)
	a.table = tview.NewTable().SetBorders(false).SetSelectable(true, false)
	style.ApplyChrome(a.table.Box)
	style.ApplyTableSelection(a.table)
	a.setHeader()

	a.history = tview.NewTextView().SetDynamicColors(true)
	a.history.SetBorder(true).SetTitle(" history (Enter on a row) ")
	a.history.SetText(ambiguousHistoryPlaceholder)

	a.input.SetDoneFunc(func(key tcell.Key) {
		switch key {
		case tcell.KeyEnter:
			a.submit(strings.TrimSpace(a.input.GetText()))
		case tcell.KeyEscape, tcell.KeyTab, tcell.KeyBacktab:
			// Blur so the global keys (: ? r d y) work again — otherwise the cursor
			// stays trapped in the field (the same rehearsal lesson :ask learned).
			if a.app != nil {
				a.app.SetFocus(a.table)
			}
		}
	})
	a.table.SetInputCapture(a.handleKey)
	a.table.SetSelectionChangedFunc(func(row, col int) {
		if a.onSelect != nil {
			a.onSelect()
		}
	})

	a.flex = tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(a.input, 1, 0, true).
		AddItem(a.table, 0, 3, false).
		AddItem(a.history, 0, 2, false)
	style.ApplyChrome(a.flex.Box)
	a.flex.SetTitle(" :ambiguous ").SetBorder(true)

	return a
}

func (a *Ambiguous) View() tview.Primitive { return a.flex }

func (a *Ambiguous) Hints() string {
	return "Enter: entity name · row: load history  ^R: resolve  d: describe  y: yank  s: sort  r: refresh"
}

// FocusPrimitive lets the App focus the entity-name field when the page opens.
func (a *Ambiguous) FocusPrimitive() tview.Primitive { return a.input }

func (a *Ambiguous) setHeader() {
	headers := []string{"IDENTITY_REF", a.sortCol.HeaderLabel(0), "SOURCE_ID", "CANDIDATES", "DECIDED_BY", a.sortCol.HeaderLabel(1)}
	for i, h := range headers {
		a.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1))
	}
}

// Refresh re-fetches the last-submitted entity, if any — switching pages away and back must
// not lose a typed name, mirroring :runs' sticky connector filter.
func (a *Ambiguous) Refresh() {
	if a.active.entity != "" {
		a.submit(a.active.entity)
	}
}

// submit fetches name's ambiguity list off the UI thread (like every other network call on this
// page — ^R and Enter-on-a-row already did; this was the one synchronous holdout, blocking the
// whole TUI on every entity lookup, `r` refresh, and page switch) and, only on success, replaces
// a.active as one unit — a failure leaves whatever was on screen before (entity, rows, and any
// loaded history) exactly as it was, rather than half-switching to a name whose rows never
// actually loaded.
//
// submitGen guards against a fast resubmit racing its own predecessor: whichever request's
// generation is no longer the latest is dropped when its response lands — including two
// requests for the SAME name (press `r` twice: an older, slower A response must not overwrite
// a newer A response that already landed), which a name-only guard cannot distinguish.
func (a *Ambiguous) submit(name string) {
	if name == "" {
		return
	}
	a.submitGen++
	gen := a.submitGen
	a.spawn(func() {
		list, err := a.client.EntityAmbiguous(name, 200, 0)
		a.enqueue(func() {
			if a.submitGen != gen {
				return // superseded by a newer submit
			}
			if err != nil {
				if a.showError != nil {
					a.showError("ambiguous: " + err.Error())
				}
				return
			}
			a.active = ambiguousActive{entity: name, items: list}
			a.history.SetText(ambiguousHistoryPlaceholder)
			a.applySort()
			a.render()
		})
	})
}

// The server already orders newest-evidence-first; this only re-orders on demand.
func (a *Ambiguous) applySort() {
	idx, desc, sorted := a.sortCol.Active()
	if !sorted {
		return
	}
	sort.SliceStable(a.active.items, func(i, j int) bool {
		// Descending swaps the OPERANDS, never negates the result: negating would
		// report less(i,j) and less(j,i) both true for equal keys, breaking
		// SliceStable's strict-weak-ordering contract and reversing every tie.
		x, y := a.active.items[i], a.active.items[j]
		if desc {
			x, y = y, x
		}
		switch idx {
		case 0:
			return x.Source < y.Source
		case 1:
			return x.OccurredAt.Before(y.OccurredAt)
		}
		return false
	})
}

func (a *Ambiguous) render() {
	a.table.Clear()
	a.setHeader()
	for i, it := range a.active.items {
		row := i + 1
		a.table.SetCell(row, 0, dataCell(it.IdentityRef).SetExpansion(1))
		a.table.SetCell(row, 1, dataCell(it.Source).SetExpansion(1))
		a.table.SetCell(row, 2, dataCell(it.SourceID).SetExpansion(1))
		a.table.SetCell(row, 3, dataCell(strings.Join(it.Candidates, ", ")).SetExpansion(2))
		a.table.SetCell(row, 4, dataCell(it.DecidedBy).SetExpansion(1))
		a.table.SetCell(row, 5, dataCell(output.HumanTimeSeconds(&it.OccurredAt)).SetExpansion(1))
	}
}

func (a *Ambiguous) selected() (client.AmbiguousIdentity, bool) {
	row, _ := a.table.GetSelection()
	if row < 1 || row-1 >= len(a.active.items) {
		return client.AmbiguousIdentity{}, false
	}
	return a.active.items[row-1], true
}

func (a *Ambiguous) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	switch {
	case ev.Key() == tcell.KeyEnter:
		a.loadHistory()
		return nil
	case ev.Key() == tcell.KeyCtrlR:
		a.resolve()
		return nil
	case ev.Rune() == 's':
		a.sortCol.Next()
		a.applySort()
		a.render()
		return nil
	case ev.Rune() == 'r':
		a.Refresh()
		return nil
	}
	return ev
}

// resolve re-runs the resolution pass for the last-submitted entity (^R). EntityResolve rides
// POSTSlow — the hub walks every row of every source synchronously, which can run well past a
// UI-thread-tolerable delay on a large entity — so this must never call the client on the event
// loop. It runs off-thread (spawn) and reports back through enqueue (QueueUpdate), single-flight
// (resolving) so a second ^R while one is in flight is a no-op rather than a second concurrent
// pass against the same entity.
func (a *Ambiguous) resolve() {
	if a.resolving {
		return
	}
	if a.active.entity == "" {
		if a.showError != nil {
			// a.active.entity is the last successfully LISTED entity, not whatever text
			// currently sits in the input — a typed-but-not-yet-submitted name, or one
			// whose list call failed, does not count (Copilot review, PR #667).
			a.showError("list an entity first (Enter on the entity field)")
		}
		return
	}
	a.resolving = true
	entity := a.active.entity
	// Snapshot submitGen, not a.active.entity: active.entity only reflects a LANDED
	// response, so if the operator submits a different entity while this resolve is still
	// running, active.entity stays `entity` until that new submit's response arrives — a
	// completion racing ahead of it would still pass an active.entity check and call
	// submit(entity), stomping the still-in-flight switch's eventual result (review round
	// 3, PR #667). submitGen instead reflects INTENT the instant submit() is called, landed
	// or not, so comparing against it defers correctly either way.
	genAtStart := a.submitGen
	if a.toast != nil {
		a.toast("resolving " + entity + "…")
	}
	a.spawn(func() {
		res, err := a.client.EntityResolve(entity)
		a.enqueue(func() {
			a.resolving = false
			if err != nil {
				if a.showError != nil {
					a.showError("resolve: " + err.Error())
				}
				return
			}
			if a.toast != nil {
				a.toast(fmt.Sprintf("resolved %s: %d seen, %d resolved, %d ambiguous",
					entity, res.RowsSeen, res.Resolved, res.Ambiguous))
			}
			// Only auto-refresh if no submit() has been requested since this resolve
			// started — a newer one, even one still in flight, must win.
			if a.submitGen == genAtStart {
				a.submit(entity)
			}
		})
	})
}

// loadHistory is the Enter-on-a-row gesture: an async fetch of the selected identity's full
// evidence ledger. Deliberately NOT part of SelectedDescribe — the global `d` key calls that
// synchronously on the tview event loop, where a network round trip would freeze the whole TUI.
// `d` stays pure (row data only); this is the one door that reaches the network, gated by its
// own key and its own single-flight guard.
func (a *Ambiguous) loadHistory() {
	if a.historyLoading {
		return
	}
	row, ok := a.selected()
	if !ok {
		return
	}
	a.historyLoading = true
	a.history.SetText("[yellow]loading…[white]")
	entity, source, sourceID := a.active.entity, row.Source, row.SourceID
	a.spawn(func() {
		events, err := a.client.EntityHistory(entity, source, sourceID)
		a.enqueue(func() {
			a.historyLoading = false
			// The selection (source/sourceID) OR the active entity may have moved while
			// the request was in flight — a stale response landing on a since-changed
			// row, or after switching to a different entity, must not render. The
			// entity check matters even when source/sourceID still match: two entity
			// manifests can share a source identity, so a late A response could
			// otherwise pass the row check and be shown as B's history (review round 1,
			// PR #667).
			cur, ok := a.selected()
			if !ok || a.active.entity != entity || cur.Source != source || cur.SourceID != sourceID {
				// Don't strand the "loading…" text set above — nothing is fetching for
				// the row/entity now showing, so the panel must say so (Copilot review,
				// PR #667).
				a.history.SetText(ambiguousHistoryPlaceholder)
				return
			}
			if err != nil {
				a.history.SetText(tview.Escape("history: " + err.Error()))
				return
			}
			a.history.SetText(renderHistory(events))
		})
	})
}

// writeSetAside lists the entities the resolver set aside for already holding another row of the
// source (#920) — the evidence of a duplicated source row, which would otherwise read as a fresh mint.
func writeSetAside(b *strings.Builder, indent string, set []client.SetAside) {
	if len(set) == 0 {
		return
	}
	b.WriteString(indent + "set aside:\n")
	for _, s := range set {
		via := "same assertion"
		if s.Rule != nil {
			via = "rule " + *s.Rule
		}
		fmt.Fprintf(b, "%s  %s  %s (%s)\n", indent, tview.Escape(s.EntityID), tview.Escape(s.Reason), tview.Escape(via))
	}
}

func renderHistory(events []client.EvidenceEvent) string {
	if len(events) == 0 {
		return "[grey]no history[white]"
	}
	var b strings.Builder
	for _, e := range events {
		fmt.Fprintf(&b, "%s  %s  rule=%s provider=%s decided_by=%s run=%s\n  candidates: %s\n",
			output.HumanTimeSeconds(&e.OccurredAt), tview.Escape(e.Outcome), tview.Escape(e.RuleDeclaredID),
			tview.Escape(e.Provider), tview.Escape(e.DecidedBy), tview.Escape(e.RunID),
			tview.Escape(strings.Join(e.Candidates, ", ")))
		writeSetAside(&b, "  ", e.SetAside)
	}
	return b.String()
}

// Describable

// SelectedDescribe shows only the data already carried by the selected row — never a network
// call (see loadHistory's doc comment for why `d` must stay synchronous).
func (a *Ambiguous) SelectedDescribe() (string, string, bool) {
	it, ok := a.selected()
	if !ok {
		return "", "", false
	}
	esc := tview.Escape
	var b strings.Builder
	fmt.Fprintf(&b, "[yellow]%s[white]\n\n", esc(it.IdentityRef))
	fmt.Fprintf(&b, "source:      %s\n", esc(it.Source))
	fmt.Fprintf(&b, "source_id:   %s\n", esc(it.SourceID))
	fmt.Fprintf(&b, "candidates:  %s\n", esc(strings.Join(it.Candidates, ", ")))
	fmt.Fprintf(&b, "decided_by:  %s\n", esc(it.DecidedBy))
	fmt.Fprintf(&b, "occurred_at: %s\n", output.HumanTimeSeconds(&it.OccurredAt))
	if len(it.DiscardedCandidates) > 0 {
		b.WriteString("discarded:\n")
		for _, d := range it.DiscardedCandidates {
			fmt.Fprintf(&b, "  %s  score=%.3f\n", esc(d.EntityID), d.Score)
		}
	}
	writeSetAside(&b, "", it.SetAside)
	b.WriteString("\nEnter: load this identity's full history below\n")
	return it.IdentityRef, b.String(), true
}

func (a *Ambiguous) OnSelectionChange(cb func()) { a.onSelect = cb }

// Yankable

func (a *Ambiguous) SelectedYank() (string, bool) {
	it, ok := a.selected()
	if !ok {
		return "", false
	}
	raw, err := json.MarshalIndent(it, "", "  ")
	if err != nil {
		return "", false
	}
	return string(raw), true
}
