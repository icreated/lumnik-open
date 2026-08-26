package resources

import (
	"fmt"
	"sort"
	"strings"
	"sync"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/editor"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
	"gopkg.in/yaml.v3"
)

type Sources struct {
	table     *tview.Table
	client    *client.Client
	items     []types.Connector
	app       *tview.Application
	mu        sync.Mutex
	onError   func(string)
	toast     func(string)
	sortCol   *style.ColumnSort
	onSelect  func()
	onEnter   func(connectorID, kind string) // wired by App: REST → :endpoints, others → :runs
	onTagEdit func(id string, current []string)
}

func NewSources(app *tview.Application, c *client.Client, onError, toast func(string)) *Sources {
	t := tview.NewTable().SetBorders(false).SetSelectable(true, false)
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
func (c *Sources) SetOnEnter(fn func(connectorID, kind string)) { c.onEnter = fn }

// SetOnTagEdit is wired by the App so pressing `t` opens a tag-edit modal.
func (c *Sources) SetOnTagEdit(fn func(string, []string)) { c.onTagEdit = fn }

func (c *Sources) View() tview.Primitive { return c.table }

func (c *Sources) Hints() string {
	return "e: edit  t: tags  d: describe  ^R: run  ^P: pause  Del: delete  y: yank  s: sort  Enter: → endpoints/runs  r: refresh"
}

func (c *Sources) setHeader() {
	headers := []string{
		c.sortCol.HeaderLabel(0),
		c.sortCol.HeaderLabel(1),
		"KIND",
		"TENANT",
		c.sortCol.HeaderLabel(2),
		c.sortCol.HeaderLabel(3),
		"TAGS",
	}
	for i, h := range headers {
		c.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetExpansion(1).SetTextColor(tcell.ColorYellow))
	}
}

func (c *Sources) Refresh() {
	c.mu.Lock()
	defer c.mu.Unlock()
	list, err := c.client.ConnectorList()
	if err != nil {
		c.signalError(fmt.Sprintf("list: %v", err))
		return
	}
	c.items = list
	c.applySort()
	c.render()
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
	case KindRunning:
		return tcell.NewRGBColor(0xFF, 0x8C, 0x00) // amber
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
	case KindRunning:
		return "▶"
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
				c.onEnter(sel.ID, kindFor(sel.TypeID))
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
		if c.onTagEdit != nil {
			c.onTagEdit(c.items[row-1].ID, c.items[row-1].Tags)
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
	current, err := c.client.ConnectorGet(sel.ID)
	if err != nil {
		c.signalError(fmt.Sprintf("get: %v", err))
		return
	}
	data, err := yaml.Marshal(current)
	if err != nil {
		c.signalError(fmt.Sprintf("marshal: %v", err))
		return
	}
	original := string(data)
	SuspendEditor(c.app, c.signalError, func() {
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
		if _, err := c.client.ConnectorRun(sel.ID); err != nil {
			c.signalError(fmt.Sprintf("run: %v", err))
			return
		}
		// Silence on success cost the rehearsal a freeze-by-retry (carnet #4):
		// the human MUST see that ^R landed.
		if c.toast != nil {
			c.toast("run scheduled: " + tview.Escape(sel.Name))
		}
	}
}

func (c *Sources) pause() {
	if sel, ok := c.selectedItem(); ok {
		var err error
		if sel.Enabled {
			err = c.client.ConnectorPause(sel.ID)
		} else {
			err = c.client.ConnectorResume(sel.ID)
		}
		if err != nil {
			c.signalError(fmt.Sprintf("pause/resume: %v", err))
		}
		c.Refresh()
	}
}

func (c *Sources) delete() {
	if sel, ok := c.selectedItem(); ok {
		if err := c.client.ConnectorDelete(sel.ID); err != nil {
			c.signalError(fmt.Sprintf("delete: %v", err))
			return
		}
		c.Refresh()
	}
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

func (c *Sources) SelectedDescribe() (string, string, bool) {
	sel, ok := c.selectedItem()
	if !ok {
		return "", "", false
	}
	full, err := c.client.ConnectorGet(sel.ID)
	if err != nil {
		return sel.Name, "error fetching detail: " + tview.Escape(err.Error()), true
	}
	esc := tview.Escape
	var b strings.Builder
	fmt.Fprintf(&b, "id:        %s\n", esc(full.ID))
	fmt.Fprintf(&b, "tenant:    %d\n", full.TenantID)
	fmt.Fprintf(&b, "type_id:   %s\n", esc(full.TypeID))
	fmt.Fprintf(&b, "schedule:  %s\n", esc(full.Schedule))
	enabled := "✗ false"
	if full.Enabled {
		enabled = "✓ true"
	}
	fmt.Fprintf(&b, "enabled:   %s\n", enabled)
	if full.Status != "" {
		fmt.Fprintf(&b, "\n── last run ──\n")
		fmt.Fprintf(&b, "status:    %s %s\n", connectorGlyph(*full), esc(full.Status))
		if full.LastRunAt != nil {
			fmt.Fprintf(&b, "at:        %s\n", full.LastRunAt.Format("2006-01-02 15:04:05"))
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

// Yankable: full YAML of the connector — symmetric with `e` editor input.

func (c *Sources) SelectedYank() (string, bool) {
	sel, ok := c.selectedItem()
	if !ok {
		return "", false
	}
	full, err := c.client.ConnectorGet(sel.ID)
	if err != nil {
		return "", false
	}
	data, err := yaml.Marshal(full)
	if err != nil {
		return "", false
	}
	return string(data), true
}
