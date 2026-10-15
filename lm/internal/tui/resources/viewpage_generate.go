package resources

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/editor"
	"github.com/icreated/lumnik-open/lm/internal/newfile"
	"github.com/rivo/tview"
)

// fallbackViewFile names the generated view when the server's leading
// `# lm view -f <name>` comment is absent or unusable.
const fallbackViewFile = "view-generated.yaml"

// suggestedFileName parses the server's leading render-command comment. The name
// is reduced to a bare .yaml basename: the server derives it from the view slug,
// and a path component must never let a generated file land outside the cwd.
func suggestedFileName(yaml string) string {
	const prefix = "# lm view -f "
	if !strings.HasPrefix(yaml, prefix) {
		return fallbackViewFile
	}
	rest := yaml[len(prefix):]
	var name string
	if strings.HasPrefix(rest, "'") { // the CLI's posixQuote form
		unquoted, ok := posixUnquote(rest)
		if !ok {
			return fallbackViewFile
		}
		name = unquoted
	} else {
		end := strings.IndexAny(rest, " \n")
		if end == -1 {
			end = len(rest)
		}
		name = rest[:end]
	}
	name = filepath.Base(strings.TrimSpace(name))
	if name == "." || name == string(filepath.Separator) || !strings.HasSuffix(name, ".yaml") {
		return fallbackViewFile
	}
	return name
}

// posixUnquote decodes a leading POSIX single-quoted token — the inverse of
// the CLI's posixQuote, where an embedded quote rides as the 4-byte sequence
// quote backslash quote quote. Reports false on an unterminated form.
// (Worded to dodge go1.26 gofmt's doc-comment smart-quote normalization,
// which mangles a literal pair of single quotes.)
func posixUnquote(s string) (string, bool) {
	var b strings.Builder
	i := 1 // past the opening quote
	for {
		j := strings.IndexByte(s[i:], '\'')
		if j == -1 {
			return "", false
		}
		b.WriteString(s[i : i+j])
		i += j + 1
		if strings.HasPrefix(s[i:], `\''`) { // '\'' : an embedded quote, still inside
			b.WriteByte('\'')
			i += 3
			continue
		}
		return b.String(), true
	}
}

// uniquify probes for the first free path: p, then stem-2.ext, stem-3.ext, …
// A candidate that cannot be probed (any Stat error, not just ErrNotExist)
// comes back as-is instead of spinning the loop — newfile.Write's O_EXCL is the
// overwrite guard, the probe only picks a friendly name.
func uniquify(p string) string {
	exists := func(q string) bool {
		_, err := os.Stat(q)
		return err == nil
	}
	if !exists(p) {
		return p
	}
	ext := filepath.Ext(p)
	stem := strings.TrimSuffix(p, ext)
	for i := 2; ; i++ {
		q := fmt.Sprintf("%s-%d%s", stem, i, ext)
		if !exists(q) {
			return q
		}
	}
}

// persistBuffer writes buffer to a temp file and returns its path. On any
// failure the half-written file is removed — callers keep the buffer instead.
func persistBuffer(pattern, buffer string) (string, error) {
	f, err := os.CreateTemp("", pattern)
	if err != nil {
		return "", err
	}
	_, werr := f.WriteString(buffer)
	cerr := f.Close()
	if werr != nil || cerr != nil {
		_ = os.Remove(f.Name())
		if werr != nil {
			return "", werr
		}
		return "", cerr
	}
	return f.Name(), nil
}

// promptGenerate is the g/^G gesture: sentence in, kind:View out. The scope is
// the session's (the :ask crucial ordering); the prompt keeps a failed sentence.
func (v *ViewPage) promptGenerate() {
	if v.generating {
		return // single-flight: one sentence at a time
	}
	tag := ""
	if v.scope != nil {
		tag = strings.TrimSpace(v.scope())
	}
	if tag == "" {
		if v.showError != nil {
			v.showError("set a scope first: :scope <tag>")
		}
		return
	}
	v.genInput = tview.NewInputField().
		SetLabel("generate over " + tview.Escape(tag) + ": ").SetFieldWidth(60).SetText(v.genPhrase)
	// The promptFilter pattern: the one-line input above the current primitive
	// (the list, or the open form when the gesture came from there).
	base := tview.Primitive(v.openFlex)
	if v.state == viewList {
		base = v.list
	}
	flex := tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(v.genInput, 1, 0, true).
		AddItem(base, 0, 1, false)
	v.pages.AddPage("generate", flex, true, true)
	if v.app != nil {
		v.app.SetFocus(v.genInput)
	}
	v.genInput.SetDoneFunc(func(key tcell.Key) {
		v.pages.RemovePage("generate")
		if key != tcell.KeyEnter {
			return
		}
		phrase := strings.TrimSpace(v.genInput.GetText())
		if phrase == "" {
			return
		}
		v.generate(tag, phrase)
	})
}

// generate calls the hub off the event loop (the call takes seconds — a frozen
// UI would read as a crash), then marshals the outcome back through enqueue.
func (v *ViewPage) generate(scope, phrase string) {
	v.generating = true
	if v.toast != nil {
		v.toast("generating…")
	}
	v.spawn(func() {
		yaml, err := v.client.ViewGenerate(scope, phrase)
		// The call takes ~4s; the user may have suspended an editor on ANOTHER
		// page meanwhile. enqueue (QueueUpdate) DROPS updates while an editor
		// session owns the terminal — delivering straight into it would wedge
		// v.generating (and the YAML/phrase) shut for the rest of the session.
		// DeferIfSuspended holds the delivery until the session ends instead of
		// dropping it; v.spawn decouples the run so the drain (which runs on
		// the event goroutine) never calls enqueue re-entrantly.
		deliver := func() {
			v.enqueue(func() {
				v.generating = false
				// The world may have changed during the call: gate on :view still
				// being the front page before touching focus or popping the editor
				// (a hidden promptGenerate would steal every keystroke into an
				// invisible InputField; a hidden editGenerated would pop vim over
				// whatever page the user switched to).
				front := v.isFront == nil || v.isFront()
				if err != nil {
					// Abstention/failure: honest, verbatim — and the sentence is kept
					// (a phrase is expensive to retype; the refused-filter precedent
					// does not apply). The error always surfaces (global surface);
					// only re-open the prompt when still front.
					v.genPhrase = phrase
					if v.showError != nil {
						v.showError(err.Error())
					}
					if front {
						v.promptGenerate()
					}
					return
				}
				v.genPhrase = ""
				if front {
					v.editGenerated(yaml)
					return
				}
				// Hidden: never pop the editor over another page. Hold the YAML as
				// a file instead — nothing is lost, and the next `g` (or a manual
				// open) can pick it up.
				path, perr := persistBuffer("lm-view-*.yaml", yaml)
				if perr != nil {
					v.enterGenIssues("[red]persist[white] — "+tview.Escape(perr.Error())+
						"\n\nYour manifest is intact — e to re-edit.", yaml)
					return
				}
				v.recents = pushRecent(v.recents, path, 8) // discoverable beyond the toast
				if v.toast != nil {
					v.toast("view generated — kept at " + path) // toast escapes at the chokepoint
				}
			})
		}
		if !DeferIfSuspended(func() { v.spawn(deliver) }) {
			deliver()
		}
	})
}

// editGenerated is one turn of the rung-2 loop on the generated YAML. An
// untouched buffer still renders: unmodified generated YAML is a valid choice,
// not an abandon.
func (v *ViewPage) editGenerated(buffer string) {
	fn := v.editFn
	if fn == nil {
		fn = editor.EditYAML
	}
	var updated string
	var editErr error
	v.suspend(func() {
		updated, _, editErr = fn("generated-view.yaml", buffer)
	})
	if editErr != nil {
		v.enterGenIssues("[red]editor[white] — "+tview.Escape(editErr.Error())+
			"\n\nYour manifest is intact — e to re-edit.", buffer)
		return
	}
	v.renderGenerated(updated)
}

// renderGenerated renders the buffer; only a successful render births the file
// (suggested name, collision-suffixed — never a silent overwrite).
func (v *ViewPage) renderGenerated(buffer string) {
	res, err := v.client.ViewRender([]byte(buffer), nil)
	if err != nil {
		v.enterGenIssues("[red]render refused[white] — "+tview.Escape(err.Error())+
			"\n\ne: re-edit · Esc: abandon (buffer kept in a temp file)", buffer)
		return
	}
	path := uniquify(suggestedFileName(buffer))
	if werr := newfile.Write(path, []byte(buffer)); werr != nil {
		v.enterGenIssues("[red]write failed[white] — "+tview.Escape(werr.Error())+
			"\n\nYour manifest is intact — e to re-edit.", buffer)
		return
	}
	v.manifest = []byte(buffer)
	v.path = path
	v.filters = nil
	v.genBuffer = ""
	v.fill(res)
	v.recents = pushRecent(v.recents, path, 8)
	if v.toast != nil {
		v.toast("written to " + path) // toast escapes at the chokepoint
	}
	v.toState(viewList)
}

// applyToHub is the 'a' gesture on a rendered view. The contract (a sanctioned
// broadening of the generate-only brief): it applies WHATEVER manifest the
// page holds — opened or generated — mirroring `lm view apply -f`; the hub's
// validate-then-upsert is the safety gate, and the confirmed name comes from
// the manifest on screen. Called synchronously — like the render/detail
// calls on this same key handler — since validate-then-upsert is a single
// quick round-trip, not the multi-second generate call that needs a spawn.
// Errors surface via the honest strings (Task 1: 403/429 render verbatim);
// success toasts the confirmed name, mirroring the local-write toast style.
func (v *ViewPage) applyToHub() {
	if v.manifest == nil {
		return
	}
	res, err := v.client.ViewApply(v.manifest)
	if err != nil {
		if v.showError != nil {
			v.showError(err.Error())
		}
		return
	}
	if v.toast != nil {
		v.toast("applied: " + res.Name)
	}
}

// enterGenIssues mirrors the :new issues state: message verbatim, buffer held.
func (v *ViewPage) enterGenIssues(text, buffer string) {
	v.genBuffer = buffer
	if v.genIssues != nil {
		v.genIssues.SetText(text)
	}
	v.toState(viewGenIssues)
}

func (v *ViewPage) handleGenIssuesKey(ev *tcell.EventKey) *tcell.EventKey {
	switch {
	case ev.Rune() == 'e':
		v.editGenerated(v.genBuffer)
		return nil
	case ev.Key() == tcell.KeyEscape:
		if strings.TrimSpace(v.genBuffer) == "" {
			v.toState(viewOpen)
			return nil
		}
		path, err := persistBuffer("lm-view-*.yaml", v.genBuffer)
		if err != nil {
			if v.showError != nil {
				v.showError("could not persist the buffer (" + err.Error() + ") — still editable with e")
			}
			return nil // a failed persist keeps the issues state: the buffer stays reachable
		}
		v.genBuffer = ""
		if v.toast != nil {
			v.toast("abandoned — buffer kept at " + path)
		}
		v.toState(viewOpen)
		return nil
	}
	return ev
}
