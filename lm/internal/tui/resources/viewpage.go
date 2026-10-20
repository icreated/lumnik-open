package resources

import (
	"fmt"
	"os"
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/editor"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

// detailBody renders one record's detail: aligned fields, then the _conflicts
// ledger — which source said what, or an explicit "no conflicts" line.
// Server-derived strings are tview-escaped (a value containing '[red]' must
// render literally); alignment counts runes, not bytes.
func detailBody(d client.ViewDetailResult) string {
	esc := tview.Escape
	var b strings.Builder
	fmt.Fprintf(&b, "[yellow]%s — %s[white]\n\n", esc(d.Title), esc(d.Key))
	width := 0
	for _, f := range d.Fields {
		if n := utf8.RuneCountInString(f.Label); n > width {
			width = n
		}
	}
	for _, f := range d.Fields {
		pad := strings.Repeat(" ", width-utf8.RuneCountInString(f.Label))
		fmt.Fprintf(&b, "%s%s  %s\n", esc(f.Label), pad, esc(f.Value))
	}
	b.WriteString("\n[yellow]conflicts[white]\n")
	if len(d.Conflicts) == 0 {
		b.WriteString("no conflicts or ambiguities\n")
		return b.String()
	}
	ledgerLines(&b, d.Conflicts)
	return b.String()
}

// pushRecent puts path at the front, deduplicated, capped — the session recents.
func pushRecent(list []string, path string, capN int) []string {
	out := []string{path}
	for _, p := range list {
		if p != path && len(out) < capN {
			out = append(out, p)
		}
	}
	return out
}

// viewPathHint is both the placeholder and the Enter-on-empty default (the living example).
const viewPathHint = "docs/demo/wow-customer/clients-view.yaml"

type viewPageState int

const (
	viewOpen viewPageState = iota
	viewList
	viewDetail
	viewGenIssues
)

// ViewPage opens a kind:View manifest FILE (the file-held philosophy — no
// registry) and renders it: list with server-side filters, Enter drills into
// the record's detail with the _conflicts ledger.
type ViewPage struct {
	client     *client.Client
	app        *tview.Application
	pages      *tview.Pages
	openFlex   *tview.Flex
	pathInput  *tview.InputField
	recentsUI  *tview.Table
	list       *tview.Table
	detail     *tview.TextView
	genIssues  *tview.TextView
	genInput   *tview.InputField
	state      viewPageState
	manifest   []byte
	path       string
	filters    []string
	recents    []string
	result     *client.ViewResult
	suspend    func(func())
	editFn     func(hint, content string) (string, bool, error) // seam for tests; defaults to editor.EditYAML
	scope      func() string                                    // session scope getter (a.currentScope)
	isFront    func() bool                                      // true when :view is the visible page; gates the async generate callback
	spawn      func(func())                                     // seam: `go` in prod, inline in tests
	enqueue    func(func())                                     // seam: QueueUpdateAfterResume in prod, inline in tests
	generating bool                                             // single-flight; event goroutine only
	inFlight   inFlight                                         // the guard on Enter and a (#1171)
	renderGen  uint64                                           // the latest render asked: only its answer lands; event goroutine only
	genBuffer  string                                           // the generated YAML being edited — survives every failure
	genPhrase  string                                           // the sentence, kept when generation fails
	showError  func(string)
	toast      func(string)
	setHints   func(string)
	onSelect   func()
}

func NewViewPage(app *tview.Application, c *client.Client, suspend func(func()), scope func() string,
	isFront func() bool, showError, toast func(string), setHints func(string)) *ViewPage {
	v := &ViewPage{client: c, app: app, suspend: suspend, scope: scope, isFront: isFront,
		showError: showError, toast: toast, setHints: setHints,
		editFn:    editor.EditYAML,
		spawn:     func(f func()) { go f() },
		pathInput: tview.NewInputField().SetLabel("view file: ").SetFieldWidth(60),
		recentsUI: tview.NewTable().SetSelectable(true, false),
		list:      newListTable(),
		detail:    tview.NewTextView().SetDynamicColors(true),
		genIssues: tview.NewTextView().SetDynamicColors(true),
		pages:     tview.NewPages()}
	// Off the loop only (generate's spawn): an update under an open $EDITOR waits for it to close (#1158).
	v.enqueue = func(f func()) { QueueUpdateAfterResume(app, f) }
	v.pathInput.SetPlaceholder(viewPathHint + "   (Enter to open this suggestion)")
	v.openFlex = tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(v.pathInput, 1, 0, true).
		AddItem(v.recentsUI, 0, 1, false)
	style.ApplyChrome(v.list.Box)
	style.ApplyTableSelection(v.list)
	style.ApplyChrome(v.detail.Box)
	v.detail.SetBorder(true)
	v.pages.AddPage("open", v.openFlex, true, true)
	v.pages.AddPage("list", v.list, true, false)
	v.pages.AddPage("detail", v.detail, true, false)
	style.ApplyChrome(v.genIssues.Box)
	v.genIssues.SetTitle(" generated view — e: re-edit · Esc: abandon (buffer kept) ").SetBorder(true)
	v.pages.AddPage("genissues", v.genIssues, true, false)
	v.genIssues.SetInputCapture(v.handleGenIssuesKey)

	v.pathInput.SetDoneFunc(func(key tcell.Key) {
		switch key {
		case tcell.KeyEnter:
			path := strings.TrimSpace(v.pathInput.GetText())
			if path == "" {
				path = viewPathHint // Enter on the empty field opens the suggested example
			}
			v.open(path)
		case tcell.KeyEscape, tcell.KeyTab, tcell.KeyBacktab:
			v.app.SetFocus(v.recentsUI) // blur so global keys work (carnet #13)
		}
	})
	v.recentsUI.SetSelectedFunc(func(row, col int) {
		if row >= 0 && row < len(v.recents) {
			v.open(v.recents[row])
		}
	})
	v.recentsUI.SetInputCapture(func(ev *tcell.EventKey) *tcell.EventKey {
		if ev.Rune() == 'g' {
			v.promptGenerate()
			return nil
		}
		return ev
	})
	v.pathInput.SetInputCapture(func(ev *tcell.EventKey) *tcell.EventKey {
		if ev.Key() == tcell.KeyCtrlG {
			v.promptGenerate()
			return nil
		}
		return ev
	})
	v.list.SetInputCapture(v.handleListKey)
	v.detail.SetInputCapture(func(ev *tcell.EventKey) *tcell.EventKey {
		if ev.Key() == tcell.KeyEscape {
			v.toState(viewList)
			return nil
		}
		return ev
	})
	v.list.SetSelectionChangedFunc(func(row, col int) {
		if v.onSelect != nil {
			v.onSelect()
		}
	})
	return v
}

func (v *ViewPage) View() tview.Primitive { return v.pages }

func (v *ViewPage) Hints() string {
	switch v.state {
	case viewList:
		return "Enter: detail (_conflicts) · f: add filter · F: clear filters · g: generate (AI) · a: apply to hub · o: open another file · r: refresh"
	case viewDetail:
		return "Esc: back to the list"
	case viewGenIssues:
		return "e: re-edit · Esc: abandon (buffer kept in a temp file)"
	default:
		return "Enter: render the view file · g/^G: generate from a sentence (AI) · (render first — then a: apply to hub)"
	}
}

func (v *ViewPage) Refresh() {
	switch v.state {
	case viewList:
		v.render(v.manifest, v.filters, nil)
	default:
		v.renderRecents()
	}
}

func (v *ViewPage) toState(s viewPageState) {
	v.state = s
	if v.pages != nil {
		switch s {
		case viewOpen:
			v.renderRecents() // 'o' must show a fresh recents list
			v.pages.SwitchToPage("open")
		case viewList:
			v.pages.SwitchToPage("list")
		case viewDetail:
			v.pages.SwitchToPage("detail")
		case viewGenIssues:
			v.pages.SwitchToPage("genissues")
		}
	}
	// A render lands off the loop: one landing after the user left :view must not write its
	// hints over the page now in front.
	if v.setHints != nil && (v.isFront == nil || v.isFront()) {
		v.setHints(v.Hints())
	}
}

func (v *ViewPage) renderRecents() {
	if v.recentsUI == nil {
		return
	}
	v.recentsUI.Clear()
	for i, p := range v.recents {
		v.recentsUI.SetCell(i, 0, dataCell(p))
	}
}

// open loads and renders a view manifest file; errors stay on the open form.
func (v *ViewPage) open(path string) {
	if path == "" {
		return
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if v.showError != nil {
			v.showError("open: " + err.Error())
		}
		return
	}
	v.render(data, nil, func() { // a render error is shown and we stay where we are
		v.path = path
		v.recents = pushRecent(v.recents, path, 8)
		v.toState(viewList)
	})
}

// OpenManifest renders a saved view the hub served (:views Enter, #1119) — the file path's twin.
func (v *ViewPage) OpenManifest(name string, data []byte) {
	v.render(data, nil, func() {
		v.path = "hub:" + name
		v.toState(viewList)
	})
}

// render renders manifest under filters off the event loop — an unbounded query (#1171). Only a
// success becomes the page's manifest and filters and runs landed; a refusal is said verbatim and
// leaves the rows untouched — never a lying empty table. The latest render wins: an answer landing
// after a newer one was asked is dropped.
func (v *ViewPage) render(manifest []byte, filters []string, landed func()) {
	v.renderGen++
	gen := v.renderGen
	background(v.app, func() func() {
		res, err := v.client.ViewRender(manifest, filters)
		return func() {
			if gen != v.renderGen {
				return
			}
			if err != nil {
				say(v.showError, err.Error())
				return
			}
			v.manifest, v.filters = manifest, filters
			v.fill(res)
			if landed != nil {
				landed()
			}
		}
	})
}

// fill populates the list table from an already-fetched render result.
func (v *ViewPage) fill(res *client.ViewResult) {
	v.result = res
	v.list.Clear()
	title := res.Title
	if len(v.filters) > 0 {
		title += " · " + strings.Join(v.filters, " · ")
	}
	v.list.SetTitle(" " + title + " ").SetBorder(true)
	for c, col := range res.Columns {
		// labels come from the server card — data, not markup
		v.list.SetCell(0, c, dataCell(strings.ToUpper(col.Label)).
			SetSelectable(false).SetTextColor(tcell.ColorYellow))
	}
	for r, row := range res.Rows {
		for c, cell := range row {
			v.list.SetCell(r+1, c, dataCell(cell))
		}
	}
}

func (v *ViewPage) selectedRow() ([]string, bool) {
	if v.result == nil {
		return nil, false
	}
	row, _ := v.list.GetSelection()
	if row < 1 || row-1 >= len(v.result.Rows) {
		return nil, false
	}
	return v.result.Rows[row-1], true
}

func (v *ViewPage) handleListKey(ev *tcell.EventKey) *tcell.EventKey {
	switch {
	case ev.Key() == tcell.KeyEnter:
		if row, ok := v.selectedRow(); ok && len(row) > 0 {
			v.openDetail(row[0])
		}
		return nil
	case ev.Rune() == 'f':
		v.promptFilter()
		return nil
	case ev.Rune() == 'F':
		v.render(v.manifest, nil, nil)
		return nil
	case ev.Rune() == 'o':
		v.toState(viewOpen)
		return nil
	case ev.Rune() == 'r':
		v.render(v.manifest, v.filters, nil)
		return nil
	case ev.Rune() == 'g':
		v.promptGenerate()
		return nil
	case ev.Rune() == 'a':
		v.applyToHub()
		return nil
	}
	return ev
}

// promptFilter swaps the list for a one-line input; a refused filter is NOT
// kept (the server's refusal already told the user why).
func (v *ViewPage) promptFilter() {
	input := tview.NewInputField().SetLabel("filter (col=v · col~text · col>v): ").SetFieldWidth(40)
	flex := tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(input, 1, 0, true).
		AddItem(v.list, 0, 1, false)
	v.pages.AddPage("filter", flex, true, true)
	input.SetDoneFunc(func(key tcell.Key) {
		v.pages.RemovePage("filter")
		if key != tcell.KeyEnter {
			return
		}
		f := strings.TrimSpace(input.GetText())
		if f == "" {
			return
		}
		// refused → not kept: only a render that succeeded keeps its filters, and the failed
		// one left rows/result untouched, so no second fetch is needed
		v.render(v.manifest, append(slices.Clone(v.filters), f), nil)
	})
}

// openDetail drills into the record keyed key, off the event loop like render (#1171).
func (v *ViewPage) openDetail(key string) {
	manifest := v.manifest
	v.inFlight.background(v.app, "Enter", v.toast, func() func() {
		d, err := v.client.ViewDetail(manifest, key)
		return func() {
			if err != nil {
				say(v.showError, err.Error())
				return
			}
			v.detail.SetText(detailBody(*d))
			v.toState(viewDetail)
		}
	})
}

// Describable — the selected row, column by column.
func (v *ViewPage) SelectedDescribe() (string, string, bool) {
	if v.list == nil || v.result == nil {
		return "", "", false
	}
	row, ok := v.selectedRow()
	if !ok || len(row) == 0 {
		return "", "", false
	}
	esc := tview.Escape
	var b strings.Builder
	for c, col := range v.result.Columns {
		if c < len(row) {
			fmt.Fprintf(&b, "%s: %s\n", esc(col.Label), esc(row[c]))
		}
	}
	return row[0], b.String(), true
}
func (v *ViewPage) OnSelectionChange(cb func()) { v.onSelect = cb }

// Yankable — the row's key (first column).
func (v *ViewPage) SelectedYank() (string, bool) {
	row, ok := v.selectedRow()
	if !ok || len(row) == 0 {
		return "", false
	}
	return row[0], true
}
