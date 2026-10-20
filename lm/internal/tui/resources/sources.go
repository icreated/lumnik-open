package resources

import (
	"fmt"
	"slices"
	"sort"
	"strings"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/editor"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
	"gopkg.in/yaml.v3"
)

type Sources struct {
	table  *tview.Table
	client *client.Client
	items  []types.Connector
	all    []types.Connector // every connector the hub listed; items is what the / filter shows
	textFilter
	app         *tview.Application
	offLoop     offLoop
	inFlight    inFlight // the guard on e, y, ^R, ^P, Del and t's scope sync (#1147, #1156, #1158)
	mu          sync.Mutex
	onError     func(string)
	toast       func(string)
	sortCol     *style.ColumnSort
	onSelect    func()
	onEnter     func(connectorID, name, kind string) // wired by App: REST → :endpoints, others → :runs
	onScopeEdit func(id string, current []string)
	confirm     ConfirmAs                // wired by App: Del, and the scope sync offered after t (#1138)
	onDead      func(connectorID string) // wired by App: D → :dlq filtered on the connector
	suspend     func(func())             // seam: SuspendEditor in prod, a stub in tests
	isFront     func() bool              // true while :sources is in front of a live app; gates Del's question (#1218)
}

func NewSources(app *tview.Application, c *client.Client, onError, toast func(string)) *Sources {
	t := newListTable()
	t.SetTitle(" :sources ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	cn := &Sources{
		table:   t,
		client:  c,
		app:     app,
		toast:   toast,
		onError: onError,
		sortCol: style.NewColumnSort([]string{"NAME", "TYPE", "SCHED", "ENABLED"}),
	}
	cn.suspend = func(f func()) { SuspendEditor(cn.app, cn.signalError, f) }
	cn.setHeader()
	cn.table.SetInputCapture(cn.handleKey)
	cn.table.SetSelectionChangedFunc(func(row, col int) {
		if cn.onSelect != nil {
			cn.onSelect()
		}
	})
	return cn
}

// SetOnEnter is called by the App to wire Enter → :endpoints (rest) or :runs (others).
func (c *Sources) SetOnEnter(fn func(connectorID, name, kind string)) { c.onEnter = fn }

// SetOnScopeEdit is wired by the App so pressing `t` opens the scope-edit modal (the key
// predates the rename; `s` is sort).
func (c *Sources) SetOnScopeEdit(fn func(string, []string)) { c.onScopeEdit = fn }

// SetConfirm is wired by the App: Del asks through it, and deletes nothing without one; the scope
// sync is offered through it too.
func (c *Sources) SetConfirm(fn ConfirmAs) { c.confirm = fn }

// SetFront is wired by the App: Del's dry run answers off the loop, and its question opens only
// while :sources is still in front.
func (c *Sources) SetFront(fn func() bool) { c.isFront = fn }

// SetSuspend is wired by the App to its own way to $EDITOR, which a :context switch closes on the
// app it retires (#1151): e reads off the loop, so its editor can open after the switch.
func (c *Sources) SetSuspend(fn func(func())) { c.suspend = fn }

// ScopesSet is told each time t set a connector's scopes. When they changed, its already-indexed
// chunks still carry the old ones: it offers to re-stamp them, `lm connector scope sync` (#1138).
func (c *Sources) ScopesSet(id string, before, after []string) {
	if c.confirm == nil || slices.Equal(before, after) || c.inFlight.busy("t", c.toast) { // no question for a press that would be dropped
		return
	}
	name := id
	if i := slices.IndexFunc(c.all, func(ct types.Connector) bool { return ct.ID == id }); i >= 0 {
		name = c.all[i].Name
	}
	c.confirm(fmt.Sprintf("Re-stamp connector %q's indexed RAG chunks with its new scopes?", name), "Re-stamp", func() {
		c.inFlight.background(c.app, "t", c.toast, func() func() {
			out, err := c.client.ConnectorRetag(id)
			return func() {
				if err != nil {
					c.signalError(fmt.Sprintf("scope sync: %v", err))
					return
				}
				say(c.toast, fmt.Sprintf("✓ %v chunk(s) re-stamped: %s", out["chunksUpdated"], name))
			}
		})
	})
}

// SetOnDeadLetters is wired by the App so D opens the selected connector's dead letters (#1111).
func (c *Sources) SetOnDeadLetters(fn func(connectorID string)) { c.onDead = fn }

func (c *Sources) View() tview.Primitive { return c.table }

func (c *Sources) Hints() string {
	return "e: edit  t: scopes  d: describe  D: dead letters  ^R: run  ^P: pause  Del: delete  y: yank  s: sort  Enter: → endpoints/runs  r: refresh"
}

func (c *Sources) setHeader() {
	headers := []string{
		c.sortCol.HeaderLabel(0),
		c.sortCol.HeaderLabel(1),
		"KIND",
		"TENANT",
		c.sortCol.HeaderLabel(2),
		c.sortCol.HeaderLabel(3),
		"SCOPES",
	}
	for i, h := range headers {
		c.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1).SetTextColor(tcell.ColorYellow))
	}
}

// Refresh lists off the event loop (offLoop): the live ticker calls it while the page is in front
// (#891).
func (c *Sources) Refresh() {
	c.offLoop.run(c.app, func() func() {
		list, err := c.client.ConnectorList()
		return func() { c.renderList(list, err) }
	})
}

func (c *Sources) renderList(list []types.Connector, err error) {
	if err != nil {
		c.signalError(fmt.Sprintf("list: %v", err))
		return
	}
	c.mu.Lock()
	prev := selectedKey(c.table, c.items, connectorKey)
	c.all = list
	c.items = filterRows(list, c.q, func(ct types.Connector) []string {
		return []string{ct.Name, ct.TypeID, kindFor(ct.TypeID), ct.Schedule, ct.Status, strings.Join(ct.Tags, ",")}
	})
	c.applySort()
	c.render()
	reselect(c.table, c.items, connectorKey, prev)
	c.mu.Unlock()
	if c.onSelect != nil { // an open describe panel follows the fresh row
		c.onSelect()
	}
}

func (c *Sources) applySort() {
	idx, desc, sorted := c.sortCol.Active()
	if !sorted {
		return
	}
	sort.SliceStable(c.items, func(i, j int) bool {
		// Descending swaps the OPERANDS, never negates the result: negating would report
		// less(i,j) and less(j,i) both true for equal keys, breaking SliceStable's
		// strict-weak-ordering contract and reversing every tie.
		a, b := c.items[i], c.items[j]
		if desc {
			a, b = b, a
		}
		switch idx {
		case 0:
			return a.Name < b.Name
		case 1:
			return a.TypeID < b.TypeID
		case 2:
			return a.Schedule < b.Schedule
		case 3:
			return a.Enabled && !b.Enabled
		}
		return false
	})
}

func (c *Sources) render() {
	c.table.Clear()
	c.setHeader()
	if len(c.items) == 0 {
		c.table.SetCell(1, 0, tview.NewTableCell(
			"No sources yet — press : then type 'new' to create one from a living example.").
			SetSelectable(false).SetExpansion(6))
		return
	}
	for i, it := range c.items {
		row := i + 1
		enabled := "yes"
		if !it.Enabled {
			enabled = "no"
		}
		// Name cell: glyph + color by connector status
		nameCell := dataCell(connectorGlyph(it) + " " + it.Name).SetExpansion(1)
		if style.ColorsEnabled() {
			nameCell.SetTextColor(connectorColor(it))
		}
		c.table.SetCell(row, 0, nameCell)
		c.table.SetCell(row, 1, dataCell(it.TypeID).SetExpansion(1))
		c.table.SetCell(row, 2, dataCell(kindFor(it.TypeID)).SetExpansion(1))
		c.table.SetCell(row, 3, tview.NewTableCell(fmt.Sprintf("%d", it.TenantID)).SetExpansion(1))
		c.table.SetCell(row, 4, dataCell(it.Schedule).SetExpansion(1))
		c.table.SetCell(row, 5, tview.NewTableCell(enabled).SetExpansion(1))
		c.table.SetCell(row, 6, dataCell(strings.Join(it.Tags, ",")).SetExpansion(1))
	}
}

// SetTextFilter narrows the table to the connectors holding q (#1120); "" shows them all.
func (c *Sources) SetTextFilter(q string) { c.q = q; c.renderList(c.all, nil) }

func connectorKey(ct types.Connector) string { return ct.ID }

// kindFor derives the short connector kind from typeId.
func kindFor(typeID string) string {
	switch {
	case strings.HasPrefix(typeID, "rest"):
		return "rest"
	case strings.HasPrefix(typeID, "jdbc"):
		return "jdbc"
	case strings.HasPrefix(typeID, "csv"):
		return "csv"
	default:
		return typeID
	}
}

// connectorColor maps (enabled, lastRun.status) → display color.
func connectorColor(ct types.Connector) tcell.Color {
	if !ct.Enabled {
		return tcell.NewRGBColor(0x6B, 0x72, 0x80) // gray — disabled
	}
	switch runStatusKind(ct.Status) {
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
	return tcell.ColorDefault // unknown / no run yet
}

// connectorGlyph returns the status glyph prefix.
func connectorGlyph(ct types.Connector) string {
	if !ct.Enabled {
		return "─"
	}
	switch runStatusKind(ct.Status) {
	case KindCompleted, KindFailed:
		return "●"
	case KindQueued:
		return "◌"
	case KindRunning:
		return "▶"
	case KindPartial:
		return "◐"
	case KindCancelled:
		return "─"
	}
	return " "
}

func (c *Sources) selectedItem() (types.Connector, bool) {
	row, _ := c.table.GetSelection()
	if row < 1 || row-1 >= len(c.items) {
		return types.Connector{}, false
	}
	return c.items[row-1], true
}

func (c *Sources) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	switch ev.Key() {
	case tcell.KeyCtrlR:
		c.runNow()
		return nil
	case tcell.KeyCtrlP:
		c.pause()
		return nil
	case tcell.KeyEnter:
		if c.onEnter != nil {
			if sel, ok := c.selectedItem(); ok {
				c.onEnter(sel.ID, sel.Name, kindFor(sel.TypeID))
			}
		}
		return nil
	case tcell.KeyDelete:
		c.delete()
		return nil
	}
	switch ev.Rune() {
	case 'e':
		c.editYAML()
		return nil
	case 'r':
		c.Refresh()
		return nil
	case 'D':
		if sel, ok := c.selectedItem(); ok && c.onDead != nil {
			c.onDead(sel.ID)
		}
		return nil
	case 'R': // modifier-free alias of ^R — Ctrl chords are awkward on some mac keyboards (carnet #5)
		c.runNow()
		return nil
	case 's':
		c.sortCol.Next()
		c.Refresh()
		return nil
	case 't':
		row, _ := c.table.GetSelection()
		if row < 1 || row-1 >= len(c.items) {
			return ev
		}
		if c.onScopeEdit != nil {
			c.onScopeEdit(c.items[row-1].ID, c.items[row-1].Tags)
		}
		return nil
		// 'd' is NOT intercepted here — it bubbles up to App.bindGlobalKeys → toggleDescribe
	}
	return ev
}

func (c *Sources) editYAML() {
	sel, ok := c.selectedItem()
	if !ok {
		return
	}
	// The GET runs off the loop: a hub that held it held every key (#1156). The editor opens in the
	// follow-up, on the loop.
	c.inFlight.background(c.app, "e", c.toast, func() func() {
		current, err := c.client.ConnectorGet(sel.ID)
		return func() {
			if err != nil {
				c.signalError(fmt.Sprintf("get: %v", err))
				return
			}
			c.edit(sel, current)
		}
	})
}

// edit opens $EDITOR on the connector e fetched, then applies what the editor changed.
func (c *Sources) edit(sel types.Connector, current *types.Connector) {
	data, err := yaml.Marshal(current)
	if err != nil {
		c.signalError(fmt.Sprintf("marshal: %v", err))
		return
	}
	original := string(data)
	c.suspend(func() {
		updated, changed, err := editor.EditYAML(sel.Name+".yaml", original)
		if err != nil {
			c.signalError(fmt.Sprintf("editor: %v", err))
			return
		}
		if !changed {
			return
		}
		var payload map[string]any
		if err := yaml.Unmarshal([]byte(updated), &payload); err != nil {
			c.signalError(fmt.Sprintf("parse: %v", err))
			return
		}
		if _, err := c.client.ConnectorUpdate(sel.ID, payload); err != nil {
			c.signalError(fmt.Sprintf("update: %v", err))
			return
		}
	})
	c.Refresh()
}

func (c *Sources) runNow() {
	if sel, ok := c.selectedItem(); ok {
		c.inFlight.background(c.app, "^R", c.toast, func() func() {
			_, err := c.client.ConnectorRun(sel.ID)
			return func() {
				if err != nil {
					c.signalError(fmt.Sprintf("run: %v", err))
					return
				}
				// Silence on success cost the rehearsal a freeze-by-retry (carnet #4):
				// the human MUST see that ^R landed.
				say(c.toast, "run scheduled: "+sel.Name)
			}
		})
	}
}

func (c *Sources) pause() {
	if sel, ok := c.selectedItem(); ok {
		c.inFlight.background(c.app, "^P", c.toast, func() func() {
			var err error
			if sel.Enabled {
				err = c.client.ConnectorPause(sel.ID)
			} else {
				err = c.client.ConnectorResume(sel.ID)
			}
			return func() {
				if err != nil {
					c.signalError(fmt.Sprintf("pause/resume: %v", err))
				}
				c.Refresh()
			}
		})
	}
}

func (c *Sources) delete() {
	sel, ok := c.selectedItem()
	if !ok || c.confirm == nil {
		return
	}
	// The dry run first, as lm connector delete (#1218): the entity that blocks the delete is named
	// before any question, and the question can say what goes. sel is captured now: the answer
	// deletes the connector the question named, whatever a live refresh did to the table meanwhile.
	c.inFlight.background(c.app, "Del", c.toast, func() func() {
		takes, err := c.client.ConnectorDeleteDryRun(sel.ID)
		return func() {
			switch {
			case c.isFront != nil && !c.isFront(): // left — or the whole hub left (:context)
				say(c.toast, "Del: :sources was left before the hub answered — nothing deleted")
			case err != nil:
				c.signalError(fmt.Sprintf("delete: %v", err))
			default:
				c.confirmDelete(sel, takes)
			}
		}
	})
}

func (c *Sources) confirmDelete(sel types.Connector, takes types.DeleteTakes) {
	q := fmt.Sprintf("Delete connector %q? Goes with it: %s.", sel.Name, output.ConnectorDeleteTakes(takes))
	c.confirm(q, "Delete", func() {
		c.inFlight.background(c.app, "Del", c.toast, func() func() {
			err := c.client.ConnectorDelete(sel.ID)
			return func() {
				if err != nil {
					c.signalError(fmt.Sprintf("delete: %v", err))
					return
				}
				c.Refresh()
			}
		})
	})
}

func (c *Sources) signalError(msg string) {
	if c.onError != nil {
		c.onError(msg)
	}
}

func (c *Sources) SelectedID() string {
	if sel, ok := c.selectedItem(); ok {
		return sel.ID
	}
	return ""
}

// Describable

// SelectedDescribe renders the listed row as it is. It runs on the event loop, on every arrow key
// while the panel is open, so it must never reach the network — and it has no need to: the list
// and the get serve the same ConnectorAdminDto.fromWithRun shape, config and last run included
// (#891).
func (c *Sources) SelectedDescribe() (string, string, bool) {
	full, ok := c.selectedItem()
	if !ok {
		return "", "", false
	}
	esc := tview.Escape
	var b strings.Builder
	fmt.Fprintf(&b, "id:        %s\n", esc(full.ID))
	fmt.Fprintf(&b, "tenant:    %d\n", full.TenantID)
	fmt.Fprintf(&b, "type_id:   %s\n", esc(full.TypeID))
	fmt.Fprintf(&b, "schedule:  %s\n", esc(full.Schedule))
	// Beside the cron, never instead of it: a panel showing one without the other answers half of
	// "when does this run?" (#499).
	fmt.Fprintf(&b, "window:    %s\n", esc(full.Window))
	enabled := "✗ false"
	if full.Enabled {
		enabled = "✓ true"
	}
	fmt.Fprintf(&b, "enabled:   %s\n", enabled)
	if full.Status != "" {
		fmt.Fprintf(&b, "\n── last run ──\n")
		fmt.Fprintf(&b, "status:    %s %s\n", connectorGlyph(full), esc(full.Status))
		if full.LastRunAt != nil {
			fmt.Fprintf(&b, "at:        %s\n", output.HumanTimeSeconds(full.LastRunAt))
		}
	}
	if len(full.Config) > 0 {
		fmt.Fprintf(&b, "\n── config ──\n")
		yml, err := yaml.Marshal(full.Config)
		if err == nil {
			b.WriteString(esc(string(yml)))
		}
	}
	return full.Name, b.String(), true
}

func (c *Sources) OnSelectionChange(cb func()) { c.onSelect = cb }

// YankLater: y copies the connector's full YAML — symmetric with e's editor input. The GET runs off
// the loop under y's inFlight key, as e's does (#1156): a hub that held it held every key (#1158). The
// yank happens in the follow-up, on the loop.
func (c *Sources) YankLater(yank func(text string)) {
	sel, ok := c.selectedItem()
	if !ok {
		return
	}
	c.inFlight.background(c.app, "y", c.toast, func() func() {
		full, err := c.client.ConnectorGet(sel.ID)
		var data []byte
		if err == nil {
			data, err = yaml.Marshal(full)
		}
		return func() {
			if err != nil {
				c.signalError(fmt.Sprintf("y: %v", err))
				return
			}
			yank(string(data))
		}
	})
}
