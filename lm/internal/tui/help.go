package tui

import (
	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"
)

// paletteEntries is the single source of truth for the command palette;
// help_test.go asserts every entry has a row in helpBody.
var paletteEntries = []string{"new", "ask", "inbox", "modules", "sources", "processes", "view", "runs", "dlq", "hooks", "migrations", "rag", "scopes", "endpoints", "conflicts", "ambiguous", "observations", "outputs", "integrations", "notify", "secrets", "entities", "tables", "apps", "workflows", "views", "exports", "search"}

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
	{"integrations", "n"},
	{"integrations", "T"},
	{"integrations", "Del"},
	{"notify", "n"},
	{"notify", "Del"},
	{"inbox", "a"},
	{"inbox", "A"},
	{"view", "a"},
	{"ambiguous", "^R"},
	{"runs", "c"},
	{"entities", "Del"},
	{"entities", "m"},
	{"entities", "="},
	{"entities", "!"},
	{"entities", "^R"},
	{"workflows", "^P"},
	{"workflows", "Del"},
	{"apps", "Del"},
	{"views", "Del"},
	{"secrets", "n"},
	{"secrets", "e"},
	{"secrets", "Del"},
	{"endpoints", "^P"},
	{"endpoints", "C"},
	{"endpoints", "Del"},
	{"processes", "e"},
	{"processes", "^P"},
	{"processes", "Del"},
	{"rag", "R"},
	{"exports", "R"},
	{"exports", "^R"},
	{"search", "R"},
	{"search", "c"},
	{"search", "Del"},
}

const helpBody = `[yellow]Global keys[white]
  :       open command palette (type a resource name)
  :context  switch to another configured hub, for this session (Enter switches)
  q       quit (when no input has focus)
  ?       this help screen
  r       refresh current resource
  d       describe panel (toggle)
  Tab     with the describe panel open: give it the keys to scroll (↑↓ PgUp PgDn) · Tab / Esc: back
  /       filter the table to the rows holding a text (Esc clears)
  y       yank the current row
  Esc     close modal / cancel input

[yellow]Your data[white]
  :sources        where your data comes from — connector instances (alias :connectors)
                     e   edit YAML in $EDITOR
                     R   trigger immediate run (also ^R)
                     ^P  pause / resume (toggle)
                     Del delete (asks first, naming the connector and the hub)
                     t   edit scope tags, then offers to re-stamp its indexed RAG chunks with them
                     D   its dead letters in :dlq
                     Enter  REST / JDBC → its :endpoints · others → its :runs
  :tables         the dynamic t_* tables your connectors landed, with your row counts
                     Enter  the table's raw rows, in landing order (n / p: page · Esc: back)
  :entities       the entities you may read · Enter: their canonical rows (no View needed)
                     a      the entity's ambiguous identities in :ambiguous
                     Del    delete the entity (asks first)
                     n / p  next / previous page of rows
                     m      mark a row (resolution entities: its SOURCE:ID)
                     =      assert this row and the marked one are the same entity (asks first)
                     !      assert they are different entities (asks first)
                     h      the row's resolution history
                     ^R     re-run the resolution pass
                     Esc    back to the list
  :conflicts      the fused rows with a source conflict or a lookup ambiguity
                     d      the full ledger: which source said what
                     s / y  cycle the sort column · yank the row as JSON
  :ambiguous      identities one entity's resolution pass could not settle
                     Enter  type an entity name to list · on a row, load its full history below
                     Esc    leave the Entity: field (: in it while empty opens the palette)
                     ^R     re-run the resolution pass for the listed entity
                     d      the row itself: identity ref, candidates, discarded, decided by
                     s / y  cycle the sort column · yank the row as JSON
  :observations   what has NOT moved — the workflow tape, stillest first; the title: watched · dormant per workflow
                     d      state, since, first observed, alert
                     y      yank the row as JSON
  :outputs        what left the substrate, and whether it arrived — failures first
                     ^R     replay a failed delivery (a new attempt is recorded)
                     d / y  the failure in full · yank the row as JSON
  :integrations   the webhook targets :outputs delivers to, with their recent delivery health
                     n   register a target (URL and signing secret typed masked)
                     T   fire a test event and say how the delivery went
                     Del remove the target (asks first; its delivery history is kept)
                     d   the registration (URL shown to its host only) and the latest deliveries
  :notify         the email rules routing matching events to a recipient
                     n   add a rule (events, recipient, entity filter, template)
                     Del remove the rule (asks first)
  :exports        the declared kind:Export manifests and their last run (integrator-only)
                     Enter  its runs, newest first (Esc: back to the exports)
                     R      run it now — the hub claims the run; r shows how it ends
                     ^R     on a run: retry it (only a Failed run)
                     e      edit the stored manifest in :new
  :search         whether this hub has a Search provider (none is the normal state), then its indexes
                     /      query the indexes, bound to the session scope (Esc: back to the indexes)
                     R      rebuild the index
                     c      cancel its rebuild
                     Del    delete the index (asks first)
  :scopes         per-métier scopes (connector tags)
                     Enter  set the active scope (used by :ask)
  :views          the saved views the hub serves the PWA
                     Enter  render it in :view · e  edit its stored manifest in :new
                     Del    delete it (asks first)
  :apps           the métier apps the PWA offers (title, scope, default, tabs)
                     e      edit its manifest in :new (rebuilt from the app's fields)
                     Del    delete one (asks first)
  :workflows      the declared document lifecycles
                     e      edit the stored manifest in :new
                     ^P     enable / disable (the declaration stays)
                     Del    delete (asks first)
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
                     w      guided setup: jdbc-generic (connect → test → pick tables → add) · csv-file (ingest a file)
                     v      edit, then validate WITHOUT applying (a applies the held buffer)
                     e      re-edit after validation errors (buffer never lost)
                     d      the kind's example and, for a connector, its type's config schema
  :endpoints      a connector's endpoints (REST: one per spec entry · JDBC: one per table), live
                     Enter  jump to the endpoint's runs
                     T      test the endpoint: the request a run would send, nothing written
                     P      probe a path of the API and draft its endpoint fragment
                     ^P     enable / disable (toggle)
                     C      reset the cursor — the next run re-reads from the start (asks first)
                     Del    purge the rows the endpoint landed (the hub counts them first; asks only when there are some)
  :runs           recent connector runs (live updates)
                     c   request cancellation (only while Running)
                     D   the run's dead letters in :dlq (Esc there clears the filter)
  :dlq            dead-letter queue (live: every 5 s and when a run ends)
                     ^R  mark replayed, naming the run that carried it (Enter: the connector's latest · - none)
                     ^D  discard
  :secrets        the credentials connectors refer to by name (values are never shown)
                     n   store a new one (the value is typed masked)
                     e   rotate the selected one's value
                     Del delete (asks first)
  :hooks          installed Kotlin hooks
                     d   the hook's binding: handler, events, priority (lm hook get)
  :migrations     Flyway status per schema — the hub applies pending ones when it next starts
  :rag            chunks indexed per scope, and the platform corpus (absent: says if switched off or not built)
                     R   reindex the platform corpus (asks first)
  :modules        installed business modules
  :processes      declared kind:Process rules
                     e   edit the stored manifest (validate → apply loop)
                     ^P  enable / disable (toggle) — the manifest stays, it just stops firing
                     Del delete (asks first)
`

func (a *App) showHelp() {
	modal := tview.NewTextView().SetDynamicColors(true).SetText(helpBody)
	modal.SetBorder(true).SetTitle(" Help (press Esc to close) ")
	modal.SetInputCapture(func(ev *tcell.EventKey) *tcell.EventKey {
		if ev.Key() == tcell.KeyEscape {
			a.setRoot(a.rootFlex)
			return nil
		}
		return ev
	})
	a.setRoot(modal)
	a.tv.SetFocus(modal)
}
