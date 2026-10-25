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

// ManifestRow is one stored manifest as a manifest page lists it.
type ManifestRow struct {
	Name    string
	Cells   []string // the columns after NAME
	Enabled *bool    // nil: the kind has no enabled flag
}

// ManifestSource is what one kind of stored manifest offers: its list, and the gestures the hub
// has a door for. A nil func is a gesture the kind does not have.
type ManifestSource struct {
	Title   string   // the page, without the colon
	Kind    string   // the :new kind its manifest re-opens as
	Columns []string // after NAME
	List    func() ([]ManifestRow, error)
	Get     func(name string) ([]byte, error)
	Delete  func(name string) error
	Toggle  func(name string, enabled bool) error
}

// Manifests is a page over stored manifests — :apps, :workflows, :views (#1119) and :processes
// (#1139): list them, narrow them (/), yank a name (y) and, where the kind has the door, re-open
// one's stored manifest in :new (e — :apps' is rebuilt from the app's fields), enable/disable it
// (^P — :workflows, :processes) and delete it after a yes (Del). One page for four kinds: each
// differs only in its doors.
type Manifests struct {
	app     *tview.Application
	src     ManifestSource
	table   *tview.Table
	mu      sync.Mutex
	offLoop offLoop
	inFlight inFlight // the guard on e, Enter, ^P and Del (#1141)
	items   []ManifestRow
	all     []ManifestRow // every row the hub listed; items is what the / filter shows
	textFilter
	toast    func(string)
	onError  func(string)
	confirm  ConfirmAs
	onEdit   func(kind string, yaml []byte)
	onOpen   func(name string, yaml []byte)
	onSelect func()
}

func NewManifests(app *tview.Application, src ManifestSource, toast, onError func(string)) *Manifests {
	t := newListTable()
	t.SetTitle(" :" + src.Title + " ").SetBorder(true)
	style.ApplyChrome(t.Box)
	style.ApplyTableSelection(t)
	p := &Manifests{app: app, src: src, table: t, toast: toast, onError: onError}
	t.SetInputCapture(p.handleKey)
	t.SetSelectionChangedFunc(func(row, col int) {
		if p.onSelect != nil {
			p.onSelect()
		}
	})
	return p
}

// Wired by the App.
func (p *Manifests) SetConfirm(fn ConfirmAs)                     { p.confirm = fn }
func (p *Manifests) SetOnEdit(fn func(kind string, yaml []byte)) { p.onEdit = fn }
func (p *Manifests) SetOnOpen(fn func(name string, yaml []byte)) { p.onOpen = fn }
func (p *Manifests) View() tview.Primitive                       { return p.table }

func (p *Manifests) Hints() string {
	var h []string
	if p.onOpen != nil && p.src.Get != nil {
		h = append(h, "Enter: open")
	}
	if p.src.Get != nil && p.onEdit != nil {
		h = append(h, "e: edit the stored manifest")
	}
	if p.src.Toggle != nil {
		h = append(h, "^P: enable/disable")
	}
	if p.src.Delete != nil && p.confirm != nil {
		h = append(h, "Del: delete")
	}
	return strings.Join(append(h, "/: filter", "d: describe", "y: yank", "r: refresh"), "  ")
}

// Refresh lists off the event loop; the live ticker calls it while its page is in front (#891).
func (p *Manifests) Refresh() {
	p.offLoop.run(p.app, func() func() {
		rows, err := p.src.List()
		return func() {
			p.mu.Lock()
			if err != nil {
				// the rows are gone from the table: the error takes their place
				p.all, p.items = nil, nil
				p.table.Clear()
				p.table.SetCell(0, 0, dataCell("error: "+err.Error()))
				say(p.onError, fmt.Sprintf("%s: %v", p.src.Title, err))
			} else {
				p.all = rows
				p.show()
			}
			p.mu.Unlock()
			if p.onSelect != nil { // an open describe panel follows the fresh row, or its absence
				p.onSelect()
			}
		}
	})
}

// SetTextFilter narrows the table to the rows holding q (#1139); "" shows them all.
func (p *Manifests) SetTextFilter(q string) {
	p.mu.Lock()
	p.q = q
	p.show()
	p.mu.Unlock()
}

// show applies the / filter to every listed row and renders it, keeping the selection by name.
func (p *Manifests) show() {
	prev := selectedKey(p.table, p.items, manifestKey)
	p.items = filterRows(p.all, p.q, p.cells)
	p.render()
	reselect(p.table, p.items, manifestKey, prev)
}

func manifestKey(r ManifestRow) string { return r.Name }

// cells is one row as the table shows it — what / searches.
func (p *Manifests) cells(it ManifestRow) []string {
	cells := append([]string{it.Name}, it.Cells...)
	if p.src.Toggle != nil && it.Enabled != nil {
		cells = append(cells, yesNo(*it.Enabled, "yes", "no"))
	}
	return cells
}

func (p *Manifests) render() {
	cols := append([]string{"name"}, p.src.Columns...)
	if p.src.Toggle != nil {
		cols = append(cols, "enabled")
	}
	headerRow(p.table, cols)
	if len(p.items) == 0 {
		msg := "None declared — create one from :new."
		if len(p.all) > 0 {
			msg = "No row holds the filter — Esc clears it."
		}
		p.table.SetCell(1, 0, tview.NewTableCell(msg).SetSelectable(false))
		return
	}
	for r, it := range p.items {
		for c, v := range p.cells(it) {
			p.table.SetCell(r+1, c, dataCell(v).SetExpansion(1))
		}
	}
}

func (p *Manifests) selected() (ManifestRow, bool) {
	row, _ := p.table.GetSelection()
	if row < 1 || row-1 >= len(p.items) {
		return ManifestRow{}, false
	}
	return p.items[row-1], true
}

func (p *Manifests) handleKey(ev *tcell.EventKey) *tcell.EventKey {
	it, ok := p.selected()
	switch {
	case ev.Rune() == 'r':
		p.Refresh()
		return nil
	case !ok:
		return ev
	case ev.Key() == tcell.KeyEnter && p.onOpen != nil && p.src.Get != nil:
		p.fetch("Enter", it.Name, p.onOpen)
		return nil
	case ev.Rune() == 'e' && p.src.Get != nil && p.onEdit != nil:
		p.fetch("e", it.Name, func(_ string, yaml []byte) { p.onEdit(p.src.Kind, yaml) })
		return nil
	case ev.Key() == tcell.KeyCtrlP && p.src.Toggle != nil && it.Enabled != nil:
		on := !*it.Enabled
		p.inFlight.background(p.app, "^P", p.toast, func() func() {
			err := p.src.Toggle(it.Name, on)
			return func() {
				if err != nil {
					say(p.onError, fmt.Sprintf("enable/disable %s: %v", it.Name, err))
					return
				}
				say(p.toast, yesNo(on, "enabled", "disabled")+": "+it.Name)
				p.Refresh()
			}
		})
		return nil
	case ev.Key() == tcell.KeyDelete && p.src.Delete != nil && p.confirm != nil:
		if p.inFlight.busy("Del", p.toast) { // no question for a press that would be dropped
			return nil
		}
		p.confirm(fmt.Sprintf("Delete %s %q?", p.src.Kind, it.Name), "Delete", func() {
			p.inFlight.background(p.app, "Del", p.toast, func() func() {
				err := p.src.Delete(it.Name)
				return func() {
					if err != nil {
						say(p.onError, fmt.Sprintf("delete %s: %v", it.Name, err))
						return
					}
					say(p.toast, "deleted: "+it.Name)
					p.Refresh()
				}
			})
		})
		return nil
	}
	return ev
}

// fetch reads one stored manifest off the loop and hands it to then on it; another press of key
// is dropped meanwhile.
func (p *Manifests) fetch(key, name string, then func(name string, yaml []byte)) {
	p.inFlight.background(p.app, key, p.toast, func() func() {
		yaml, err := p.src.Get(name)
		return func() {
			if err != nil {
				say(p.onError, fmt.Sprintf("%s: %v", name, err))
				return
			}
			then(name, yaml)
		}
	})
}

// Describable — the row as listed.

func (p *Manifests) SelectedDescribe() (string, string, bool) {
	it, ok := p.selected()
	if !ok {
		return "", "", false
	}
	var b strings.Builder
	for i, c := range p.src.Columns {
		if i < len(it.Cells) {
			fmt.Fprintf(&b, "%-10s %s\n", tview.Escape(c)+":", tview.Escape(it.Cells[i]))
		}
	}
	if it.Enabled != nil {
		fmt.Fprintf(&b, "%-10s %v\n", "enabled:", *it.Enabled)
	}
	return it.Name, b.String(), true
}

func (p *Manifests) OnSelectionChange(cb func()) { p.onSelect = cb }

// Yankable — the row's name, what every lm command about it takes.
func (p *Manifests) SelectedYank() (string, bool) {
	it, ok := p.selected()
	return it.Name, ok
}

// ProcessesSource is :processes — the declared kind:Process rules; ^P switches one off without
// losing it (#1139).
func ProcessesSource(c *client.Client) ManifestSource {
	return ManifestSource{
		Title: "processes", Kind: "Process", Columns: []string{"table", "emit"},
		List: func() ([]ManifestRow, error) {
			ps, err := c.ProcessList()
			if err != nil {
				return nil, err
			}
			rows := make([]ManifestRow, len(ps))
			for i, pr := range ps {
				on := pr.Enabled
				rows[i] = ManifestRow{Name: pr.Name, Cells: []string{pr.Table, pr.Emit}, Enabled: &on}
			}
			return rows, nil
		},
		Get:    c.ProcessManifest,
		Delete: c.ProcessDelete,
		Toggle: func(name string, on bool) error { _, err := c.ProcessSetEnabled(name, on); return err },
	}
}

// AppsSource is :apps — kind:App, the business apps the PWA offers. The hub keeps no app manifest
// text: e rebuilds one from the app's fields, which are the whole of a kind:App (#1176).
func AppsSource(c *client.Client) ManifestSource {
	return ManifestSource{
		Title: "apps", Kind: "App", Columns: []string{"title", "scope", "default", "tabs"},
		List: func() ([]ManifestRow, error) {
			raw, err := c.AppList()
			if err != nil {
				return nil, err
			}
			apps, err := client.DecodeApps(raw)
			if err != nil {
				return nil, err
			}
			rows := make([]ManifestRow, len(apps))
			for i, a := range apps {
				def, tabs := "", "(all)"
				if a.Default {
					def = "yes"
				}
				if len(a.Tabs) > 0 {
					tabs = strings.Join(a.Tabs, ",")
				}
				rows[i] = ManifestRow{Name: a.Name, Cells: []string{a.Title, a.Scope, def, tabs}}
			}
			return rows, nil
		},
		Get:    c.AppManifest,
		Delete: c.AppDelete,
	}
}

// WorkflowsSource is :workflows — the declared document lifecycles; ^P switches one off without
// losing it.
func WorkflowsSource(c *client.Client) ManifestSource {
	return ManifestSource{
		Title: "workflows", Kind: "Workflow", Columns: []string{"status column", "scope"},
		List: func() ([]ManifestRow, error) {
			wfs, err := c.ListWorkflows()
			if err != nil {
				return nil, err
			}
			rows := make([]ManifestRow, len(wfs))
			for i, w := range wfs {
				on := w.Enabled
				rows[i] = ManifestRow{Name: w.Name, Cells: []string{w.Table + "." + w.Column, w.Scope}, Enabled: &on}
			}
			return rows, nil
		},
		Get:    c.GetWorkflowManifest,
		Delete: c.DeleteWorkflow,
		Toggle: func(name string, on bool) error { _, err := c.SetWorkflowEnabled(name, on); return err },
	}
}

// ViewsSource is :views — the saved views the hub serves the PWA; Enter renders one in :view.
func ViewsSource(c *client.Client) ManifestSource {
	return ManifestSource{
		Title: "views", Kind: "View", Columns: []string{"title", "scope"},
		List: func() ([]ManifestRow, error) {
			views, err := c.ViewsList("")
			if err != nil {
				return nil, err
			}
			rows := make([]ManifestRow, len(views))
			for i, v := range views {
				rows[i] = ManifestRow{Name: v.Name, Cells: []string{v.Title, v.Scope}}
			}
			return rows, nil
		},
		Get:    c.ViewGet,
		Delete: c.ViewDelete,
	}
}

func yesNo(b bool, yes, no string) string {
	if b {
		return yes
	}
	return no
}
