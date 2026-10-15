package tui

import (
	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"
)

// paletteEntries is the single source of truth for the command palette;
// help_test.go asserts every entry has a row in helpBody.
var paletteEntries = []string{"new", "ask", "inbox", "modules", "sources", "processes", "view", "runs", "dlq", "hooks", "migrations", "scopes", "endpoints", "conflicts", "ambiguous", "observations", "outputs"}

// paletteAliases maps old mechanism spellings to their canonical usage name.
var paletteAliases = map[string]string{"connectors": "sources"}

// mutatingKeyEntries is the curated single source of truth for keys that
// change state on a resource page (never a read-only nav key like d/y/s/r/Enter);
// help_test.go asserts each one has a bullet row under its :page in helpBody, so
// a mutating key added to a page's Hints() but not to helpBody is caught here.
var mutatingKeyEntries = []struct{ page, key string }{
	{"sources", "e"},
	{"sources", "R"},
	{"sources", "^P"},
	{"sources", "Del"},
	{"sources", "t"},
	{"dlq", "^R"},
	{"dlq", "^D"},
	{"outputs", "^R"},
	{"inbox", "a"},
	{"inbox", "A"},
	{"view", "a"},
	{"ambiguous", "^R"},
	{"runs", "c"},
	{"processes", "e"},
	{"processes", "^P"},
	{"processes", "Del"},
}

const helpBody = `[yellow]Global keys[white]
  :       open command palette (type a resource name)
  q       quit (when no input has focus)
  ?       this help screen
  r       refresh current resource
  d       describe panel (toggle)
  y       yank the current row
  Esc     close modal / cancel input

[yellow]Your data[white]
  :sources        where your data comes from — connector instances (alias :connectors)
                     e   edit YAML in $EDITOR
                     R   trigger immediate run (also ^R)
                     ^P  pause / resume (toggle)
                     Del delete (no confirmation)
                     t   edit scope tags
                     Enter  REST → its :endpoints · others → its :runs
  :conflicts      the fused rows with a source conflict or a lookup ambiguity
                     d      the full ledger: which source said what
                     s / y  cycle the sort column · yank the row as JSON
  :ambiguous      identities one entity's resolution pass could not settle
                     Enter  type an entity name to list · on a row, load its full history below
                     ^R     re-run the resolution pass for the listed entity
                     d      the row itself: identity ref, candidates, discarded, decided by
                     s / y  cycle the sort column · yank the row as JSON
  :observations   what has NOT moved — the workflow tape, stillest first
                     d      state, since, first observed, alert
                     y      yank the row as JSON
  :outputs        what left the substrate, and whether it arrived — failures first
                     ^R     replay a failed delivery (a new attempt is recorded)
                     d / y  the failure in full · yank the row as JSON
  :scopes         per-métier scopes (connector tags)
                     Enter  set the active scope (used by :ask)
  :view           open a kind:View file and render it · g: generate one from a sentence (AI)
                     Enter  record detail with the _conflicts ledger
                     f / F  add a server-side filter (honest refusals) / clear
                     g      generate from a sentence (AI)
                     a      apply to hub (into the saved-view store)
                     o      open another file
  :ask            analytic chat — ask your data in natural language
                     Enter  run the question (text-to-SQL over the hub)
                     y      yank the generated SQL (OSC52)
                     e      export result rows to CSV
                     n      new question
  :inbox          decisions waiting for you (decision.* events, live)
                     a   ack the selected decision (idempotent)
                     A   toggle disposed entries ([traité])

[yellow]Workshop[white]
  :new            create an object — pick a kind, edit its living example
                     Enter  open the template in $EDITOR (validate → apply loop)
                     e      re-edit after validation errors (buffer never lost)
  :endpoints      REST endpoints (Enter from a REST source filters here)
                     Enter  jump to the endpoint's runs
  :runs           recent connector runs (live updates)
                     c   request cancellation (only while Running)
  :dlq            dead-letter queue (live updates)
                     ^R  mark replayed
                     ^D  discard
  :hooks          installed Kotlin hooks
  :migrations     Flyway status per schema
  :modules        installed business modules
  :processes      declared kind:Process rules
                     e   edit the stored manifest (validate → apply loop)
                     ^P  enable / disable (toggle) — the manifest stays, it just stops firing
                     Del delete (no confirmation)
`

func (a *App) showHelp() {
	modal := tview.NewTextView().SetDynamicColors(true).SetText(helpBody)
	modal.SetBorder(true).SetTitle(" Help (press Esc to close) ")
	modal.SetInputCapture(func(ev *tcell.EventKey) *tcell.EventKey {
		if ev.Key() == tcell.KeyEscape {
			a.tv.SetRoot(a.rootFlex, true)
			return nil
		}
		return ev
	})
	a.tv.SetRoot(modal, true)
	a.tv.SetFocus(modal)
}
