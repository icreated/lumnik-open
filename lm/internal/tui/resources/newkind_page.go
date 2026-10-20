package resources

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/editor"
	"github.com/icreated/lumnik-open/lm/internal/output"
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
	app       *tview.Application // nil in a bare test: the hub calls then run inline
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
	onWizard  func(kind string) bool                           // wired by the App: w runs a kind's guided setup, if it has one
	held      bool                                             // the issues view holds a validated, unapplied buffer: a applies it
	onSelect  func()
	schemas   lazyDetail // d on a connector kind: its type's config schema, per type (#1138)
	inFlight  inFlight   // the guard on the hub round of a turn (#1147): see turnKey
}

// turnKey names the one hub round of an authoring turn, a check or a validate-and-apply: while it
// is out, no gesture opens the editor or applies again — the second buffer would race the first.
const turnKey = "the manifest"

func NewNewKind(c *client.Client, suspend func(func()), showError, toast func(string), navigate, setHints func(string)) *NewKind {
	n := &NewKind{client: c, suspend: suspend, showError: showError, toast: toast,
		navigate: navigate, setHints: setHints, kinds: newkind.All(),
		editFn: editor.EditYAML,
		table:  newListTable(),
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

// SetOnWizard is wired by the App: w on a kind with a guided setup (#1117) runs it.
func (n *NewKind) SetOnWizard(fn func(kind string) bool) { n.onWizard = fn }

// SetApp is wired by the App so validate/apply run off the event loop (#1126).
func (n *NewKind) SetApp(app *tview.Application) { n.app = app }

func (n *NewKind) View() tview.Primitive { return n.pages }
func (n *NewKind) Hints() string {
	if n.mode == modeIssues {
		if n.held {
			return "a: apply it · e: re-edit · Esc: abandon (buffer kept in a temp file)"
		}
		return "e: re-edit · Esc: abandon (buffer kept in a temp file)"
	}
	return "Enter: edit the kind's living example · w: guided setup (jdbc-generic, csv-file) · v: edit, then validate without applying · d: describe · r: refresh"
}

func (n *NewKind) Refresh() {
	n.schemas.retryFailed()
	n.render()
}

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
	n.held = false // only hold() holds a buffer for a
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
	if ev.Rune() == 'w' && n.onWizard != nil {
		if k, ok := n.selectedKind(); ok && !n.onWizard(k.Kind) && n.toast != nil {
			n.toast(k.Kind + " has no guided setup — Enter edits its example")
		}
		return nil
	}
	if ev.Rune() == 'v' {
		if k, ok := n.selectedKind(); ok {
			n.validateOnly(k)
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
	case ev.Rune() == 'a' && n.held:
		n.finish(n.current, n.buffer)
		return nil
	case ev.Rune() == 'a' && n.inFlight.busy(turnKey, n.toast): // the apply a started is still out
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
func (n *NewKind) edit(k newkind.KindTemplate, initial string) { n.turn(k, initial, false) }

// validateOnly is v: edit the kind's example, then validate it without applying (#1118).
func (n *NewKind) validateOnly(k newkind.KindTemplate) { n.turn(k, k.Template, true) }

// turn is one authoring turn: $EDITOR on the buffer, then check it (dryRun) or validate-and-apply it.
func (n *NewKind) turn(k newkind.KindTemplate, initial string, dryRun bool) {
	if n.inFlight.busy(turnKey, n.toast) { // no editor for a turn that could not be sent
		return
	}
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
	if dryRun {
		n.check(updated)
		return
	}
	n.finish(k, updated)
}

// check validates buffer and holds it in the issues view, applied by nothing until a. A connector
// goes through the hub's validation door; every other kind has no dry run — its apply IS its
// validation — so only the YAML and the kind are checked here, and the view says so.
func (n *NewKind) check(buffer string) {
	class, kindVal, refusal := classify(buffer)
	switch {
	case refusal != "":
		n.enterIssues(refusal, buffer)
	case class == "connector":
		n.inFlight.background(n.app, turnKey, n.toast, func() func() {
			res, err := n.client.ValidateManifest([]byte(buffer))
			return func() {
				switch {
				case err != nil:
					n.enterIssues(serverError(err), buffer)
				case len(res.Errors) > 0:
					n.enterIssues(newkind.FormatIssues(toIssues(res.Errors), toIssues(res.Warnings)), buffer)
				default:
					n.hold("[green]valid[white] — not applied.", res.Warnings, buffer)
				}
			}
		})
	default:
		n.hold("[yellow]YAML and kind check out[white] — not applied. A "+tview.Escape(kindVal)+
			" has no dry run on the hub: it is validated when applied.", nil, buffer)
	}
}

// hold shows a validated buffer that nothing has applied yet.
func (n *NewKind) hold(verdict string, warnings []client.ValidationError, buffer string) {
	text := verdict + "\n\na: apply it · e: re-edit · Esc: abandon (buffer kept)"
	if len(warnings) > 0 {
		text += "\n\n" + newkind.FormatIssues(nil, toIssues(warnings))
	}
	n.enterIssues(text, buffer)
	n.held = true
	if n.setHints != nil {
		n.setHints(n.Hints()) // now that a applies it
	}
}

// finish validates and applies buffer, then reports and navigates — the end of one authoring turn.
// The hub calls run off the event loop (#1126): validate + apply is two round trips for a connector.
func (n *NewKind) finish(k newkind.KindTemplate, buffer string) {
	n.held = false
	n.inFlight.background(n.app, turnKey, n.toast, func() func() {
		issues, note, err := n.validateOrApply(buffer)
		return func() { n.report(k, buffer, issues, note, err) }
	})
}

// serverError is the issues text for a transport/server failure: the buffer must survive it.
func serverError(err error) string {
	return "[red]server error[white] — " + tview.Escape(err.Error()) + "\n\nYour manifest is intact — e to re-edit."
}

// classify routes buffer, or says why it cannot be: invalid YAML, a missing or unknown kind.
func classify(buffer string) (class, kindVal, refusal string) {
	class, kindVal, parseErr := newkind.ClassifyManifest(buffer)
	switch {
	case parseErr != nil:
		return "", "", newkind.FormatIssues([]newkind.Issue{{Path: "yaml", Message: "invalid YAML: " + parseErr.Error()}}, nil)
	case class == "" && kindVal == "":
		return "", "", newkind.FormatIssues([]newkind.Issue{{Path: "kind", Message: "missing top-level kind — expected " + routableKinds}}, nil)
	case class == "":
		return "", kindVal, newkind.FormatIssues([]newkind.Issue{{Path: "kind", Message: "kind '" + kindVal + "' is not routable here — expected " + routableKinds}}, nil)
	}
	return class, kindVal, ""
}

// report lands one turn's outcome on the loop: the issues view, or applied → toast → observe.
func (n *NewKind) report(k newkind.KindTemplate, buffer, issues, note string, err error) {
	if err != nil {
		n.enterIssues(serverError(err), buffer)
		return
	}
	if issues != "" {
		n.enterIssues(issues, buffer)
		return
	}
	n.buffer = ""
	n.leaveIssues()
	if n.toast != nil {
		if note == "" {
			n.toast("applied.")
		} else {
			n.toast("applied — " + note)
		}
	}
	if n.navigate != nil {
		n.navigate(k.Observe)
	}
}

// routableKinds names every kind :new applies, for the refusal of any other.
const routableKinds = "Connector, Entity, Process, Workflow, App, View, Export or SearchIndex"

// validateOrApply: Connector manifests validate first (rich field-by-field errors),
// then apply; Entity/App/Process/Workflow apply directly — their apply IS the refusable validation.
func (n *NewKind) validateOrApply(buf string) (issues string, note string, err error) {
	class, _, refusal := classify(buf)
	if refusal != "" {
		return refusal, "", nil
	}
	if apply, ok := n.plainApply()[class]; ok {
		if err := apply([]byte(buf)); err != nil {
			return applyIssue(err), "", nil
		}
		return "", "", nil
	}
	switch class {
	case "connector":
		res, err := n.client.ValidateManifest([]byte(buf))
		if err != nil {
			return "", "", err
		}
		if len(res.Errors) > 0 {
			return newkind.FormatIssues(toIssues(res.Errors), toIssues(res.Warnings)), "", nil
		}
		if _, err := n.client.ApplyManifest([]byte(buf)); err != nil {
			return "", "", err
		}
		// Accepted is not silent. These advisories were rendered only on the REFUSAL path, so a
		// scopeless connector applied from here said nothing at all (#531). They cannot go to the
		// issues view: a non-empty issues string means "not applied, e to re-edit", and this one
		// is written. The toast is this surface's word for "done, and here is what about it".
		note = applyNote(res.Warnings)
	case "app":
		resp, err := n.client.AppApply([]byte(buf))
		if err != nil {
			return applyIssue(err), "", nil
		}
		note = appApplyNote(resp)
	case "view":
		res, err := n.client.ViewApply([]byte(buf))
		if err != nil {
			return applyIssue(err), "", nil
		}
		note = applyNote(res.Warnings) // a link target not there yet: applied, and worth saying
	}
	return "", note, nil
}

// plainApply is every kind whose apply door is its whole validation and answers nothing worth a
// note; connector (validate first), app and view (their warnings) keep their own cases.
func (n *NewKind) plainApply() map[string]func([]byte) error {
	c := n.client
	return map[string]func([]byte) error{
		"entity":      func(b []byte) error { _, err := c.EntityApply(b); return err },
		"process":     func(b []byte) error { _, err := c.ProcessApply(b); return err },
		"workflow":    func(b []byte) error { _, err := c.ApplyWorkflow(b); return err },
		"export":      func(b []byte) error { _, err := c.ExportApply(b); return err },
		"searchindex": func(b []byte) error { _, err := c.SearchIndexApply(b); return err },
	}
}

// applyIssue is the issues text for a refused apply.
func applyIssue(err error) string {
	return newkind.FormatIssues([]newkind.Issue{{Path: "apply", Message: err.Error()}}, nil)
}

// appApplyNote is the toast's note for an applied kind:App: the hub keeps a declared tab it does not
// offer and answers with a warning, which this surface used to drop with the whole response.
func appApplyNote(resp []byte) string {
	var r struct {
		Warnings []client.ValidationError `json:"warnings"`
	}
	if json.Unmarshal(resp, &r) != nil {
		return ""
	}
	return applyNote(r.Warnings)
}

// applyNote is what the toast says beyond "applied." — the paths the hub advised on, not their
// full text: the panel that shows recommendations is the refusal view, and this manifest was
// accepted. `lm validate -f` prints the whole report.
func applyNote(warnings []client.ValidationError) string {
	if len(warnings) == 0 {
		return ""
	}
	paths := make([]string, 0, len(warnings))
	for _, w := range warnings {
		paths = append(paths, w.Path)
	}
	unit := "warnings"
	if len(warnings) == 1 {
		unit = "warning"
	}
	return fmt.Sprintf("%d %s (%s)", len(warnings), unit, strings.Join(paths, ", "))
}

func toIssues(in []client.ValidationError) []newkind.Issue {
	out := make([]newkind.Issue, 0, len(in))
	for _, e := range in {
		out = append(out, newkind.Issue{Path: e.Path, Message: e.Message, Recommendations: e.Recommendations})
	}
	return out
}

// Describable — the picker's d panel: the kind's doc line, canonical source,
// the head of its living example and, for a connector, its type's config schema.
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
	if typ := k.ConnectorType(); typ != "" && n.client != nil {
		schema, ok := n.schemas.get(n.app, typ, func() (string, error) {
			raw, err := n.client.DescribeConnectorType(typ)
			if err != nil {
				return "", err
			}
			var def output.ConfigDef
			if err := json.Unmarshal(raw, &def); err != nil {
				return "", err
			}
			var b strings.Builder
			output.RenderConfigDef(&b, def, 0)
			return tview.Escape(b.String()), nil // the hub's words
		}, n.onSelect)
		if !ok {
			schema = "[gray]fetching its config schema…[white]"
		}
		body += "\n\n[yellow]connector type: " + tview.Escape(typ) + "[white]  (lm describe connector-type)\n" + schema
	}
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
