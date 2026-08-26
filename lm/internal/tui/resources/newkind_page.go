package resources

import (
	"fmt"
	"strings"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/editor"
	"github.com/icreated/lumnik-open/lm/internal/tui/newkind"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/rivo/tview"
)

type newKindMode int

const (
	modePick newKindMode = iota
	modeIssues
)

// NewKind is the :new page — the authoring loop's front door. Two states:
// the kind picker (Enter opens the living example in $EDITOR) and the issues
// view (the server's field-by-field refusals; e re-edits the SAME buffer,
// Esc abandons it to a temp file — never lost).
type NewKind struct {
	client    *client.Client
	pages     *tview.Pages
	table     *tview.Table
	issues    *tview.TextView
	kinds     []newkind.KindTemplate
	mode      newKindMode
	buffer    string               // the human's edits — survives every failure
	current   newkind.KindTemplate // the kind being authored while in modeIssues
	suspend   func(func())
	showError func(string)
	toast     func(string)
	navigate  func(string)
	setHints  func(string)                                     // keeps the footer honest across the internal picker↔issues switch
	editFn    func(hint, content string) (string, bool, error) // seam for tests; defaults to editor.EditYAML
	onSelect  func()
}

func NewNewKind(c *client.Client, suspend func(func()), showError, toast func(string), navigate, setHints func(string)) *NewKind {
	n := &NewKind{client: c, suspend: suspend, showError: showError, toast: toast,
		navigate: navigate, setHints: setHints, kinds: newkind.All(),
		editFn: editor.EditYAML,
		table:  tview.NewTable().SetBorders(false).SetSelectable(true, false),
		issues: tview.NewTextView().SetDynamicColors(true),
		pages:  tview.NewPages()}
	style.ApplyChrome(n.table.Box)
	style.ApplyTableSelection(n.table)
	style.ApplyChrome(n.issues.Box)
	n.issues.SetTitle(" validation — e: re-edit · Esc: abandon (buffer kept) ").SetBorder(true)
	n.pages.AddPage("pick", n.table, true, true)
	n.pages.AddPage("issues", n.issues, true, false)
	n.table.SetInputCapture(n.handlePickKey)
	n.issues.SetInputCapture(n.handleIssuesKey)
	n.table.SetSelectionChangedFunc(func(row, col int) {
		if n.onSelect != nil {
			n.onSelect()
		}
	})
	return n
}

func (n *NewKind) View() tview.Primitive { return n.pages }
func (n *NewKind) Hints() string {
	if n.mode == modeIssues {
		return "e: re-edit · Esc: abandon (buffer kept in a temp file)"
	}
	return "Enter: edit the kind's living example · d: describe · r: refresh"
}

func (n *NewKind) Refresh() { n.render() }

func (n *NewKind) render() {
	n.table.Clear()
	for i, h := range []string{"KIND", "WHAT"} {
		n.table.SetCell(0, i, tview.NewTableCell(h).SetSelectable(false).SetTextColor(tcell.ColorYellow))
	}
	for i, k := range n.kinds {
		n.table.SetCell(i+1, 0, tview.NewTableCell(k.Kind))
		n.table.SetCell(i+1, 1, tview.NewTableCell(k.Doc))
	}
}

func (n *NewKind) selectedKind() (newkind.KindTemplate, bool) {
	row, _ := n.table.GetSelection()
	if row < 1 || row-1 >= len(n.kinds) {
		return newkind.KindTemplate{}, false
	}
	return n.kinds[row-1], true
}

// enterIssues switches to the issues view, holding the human's buffer.
func (n *NewKind) enterIssues(text, buffer string) {
	n.mode = modeIssues
	n.buffer = buffer
	if n.issues != nil {
		n.issues.SetText(text)
	}
	if n.pages != nil {
		n.pages.SwitchToPage("issues")
	}
	if n.setHints != nil {
		n.setHints(n.Hints())
	}
}

// abandon persists the buffer to a temp file, THEN leaves the issues view — a
// failed write keeps the buffer and the issues mode so nothing is ever lost.
// Returns the file path ("" when there was nothing to persist).
func (n *NewKind) abandon() string {
	if strings.TrimSpace(n.buffer) == "" {
		n.leaveIssues()
		return ""
	}
	path, err := persistBuffer("lm-new-*.yaml", n.buffer)
	if err != nil {
		if n.showError != nil {
			n.showError("could not persist the buffer (" + err.Error() + ") — still editable with e")
		}
		return ""
	}
	n.buffer = ""
	n.leaveIssues()
	return path
}

// leaveIssues returns to the picker and refreshes the footer hints.
func (n *NewKind) leaveIssues() {
	n.mode = modePick
	if n.pages != nil {
		n.pages.SwitchToPage("pick")
	}
	if n.setHints != nil {
		n.setHints(n.Hints())
	}
}

func (n *NewKind) handlePickKey(ev *tcell.EventKey) *tcell.EventKey {
	if ev.Key() == tcell.KeyEnter {
		if k, ok := n.selectedKind(); ok {
			n.edit(k, k.Template)
		}
		return nil
	}
	return ev
}

func (n *NewKind) handleIssuesKey(ev *tcell.EventKey) *tcell.EventKey {
	switch {
	case ev.Rune() == 'e':
		n.reEdit()
		return nil
	case ev.Key() == tcell.KeyEscape:
		if path := n.abandon(); path != "" && n.toast != nil {
			n.toast("abandoned — buffer kept at " + path)
		}
		return nil // a failed persist keeps modeIssues: the buffer stays editable
	}
	return ev
}

// reEdit re-opens the editor on the preserved buffer WITHOUT leaving the issues
// view first — an editor failure must keep the buffer reachable.
func (n *NewKind) reEdit() {
	n.edit(n.current, n.buffer)
}

// EditExisting runs the SAME authoring loop on an already-stored manifest —
// the :processes 'e' gesture. Returns false when the kind isn't in the registry.
func (n *NewKind) EditExisting(kind, buffer string) bool {
	for _, k := range n.kinds {
		if k.Kind == kind {
			n.edit(k, buffer)
			return true
		}
	}
	return false
}

// edit runs one turn of the loop: $EDITOR on the buffer, then validate/apply.
// The current mode is kept on editor failure; success paths set their own mode.
func (n *NewKind) edit(k newkind.KindTemplate, initial string) {
	n.current = k
	fn := n.editFn
	if fn == nil {
		fn = editor.EditYAML
	}
	var updated string
	var changed bool
	var editErr error
	n.suspend(func() {
		updated, changed, editErr = fn("new-"+k.Kind+".yaml", initial)
	})
	if editErr != nil {
		if n.showError != nil {
			n.showError("editor: " + editErr.Error())
		}
		return
	}
	if !changed && initial == k.Template {
		return // untouched template closed — a browse, not an abandon
	}
	buffer := updated

	issues, err := n.validateOrApply(buffer)
	if err != nil {
		// Transport/server failure: the buffer must survive it.
		n.enterIssues("[red]server error[white] — "+tview.Escape(err.Error())+"\n\nYour manifest is intact — e to re-edit.", buffer)
		return
	}
	if issues != "" {
		n.enterIssues(issues, buffer)
		return
	}
	n.buffer = ""
	n.leaveIssues()
	if n.toast != nil {
		n.toast("applied.")
	}
	if n.navigate != nil {
		n.navigate(k.Observe)
	}
}

// validateOrApply: Connector manifests validate first (rich field-by-field errors),
// then apply; Entity/Process/Workflow apply directly — their apply IS the refusable validation.
func (n *NewKind) validateOrApply(buf string) (string, error) {
	class, kindVal, parseErr := newkind.ClassifyManifest(buf)
	if parseErr != nil {
		return newkind.FormatIssues([]newkind.Issue{{Path: "yaml",
			Message: "invalid YAML: " + parseErr.Error()}}, nil), nil
	}
	switch class {
	case "connector":
		res, err := n.client.ValidateManifest([]byte(buf))
		if err != nil {
			return "", err
		}
		if len(res.Errors) > 0 {
			return newkind.FormatIssues(toIssues(res.Errors), toIssues(res.Warnings)), nil
		}
		if _, err := n.client.ApplyManifest([]byte(buf)); err != nil {
			return "", err
		}
	case "entity":
		if _, err := n.client.EntityApply([]byte(buf)); err != nil {
			return newkind.FormatIssues([]newkind.Issue{{Path: "apply", Message: err.Error()}}, nil), nil
		}
	case "process":
		if _, err := n.client.ProcessApply([]byte(buf)); err != nil {
			return newkind.FormatIssues([]newkind.Issue{{Path: "apply", Message: err.Error()}}, nil), nil
		}
	case "workflow":
		if _, err := n.client.ApplyWorkflow([]byte(buf)); err != nil {
			return newkind.FormatIssues([]newkind.Issue{{Path: "apply", Message: err.Error()}}, nil), nil
		}
	default:
		msg := "missing top-level kind — expected Connector, Entity, Process, or Workflow"
		if kindVal != "" {
			msg = "kind '" + kindVal + "' is not routable here — expected Connector, Entity, Process, or Workflow"
		}
		return newkind.FormatIssues([]newkind.Issue{{Path: "kind", Message: msg}}, nil), nil
	}
	return "", nil
}

func toIssues(in []client.ValidationError) []newkind.Issue {
	out := make([]newkind.Issue, 0, len(in))
	for _, e := range in {
		out = append(out, newkind.Issue{Path: e.Path, Message: e.Message, Recommendations: e.Recommendations})
	}
	return out
}

// Describable — the picker's d panel: the kind's doc line, canonical source,
// and the head of its living example.
func (n *NewKind) SelectedDescribe() (string, string, bool) {
	if n.table == nil {
		return "", "", false
	}
	k, ok := n.selectedKind()
	if !ok {
		return "", "", false
	}
	lines := strings.SplitN(k.Template, "\n", 21)
	head := strings.Join(lines[:min(20, len(lines))], "\n")
	body := fmt.Sprintf("%s\n\nsource: %s\n\n%s", k.Doc, k.Source, highlightYAML(head))
	return "new " + k.Kind, body, true
}
func (n *NewKind) OnSelectionChange(cb func()) { n.onSelect = cb }

// Yankable — yanks the full living example.
func (n *NewKind) SelectedYank() (string, bool) {
	if n.table == nil {
		return "", false
	}
	k, ok := n.selectedKind()
	if !ok {
		return "", false
	}
	return k.Template, true
}
