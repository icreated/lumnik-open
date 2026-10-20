package tui

import (
	"context"
	"encoding/json"
	"net/url"
	"strings"
	"sync/atomic"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/resources"
	"github.com/rivo/tview"
)

// Describable: pages that support `d` (k9s describe).
type Describable interface {
	SelectedDescribe() (title string, body string, ok bool)
	OnSelectionChange(cb func())
}

// Filterable: pages that support `/` (#1120).
type Filterable interface {
	SetTextFilter(q string)
	TextFilter() string
}

// Drillable: pages a drill-down narrows (:runs by connector/endpoint, :dlq by run/connector);
// Esc clears it.
type Drillable interface {
	FilterActive() bool
	ClearFilter()
	Hints() string
}

// Yankable: pages that support `y` (copy current selection to clipboard).
type Yankable interface {
	SelectedYank() (text string, ok bool)
}

// YankableLater: pages whose `y` must ask the hub first (:sources copies the whole connector). The
// page reads it off the event loop and calls yank in its follow-up, on the loop (#1158).
type YankableLater interface {
	YankLater(yank func(text string))
}

type App struct {
	tv               *tview.Application
	pages            *tview.Pages
	header           *Header
	footer           *Footer
	rootFlex         *tview.Flex
	client           *client.Client
	palette          *Palette
	switchTo         func(name string)
	modulesPage      *resources.Modules
	sourcesPage      *resources.Sources
	runsPage         *resources.Runs
	dlqPage          *resources.DLQ
	conflictsPage    *resources.Conflicts
	observationsPage *resources.Observations
	outputsPage      *resources.Outputs
	inboxPage        *resources.Inbox
	newPage          *resources.NewKind
	processesPage    *resources.Manifests
	viewPage         *resources.ViewPage
	hooksPage        *resources.Hooks
	migrationsPage   *resources.Migrations
	ragPage          *resources.Rag
	scopesPage       *resources.Scopes
	endpointsPage    *resources.Endpoints
	ambiguousPage    *resources.Ambiguous
	askPage          *resources.Ask
	secretsPage      *resources.Secrets
	integrationsPage *resources.Integrations
	notifyPage       *resources.Notify
	entitiesPage     *resources.Entities
	tablesPage       *resources.Tables
	appsPage         *resources.Manifests
	workflowsPage    *resources.Manifests
	viewsPage        *resources.Manifests
	exportsPage      *resources.Exports
	searchPage       *resources.Search
	live             *LiveStatus
	sseStop          context.CancelFunc
	describe         *DescribePanel
	describeOpen     bool
	describeSrc      Describable
	currentScope     string
	contextLabel     string // which hub: said in the header and in every confirmation
	overlaid         bool        // something other than the main layout holds the screen (setRoot); event goroutine only
	retired          atomic.Bool // replaced by :context — its late signals stay silent and it never takes the screen (#1151)
}

func newApp(c *client.Client) *App { return newAppOn(tview.NewApplication(), c) }

// newAppOn builds the app on tv, taking its root and its keys: :context builds the next one on the
// same terminal (#1137).
func newAppOn(tv *tview.Application, c *client.Client) *App {
	a := &App{
		tv:     tv,
		pages:  tview.NewPages(),
		client: c,
		live:   &LiveStatus{},
	}
	a.describe = NewDescribePanel()
	a.header = NewHeader()
	a.footer = NewFooter()
	a.palette = NewPalette(paletteEntries, paletteAliases)
	// A dead session (refresh failed past the reload-retry, or a 401) raises the
	// re-login modal — from any page, on demand. The goroutine decouples from the
	// caller so the QueueUpdate is never re-entrant (the signal often fires from
	// within a page's on-loop request) and never blocks before the loop is pumping.
	a.client.SetAuthLost(func() {
		if a.retired.Load() {
			return // a hub this session has left: its expiry is not the screen's business
		}
		// Raise the modal on the UI thread. If an editor session owns the terminal,
		// DEFER it until SuspendEditor's screen swap is done — drawing the modal
		// during the swap raced the loop and quit lm (the ':q quits lm' regression,
		// only with a dead session because that's when this fires). If no editor is
		// active, raise now; the goroutine decouples the QueueUpdateDraw so it's
		// never re-entrant.
		raise := func() { go a.tv.QueueUpdateDraw(a.showAuthLost) }
		if !resources.DeferIfSuspended(raise) {
			raise()
		}
	})
	a.modulesPage = resources.NewModules(a.tv, c)
	a.pages.AddPage("modules", a.modulesPage.View(), true, false)
	a.sourcesPage = resources.NewSources(a.tv, c, a.showError, func(m string) {
		a.footer.SetToast(a.tv, m, 2*time.Second)
	})
	a.pages.AddPage("sources", a.sourcesPage.View(), true, false)
	a.endpointsPage = resources.NewEndpoints(a.tv, c, func(m string) { a.footer.SetToast(a.tv, m, 3*time.Second) }, a.showError)
	a.pages.AddPage("endpoints", a.endpointsPage.View(), true, false)
	a.sourcesPage.SetOnEnter(func(connectorID, name, kind string) {
		if kind == "rest" || kind == "jdbc" { // one endpoint per spec.endpoints[] / per table
			a.endpointsPage.SetFilter(connectorID, name)
			a.switchTo("endpoints")
		} else {
			a.runsPage.SetFilter(connectorID, name)
			a.switchTo("runs")
		}
	})
	a.endpointsPage.SetOnEnter(func(endpointID, name string) {
		a.runsPage.SetEndpointFilter(endpointID, name)
		a.switchTo("runs")
	})
	a.runsPage = resources.NewRuns(a.tv, c, func(m string) {
		a.footer.SetToast(a.tv, m, 3*time.Second)
	})
	a.pages.AddPage("runs", a.runsPage.View(), true, false)
	a.dlqPage = resources.NewDLQ(a.tv, c, a.showError)
	a.dlqPage.SetAsk(a.prompt)
	a.pages.AddPage("dlq", a.dlqPage.View(), true, false)
	a.conflictsPage = resources.NewConflicts(a.tv, c, a.showError)
	a.pages.AddPage("conflicts", a.conflictsPage.View(), true, false)
	a.observationsPage = resources.NewObservations(a.tv, c, a.showError)
	a.pages.AddPage("observations", a.observationsPage.View(), true, false)
	a.outputsPage = resources.NewOutputs(a.tv, c, a.showError, func(m string) {
		a.footer.SetToast(a.tv, m, 3*time.Second)
	})
	a.pages.AddPage("outputs", a.outputsPage.View(), true, false)
	a.hooksPage = resources.NewHooks(a.tv, c, a.showError)
	a.pages.AddPage("hooks", a.hooksPage.View(), true, false)
	a.migrationsPage = resources.NewMigrations(a.tv, c)
	a.pages.AddPage("migrations", a.migrationsPage.View(), true, false)
	a.ragPage = resources.NewRag(a.tv, c, a.showError, func(m string) { a.footer.SetToast(a.tv, m, 3*time.Second) })
	a.ragPage.SetConfirm(a.confirmAs)
	a.pages.AddPage("rag", a.ragPage.View(), true, false)
	a.scopesPage = resources.NewScopes(a.tv, c)
	a.pages.AddPage("scopes", a.scopesPage.View(), true, false)
	a.inboxPage = resources.NewInbox(a.tv, c, a.showError, func(m string) {
		a.footer.SetToast(a.tv, m, 2*time.Second)
	})
	a.pages.AddPage("inbox", a.inboxPage.View(), true, false)
	a.newPage = resources.NewNewKind(c, a.suspend, a.showError, func(m string) {
		a.footer.SetToast(a.tv, m, 4*time.Second)
	}, func(page string) {
		if a.switchTo != nil {
			a.switchTo(page)
		}
	}, func(h string) {
		a.footer.SetHints(h)
	})
	a.newPage.SetApp(a.tv)
	a.newPage.SetOnWizard(a.onboard)
	a.pages.AddPage("new", a.newPage.View(), true, false)
	a.viewPage = resources.NewViewPage(a.tv, c,
		a.suspend,
		func() string { return a.currentScope },
		a.frontIs("view"),
		a.showError,
		func(m string) { a.footer.SetToast(a.tv, m, 3*time.Second) },
		func(h string) { a.footer.SetHints(h) })
	a.pages.AddPage("view", a.viewPage.View(), true, false)
	a.askPage = resources.NewAsk(a.tv, c, a.frontIs("ask"), a.showError, func(m string) {
		a.footer.SetToast(a.tv, m, 2*time.Second)
	})
	a.pages.AddPage("ask", a.askPage.View(), true, false)
	a.ambiguousPage = resources.NewAmbiguous(a.tv, c, a.showError, func(m string) {
		a.footer.SetToast(a.tv, m, 3*time.Second)
	})
	a.pages.AddPage("ambiguous", a.ambiguousPage.View(), true, false)
	a.entitiesPage = resources.NewEntities(a.tv, c, func(m string) {
		a.footer.SetToast(a.tv, m, 4*time.Second)
	}, a.showError)
	a.entitiesPage.SetAsk(a.prompt)
	a.entitiesPage.SetConfirm(a.confirmAs)
	a.entitiesPage.SetShow(a.showText)
	a.entitiesPage.SetOnAmbiguous(func(name string) {
		a.switchTo("ambiguous")
		a.ambiguousPage.Open(name)
	})
	a.pages.AddPage("entities", a.entitiesPage.View(), true, false)
	toast := func(m string) { a.footer.SetToast(a.tv, m, 3*time.Second) }
	// editStored is e's follow-up on the page named page: the stored manifest, read off the loop,
	// reopened in :new — only while the user is still on page (#1171, as :mappings since #1161).
	editStored := func(page string) func(kind string, yaml []byte) {
		front := a.frontIs(page)
		return func(kind string, yaml []byte) {
			if !front() { // left — or the whole hub left (:context): never edited, never applied there
				toast("e: :" + page + " was left before the hub answered — nothing opened")
				return
			}
			a.switchTo("new")
			if !a.newPage.EditExisting(kind, string(yaml)) {
				a.showError(kind + " is not in the :new kind registry — cannot edit")
			}
		}
	}
	for _, m := range []struct {
		page **resources.Manifests
		src  resources.ManifestSource
	}{
		{&a.appsPage, resources.AppsSource(c)},
		{&a.workflowsPage, resources.WorkflowsSource(c)},
		{&a.viewsPage, resources.ViewsSource(c)},
		{&a.processesPage, resources.ProcessesSource(c)},
	} {
		p := resources.NewManifests(a.tv, m.src, toast, a.showError)
		p.SetConfirm(a.confirmAs)
		p.SetOnEdit(editStored(m.src.Title))
		a.pages.AddPage(m.src.Title, p.View(), true, false)
		*m.page = p
	}
	viewsFront := a.frontIs("views")
	a.viewsPage.SetOnOpen(func(name string, yaml []byte) {
		if !viewsFront() { // retired, or the user left :views before the hub answered: no screen move
			return
		}
		a.switchTo("view")
		a.viewPage.OpenManifest(name, yaml)
	})
	a.exportsPage = resources.NewExports(a.tv, c, toast, a.showError)
	a.exportsPage.SetOnEdit(editStored("exports"))
	a.exportsPage.SetOnHints(a.footer.SetHints)
	a.pages.AddPage("exports", a.exportsPage.View(), true, false)
	a.searchPage = resources.NewSearch(a.tv, c, func() string { return a.currentScope }, toast, a.showError)
	a.searchPage.SetConfirm(a.confirmAs)
	a.pages.AddPage("search", a.searchPage.View(), true, false)
	a.tablesPage = resources.NewTables(a.tv, c, a.showError)
	a.pages.AddPage("tables", a.tablesPage.View(), true, false)
	a.secretsPage = resources.NewSecrets(a.tv, c, func(m string) {
		a.footer.SetToast(a.tv, m, 3*time.Second)
	}, a.showError)
	a.secretsPage.SetAsk(a.prompt)
	a.secretsPage.SetConfirm(a.confirmAs)
	a.pages.AddPage("secrets", a.secretsPage.View(), true, false)
	a.integrationsPage = resources.NewIntegrations(a.tv, c, func(m string) {
		a.footer.SetToast(a.tv, m, 3*time.Second)
	}, a.showError)
	a.integrationsPage.SetAsk(a.prompt)
	a.integrationsPage.SetConfirm(a.confirmAs)
	a.pages.AddPage("integrations", a.integrationsPage.View(), true, false)
	a.notifyPage = resources.NewNotify(a.tv, c, func(m string) {
		a.footer.SetToast(a.tv, m, 3*time.Second)
	}, a.showError)
	a.notifyPage.SetAsk(a.prompt)
	a.notifyPage.SetConfirm(a.confirmAs)
	a.pages.AddPage("notify", a.notifyPage.View(), true, false)
	a.sourcesPage.SetConfirm(a.confirmAs)
	a.sourcesPage.SetSuspend(a.suspend)
	a.sourcesPage.SetFront(a.frontIs("sources"))
	a.sourcesPage.SetOnDeadLetters(func(id string) { a.dlqPage.SetConnectorFilter(id); a.switchTo("dlq") })
	a.runsPage.SetOnDeadLetters(func(id string) { a.dlqPage.SetRunFilter(id); a.switchTo("dlq") })
	a.endpointsPage.SetConfirm(a.confirmAs)
	a.endpointsPage.SetAsk(a.prompt)
	a.endpointsPage.SetShow(a.showText)
	a.endpointsPage.SetFront(a.frontIs("endpoints"))
	a.scopesPage.SetOnEnter(func(tag string) {
		a.setScope(tag)
		a.refreshCurrent()
	})
	a.sourcesPage.SetOnScopeEdit(func(id string, current []string) {
		input := tview.NewInputField().
			SetLabel(" Scopes (comma-separated): ").
			SetText(strings.Join(current, ",")).
			SetFieldWidth(60)
		input.SetDoneFunc(func(key tcell.Key) {
			a.setRoot(a.rootFlex)
			if key == tcell.KeyEnter {
				scopes := client.SplitScopes(input.GetText())
				go func() { // off the loop, like every page's mutation (#1126)
					_, err := a.client.ConnectorSetTags(id, scopes)
					resources.QueueUpdate(a.tv, func() {
						if err != nil {
							a.showError("set scopes: " + err.Error())
							return
						}
						a.sourcesPage.Refresh()
						a.footer.SetToast(a.tv, "✓ scopes updated", 2*time.Second)
						a.sourcesPage.ScopesSet(id, current, scopes)
					})
				}()
			}
		})
		modal := tview.NewFlex().SetDirection(tview.FlexRow).
			AddItem(a.rootFlex, 0, 1, false).
			AddItem(input, 1, 0, true)
		a.setRoot(modal)
		a.tv.SetFocus(input)
	})
	a.switchTo = func(name string) {
		if a.retired.Load() {
			return
		}
		if a.describeOpen {
			a.closeDescribe()
		}
		switch name {
		case "modules":
			a.modulesPage.Refresh()
			a.pages.SwitchToPage("modules")
			a.header.SetResource(":modules")
			a.footer.SetHints(a.modulesPage.Hints())
		case "sources":
			a.sourcesPage.Refresh()
			a.pages.SwitchToPage("sources")
			a.header.SetResource(":sources")
			a.footer.SetHints(a.sourcesPage.Hints())
		case "runs":
			a.runsPage.Refresh()
			a.pages.SwitchToPage("runs")
			a.header.SetResource(":runs")
			a.footer.SetHints(a.runsPage.Hints())
		case "dlq":
			a.dlqPage.Refresh()
			a.pages.SwitchToPage("dlq")
			a.header.SetResource(":dlq")
			a.footer.SetHints(a.dlqPage.Hints())
		case "conflicts":
			a.conflictsPage.Refresh()
			a.pages.SwitchToPage("conflicts")
			a.header.SetResource(":conflicts")
			a.footer.SetHints(a.conflictsPage.Hints())
		case "ambiguous":
			a.ambiguousPage.Refresh()
			a.pages.SwitchToPage("ambiguous")
			a.header.SetResource(":ambiguous")
			a.footer.SetHints(a.ambiguousPage.Hints())
			a.tv.SetFocus(a.ambiguousPage.FocusPrimitive())
		case "observations":
			a.observationsPage.Refresh()
			a.pages.SwitchToPage("observations")
			a.header.SetResource(":observations")
			a.footer.SetHints(a.observationsPage.Hints())
		case "outputs":
			a.outputsPage.Refresh()
			a.pages.SwitchToPage("outputs")
			a.header.SetResource(":outputs")
			a.footer.SetHints(a.outputsPage.Hints())
		case "inbox":
			a.inboxPage.Refresh()
			a.pages.SwitchToPage("inbox")
			a.header.SetResource(":inbox")
			a.footer.SetHints(a.inboxPage.Hints())
		case "new":
			a.newPage.Refresh()
			a.pages.SwitchToPage("new")
			a.header.SetResource(":new")
			a.footer.SetHints(a.newPage.Hints())
		case "view":
			a.viewPage.Refresh()
			a.pages.SwitchToPage("view")
			a.header.SetResource(":view")
			a.footer.SetHints(a.viewPage.Hints())
		case "hooks":
			a.hooksPage.Refresh()
			a.pages.SwitchToPage("hooks")
			a.header.SetResource(":hooks")
			a.footer.SetHints(a.hooksPage.Hints())
		case "migrations":
			a.migrationsPage.Refresh()
			a.pages.SwitchToPage("migrations")
			a.header.SetResource(":migrations")
			a.footer.SetHints(a.migrationsPage.Hints())
		case "rag":
			a.ragPage.Refresh()
			a.pages.SwitchToPage("rag")
			a.header.SetResource(":rag")
			a.footer.SetHints(a.ragPage.Hints())
		case "scopes":
			a.scopesPage.Refresh()
			a.pages.SwitchToPage("scopes")
			a.header.SetResource(":scopes")
			a.footer.SetHints(a.scopesPage.Hints())
		case "endpoints":
			a.endpointsPage.Refresh()
			a.pages.SwitchToPage("endpoints")
			a.header.SetResource(":endpoints")
			a.footer.SetHints(a.endpointsPage.Hints())
		case "apps", "workflows", "views", "processes":
			p := a.pageByName(name).(*resources.Manifests)
			p.Refresh()
			a.pages.SwitchToPage(name)
			a.header.SetResource(":" + name)
			a.footer.SetHints(p.Hints())
		case "tables":
			a.tablesPage.Refresh()
			a.pages.SwitchToPage("tables")
			a.header.SetResource(":tables")
			a.footer.SetHints(a.tablesPage.Hints())
		case "entities":
			a.entitiesPage.Refresh()
			a.pages.SwitchToPage("entities")
			a.header.SetResource(":entities")
			a.footer.SetHints(a.entitiesPage.Hints())
		case "secrets":
			a.secretsPage.Refresh()
			a.pages.SwitchToPage("secrets")
			a.header.SetResource(":secrets")
			a.footer.SetHints(a.secretsPage.Hints())
		case "integrations":
			a.integrationsPage.Refresh()
			a.pages.SwitchToPage("integrations")
			a.header.SetResource(":integrations")
			a.footer.SetHints(a.integrationsPage.Hints())
		case "notify":
			a.notifyPage.Refresh()
			a.pages.SwitchToPage("notify")
			a.header.SetResource(":notify")
			a.footer.SetHints(a.notifyPage.Hints())
		case "ask":
			a.askPage.SetScope(a.currentScope)
			a.askPage.Refresh()
			a.pages.SwitchToPage("ask")
			a.header.SetResource(":ask")
			a.footer.SetHints(a.askPage.Hints())
			a.tv.SetFocus(a.askPage.FocusPrimitive())
		default: // a page with nothing special to do on arrival (:exports, :search) needs no case here
			if p, ok := a.pageByName(name).(interface {
				Refresh()
				Hints() string
			}); ok {
				p.Refresh()
				a.pages.SwitchToPage(name)
				a.header.SetResource(":" + name)
				a.footer.SetHints(p.Hints())
			}
		}
		// A page keeps its / filter while hidden: coming back, the header must say it again.
		if f, ok := a.pageByName(name).(Filterable); ok && f.TextFilter() != "" {
			a.header.SetResource(filteredResource(name, f.TextFilter()))
		}
	}

	a.rootFlex = tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(a.header.View(), 1, 0, false).
		AddItem(a.pages, 0, 1, true).
		AddItem(a.footer.View(), 1, 0, false)

	a.setRoot(a.rootFlex)
	a.tv.EnableMouse(false).EnablePaste(false)
	a.bindGlobalKeys()
	return a
}

// setRoot is the only way the app takes the screen: it records whether an overlay (the help, a
// text, a prompt, :context…) holds it, and the global keys leave an overlay its own.
func (a *App) setRoot(root tview.Primitive) {
	a.overlaid = root != a.rootFlex
	a.tv.SetRoot(root, true)
}

// suspendEditor hands the terminal to $EDITOR — a variable so a test can stand in for the editor.
var suspendEditor = resources.SuspendEditor

// suspend is every page's way to $EDITOR.
func (a *App) suspend(f func()) {
	if a.retired.Load() {
		return
	}
	suspendEditor(a.tv, a.showError, f)
}

// setTextFilter applies a page's / filter and says it in the header, where the page name is: the
// table showing fewer rows than the hub holds must never pass for the whole list. Esc clears it.
func (a *App) setTextFilter(name string, f Filterable, q string) {
	f.SetTextFilter(q)
	a.header.SetResource(filteredResource(name, q))
	if h, ok := f.(interface{ Hints() string }); ok { // a / that changes the page's gestures (:search)
		a.footer.SetHints(h.Hints())
	}
}

func filteredResource(name, q string) string {
	if q == "" {
		return ":" + name
	}
	return ":" + name + " /" + tview.Escape(q) + " (Esc clears)"
}

// restoreRoot puts the main layout back after an overlay and gives the front page the keys.
func (a *App) restoreRoot() {
	a.setRoot(a.rootFlex)
	if _, p := a.pages.GetFrontPage(); p != nil {
		a.tv.SetFocus(p)
	}
}

// showText shows a read-only text (a resolution history) over the screen until Esc.
func (a *App) showText(title, body string) {
	if a.retired.Load() {
		return
	}
	view := tview.NewTextView().SetDynamicColors(true).SetText(body)
	view.SetBorder(true).SetTitle(title + "(Esc closes) ")
	view.SetInputCapture(func(ev *tcell.EventKey) *tcell.EventKey {
		if ev.Key() == tcell.KeyEscape {
			a.restoreRoot()
			return nil
		}
		return ev
	})
	a.setRoot(view)
	a.tv.SetFocus(view)
}

// prompt asks one line at the foot of the screen; masked hides what is typed (a secret value is
// never drawn). done runs only on Enter — Esc abandons.
func (a *App) prompt(label string, masked bool, done func(string)) {
	if a.retired.Load() {
		return
	}
	input := tview.NewInputField().SetLabel(" " + tview.Escape(label)).SetFieldWidth(60)
	if masked {
		input.SetMaskCharacter('•')
	}
	input.SetDoneFunc(func(key tcell.Key) {
		text := input.GetText()
		a.restoreRoot()
		if key == tcell.KeyEnter {
			done(text)
		}
	})
	a.setRoot(tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(a.rootFlex, 0, 1, false).
		AddItem(input, 1, 0, true))
	a.tv.SetFocus(input)
}

// confirmText is the confirmation's text. Both parts are data — a connector name, a context from
// the config — and a Modal parses colour tags: escaped, so a name cannot restyle the question.
func confirmText(question, context string) string {
	text := tview.Escape(question)
	if context != "" {
		text += "\n\non " + tview.Escape(context)
	}
	return text
}

// confirmAs asks a destructive question in a modal that also names the hub, Cancel focused: Enter
// alone never deletes (#1110). Its yes button is named by the page for what it does — Delete,
// Remove, Reindex, Re-stamp — and there is no default: a page that names none does not compile (#1158).
func (a *App) confirmAs(question, yesLabel string, yes func()) {
	if a.retired.Load() {
		return
	}
	modal := tview.NewModal().SetText(confirmText(question, a.contextLabel)).AddButtons([]string{"Cancel", yesLabel})
	modal.SetDoneFunc(func(_ int, label string) {
		a.restoreRoot()
		if label == yesLabel {
			yes()
		}
	})
	a.setRoot(tview.NewPages().
		AddPage("main", a.rootFlex, true, true).
		AddPage("confirm", modal, true, true))
	a.tv.SetFocus(modal)
}

func (a *App) showError(msg string) {
	// msg carries raw server/os error text — escaped here (DynamicColors footer).
	a.footer.SetHints("ERROR: " + tview.Escape(msg))
}

func (a *App) bindGlobalKeys() {
	a.tv.SetInputCapture(func(ev *tcell.EventKey) *tcell.EventKey {
		if ev.Key() == tcell.KeyCtrlC {
			a.tv.Stop()
			return nil
		}
		if in, ok := a.tv.GetFocus().(*tview.InputField); ok {
			// A page's own field (:ambiguous' entity, :ask's question, :view's file) can hold the
			// keys: `:` in it while empty opens the palette — nothing typed there starts with one
			// (#1175). An overlay's field (the palette, a prompt) keeps its `:`.
			if ev.Rune() == ':' && in.GetText() == "" && !a.overlaid {
				a.showPalette()
				return nil
			}
			return ev
		}
		if _, ok := a.tv.GetFocus().(*tview.Button); ok {
			return ev // a confirmation is open: its keys answer it, they do not quit or switch pages
		}
		if a.overlaid { // the overlay owns the keys (its Esc closes it); q still quits
			if ev.Rune() == 'q' {
				a.tv.Stop()
				return nil
			}
			return ev
		}
		if a.describeOpen {
			inPanel := a.tv.GetFocus() == a.describe.body
			switch {
			case ev.Key() == tcell.KeyTab:
				a.focusDescribe(!inPanel)
				return nil
			case ev.Key() == tcell.KeyEscape && inPanel: // the keys go back first; a second Esc closes
				a.focusDescribe(false)
				return nil
			}
		}
		if ev.Key() == tcell.KeyEscape {
			if a.describeOpen {
				a.closeDescribe()
				return nil
			}
			// Esc unwinds the narrowest filter first: the / text, then the drill-down.
			pageName, _ := a.pages.GetFrontPage()
			if f, ok := a.pageByName(pageName).(Filterable); ok && f.TextFilter() != "" {
				a.setTextFilter(pageName, f, "")
				return nil
			}
			if d, ok := a.pageByName(pageName).(Drillable); ok && d.FilterActive() {
				d.ClearFilter()
				a.footer.SetHints(d.Hints())
				return nil
			}
			if pageName == "endpoints" {
				a.switchTo("sources")
				return nil
			}
		}
		switch ev.Rune() {
		case ':':
			a.showPalette()
			return nil
		case '?':
			a.showHelp()
			return nil
		case 'r':
			a.refreshCurrent()
			return nil
		case 'd':
			a.toggleDescribe()
			return nil
		case 'y':
			a.yankCurrent()
			return nil
		case '/':
			name, _ := a.pages.GetFrontPage()
			if f, ok := a.pageByName(name).(Filterable); ok {
				a.prompt("/", false, func(q string) { a.setTextFilter(name, f, strings.TrimSpace(q)) })
				return nil
			}
		case 'q':
			a.tv.Stop()
			return nil
		}
		return ev
	})
}

func (a *App) toggleDescribe() {
	if a.describeOpen {
		a.closeDescribe()
		return
	}
	pageName, primitive := a.pages.GetFrontPage()
	if pageName == "" || primitive == nil {
		return
	}
	d, ok := a.currentDescribable(pageName)
	if !ok {
		return
	}
	title, body, hasSel := d.SelectedDescribe()
	if !hasSel {
		return
	}
	a.describe.SetContent(title, body)
	a.describeSrc = d
	// SetSelectionChangedFunc fires on the UI thread (input + render are
	// single-threaded in tview), so update the panel DIRECTLY. A QueueUpdate
	// here is re-entrant — it blocks the update loop waiting on itself, the
	// exact deadlock that froze the rehearsal on Esc-from-describe (carnet #9,
	// the real cause: the runs off-thread fetch only masked it).
	d.OnSelectionChange(func() {
		t, b, ok := d.SelectedDescribe()
		if !ok {
			// The row is gone — a live refresh failed or emptied the list (#891): the panel
			// stays open across ticks now, so it must not keep presenting it.
			a.closeDescribe()
			return
		}
		a.describe.SetContent(t, b)
	})

	split := tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(primitive, 0, 6, true).
		AddItem(a.describe.View(), 0, 4, false)

	a.rootFlex.Clear().
		AddItem(a.header.View(), 1, 0, false).
		AddItem(split, 0, 1, true).
		AddItem(a.footer.View(), 1, 0, false)
	a.tv.SetFocus(primitive)
	a.describeOpen = true
	a.footer.SetNote(describeTabHint)
}

// The footer's word on the describe panel: how to give it the keys, and, once it has them, how to
// scroll and give them back (#1173).
const (
	describeTabHint     = "Tab: scroll the describe panel"
	describeFocusedHint = "↑↓ PgUp PgDn: scroll the describe panel · Tab/Esc: back to the list"
)

// focusDescribe gives the describe panel the keys (in), so ↑↓ PgUp PgDn scroll a body longer than
// its share of the screen — a failed run's error, a connector's config — or gives them back to the
// page (#1173).
func (a *App) focusDescribe(in bool) {
	if in {
		a.tv.SetFocus(a.describe.body)
		a.footer.SetNote(describeFocusedHint)
		return
	}
	if _, p := a.pages.GetFrontPage(); p != nil {
		a.tv.SetFocus(p)
	}
	a.footer.SetNote(describeTabHint)
}

func (a *App) closeDescribe() {
	if !a.describeOpen {
		return
	}
	a.describe.Reset()
	a.footer.SetNote("")
	a.rootFlex.Clear().
		AddItem(a.header.View(), 1, 0, false).
		AddItem(a.pages, 0, 1, true).
		AddItem(a.footer.View(), 1, 0, false)
	if a.describeSrc != nil {
		a.describeSrc.OnSelectionChange(nil)
		a.describeSrc = nil
	}
	_, primitive := a.pages.GetFrontPage()
	if primitive != nil {
		a.tv.SetFocus(primitive)
	}
	a.describeOpen = false
}

// pageByName is the single name→page map — the palette, switchTo, the d/y resolvers
// and the live ticker all deal in these names; a page missing here silently loses its keys
// (guarded by TestPageByNameCoversAllPaletteEntries).
func (a *App) pageByName(name string) any {
	switch name {
	case "ask":
		return pageOrNil(a.askPage)
	case "inbox":
		return pageOrNil(a.inboxPage)
	case "new":
		return pageOrNil(a.newPage)
	case "processes":
		return pageOrNil(a.processesPage)
	case "view":
		return pageOrNil(a.viewPage)
	case "modules":
		return pageOrNil(a.modulesPage)
	case "sources":
		return pageOrNil(a.sourcesPage)
	case "runs":
		return pageOrNil(a.runsPage)
	case "dlq":
		return pageOrNil(a.dlqPage)
	case "conflicts":
		return pageOrNil(a.conflictsPage)
	case "ambiguous":
		return pageOrNil(a.ambiguousPage)
	case "observations":
		return pageOrNil(a.observationsPage)
	case "outputs":
		return pageOrNil(a.outputsPage)
	case "hooks":
		return pageOrNil(a.hooksPage)
	case "migrations":
		return pageOrNil(a.migrationsPage)
	case "rag":
		return pageOrNil(a.ragPage)
	case "scopes":
		return pageOrNil(a.scopesPage)
	case "endpoints":
		return pageOrNil(a.endpointsPage)
	case "secrets":
		return pageOrNil(a.secretsPage)
	case "integrations":
		return pageOrNil(a.integrationsPage)
	case "notify":
		return pageOrNil(a.notifyPage)
	case "entities":
		return pageOrNil(a.entitiesPage)
	case "tables":
		return pageOrNil(a.tablesPage)
	case "apps":
		return pageOrNil(a.appsPage)
	case "workflows":
		return pageOrNil(a.workflowsPage)
	case "views":
		return pageOrNil(a.viewsPage)
	case "exports":
		return pageOrNil(a.exportsPage)
	case "search":
		return pageOrNil(a.searchPage)
	}
	return nil
}

// pageOrNil returns an UNTYPED nil for a nil page pointer — a typed nil inside
// the interface would defeat the conservation test and let the d/y resolvers
// type-assert into a nil-receiver panic.
func pageOrNil[T any](p *T) any {
	if p == nil {
		return nil
	}
	return p
}

func (a *App) currentDescribable(name string) (Describable, bool) {
	d, ok := a.pageByName(name).(Describable)
	return d, ok
}

func (a *App) currentYankable(name string) (Yankable, bool) {
	y, ok := a.pageByName(name).(Yankable)
	return y, ok
}

func (a *App) yankCurrent() {
	pageName, _ := a.pages.GetFrontPage()
	if y, ok := a.pageByName(pageName).(YankableLater); ok {
		y.YankLater(a.yank)
		return
	}
	y, ok := a.currentYankable(pageName)
	if !ok {
		return
	}
	text, hasSel := y.SelectedYank()
	if !hasSel {
		return
	}
	a.yank(text)
}

// yank copies text to the clipboard and says so. It opens nothing, so a follow-up landing on an app
// :context has retired needs no guard: it copies what the integrator asked for.
func (a *App) yank(text string) {
	if err := Yank(text); err != nil {
		a.showError("yank: " + err.Error())
		return
	}
	a.footer.SetToast(a.tv, "✓ yanked", 2*time.Second)
}

func (a *App) refreshCurrent() {
	name, _ := a.pages.GetFrontPage()
	if a.switchTo != nil {
		a.switchTo(name)
	}
}

func (a *App) showPalette() {
	if a.palette == nil {
		return
	}
	input := tview.NewInputField().SetLabel(" :").SetFieldWidth(40)
	// Autocomplete the page name from the palette — typing ':so' offers ':sources'
	// (rehearsal feedback #4: typing full names was tedious). Tab/Enter accepts.
	input.SetAutocompleteFunc(func(current string) []string {
		if strings.TrimSpace(current) == "" {
			return nil
		}
		return a.palette.Autocomplete(strings.TrimSpace(current))
	})
	var done func(key tcell.Key)
	input.SetAutocompletedFunc(func(text string, index, source int) bool {
		switch {
		case source == tview.AutocompletedNavigate:
			return false // let arrows browse the list without committing
		case source == tview.AutocompletedEnter && text == strings.TrimSpace(input.GetText()):
			done(tcell.KeyEnter) // the name is typed in full: accepting it is the switch (#1175)
			return true
		}
		input.SetText(text)
		return true // Tab/Enter/click commits the highlighted suggestion
	})
	done = func(key tcell.Key) {
		text := strings.TrimSpace(input.GetText())
		a.setRoot(a.rootFlex)
		if key != tcell.KeyEnter || text == "" {
			return
		}
		// :scope <tag>  — special two-word command (switch session scope, no page change)
		if strings.HasPrefix(text, "scope ") {
			tag := strings.TrimSpace(strings.TrimPrefix(text, "scope "))
			if tag == "" {
				return
			}
			a.setScope(tag)
			a.footer.SetToast(a.tv, "✓ scope: "+tag, 2*time.Second)
			return
		}
		if text == "context" || text == "ctx" {
			a.showContexts()
			return
		}
		if name := a.palette.Resolve(text); name != "" && a.switchTo != nil {
			a.switchTo(name)
		} else if name == "" {
			// Silence here cost the rehearsal real minutes (carnet #2): an unknown
			// or ambiguous entry must SAY so, not swallow the Enter.
			a.footer.SetToast(a.tv, "unknown page: :"+text+"  (? lists them)", 3*time.Second)
		}
	}
	input.SetDoneFunc(done)
	modalFlex := tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(a.rootFlex, 0, 1, false).
		AddItem(input, 1, 0, true)
	a.setRoot(modalFlex)
	a.tv.SetFocus(input)
}

func (a *App) startLive() {
	ctx, cancel := context.WithCancel(context.Background())
	a.sseStop = cancel
	go a.liveTicker(ctx)
	events := make(chan client.SSEEvent, 64)
	go func() {
		a.live.Set(LiveConnecting)
		lastErr := ""
		a.client.SSEStreamWithReconnect(ctx, "/api/platform/events/stream",
			[]string{"connector.run.*"}, events, 5*time.Second, func(err error) {
				a.live.Observe(err)
				// Said once per distinct reason, not every 5 s retry: a 403 or a hub that is down
				// must be visible, but must not take the footer over.
				if err == nil {
					lastErr = ""
				} else if msg := err.Error(); msg != lastErr {
					lastErr = msg
					resources.QueueUpdate(a.tv, func() { a.showError("live events: " + msg) })
				}
			})
		a.live.Set(LiveOffline)
	}()
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case ev, ok := <-events:
				if !ok {
					return
				}
				a.dispatchEvent(ev)
			}
		}
	}()
}

func (a *App) dispatchEvent(ev client.SSEEvent) {
	var m map[string]any
	if err := json.Unmarshal([]byte(ev.Data), &m); err != nil {
		return
	}
	topic, _ := m["topic"].(string)
	if !strings.HasPrefix(topic, "connector.run.") {
		return
	}
	// A run starting or ending is when a connector's status, an endpoint's and the dead letters
	// change. Only the page in front is refreshed: switchTo refetches on arrival, so a hidden page
	// has nothing to keep fresh — and each :runs fetch is the RunList of #892, two per endpoint
	// run (#891). The hub emits no connector.dlq.* topic: :dlq hears runs instead (#1109).
	for _, page := range []string{"runs", "sources", "dlq", "endpoints"} {
		a.refreshIfActive(page)
	}
}

func (a *App) liveTicker(ctx context.Context) {
	headerTick := time.NewTicker(1 * time.Second)
	moduleTick := time.NewTicker(30 * time.Second)
	hookTick := time.NewTicker(15 * time.Second)
	migTick := time.NewTicker(30 * time.Second)
	connTick := time.NewTicker(5 * time.Second)
	defer func() {
		headerTick.Stop()
		moduleTick.Stop()
		hookTick.Stop()
		migTick.Stop()
		connTick.Stop()
	}()
	for {
		select {
		case <-ctx.Done():
			return
		case <-headerTick.C:
			resources.QueueUpdate(a.tv, func() {
				a.header.SetLiveStatus(a.live.Label())
			})
		case <-moduleTick.C:
			a.refreshIfActive("modules")
		case <-hookTick.C:
			a.refreshIfActive("hooks")
			a.refreshIfActive("processes")
		case <-migTick.C:
			a.refreshIfActive("migrations")
		case <-connTick.C:
			a.refreshIfActive("sources")
			a.refreshIfActive("inbox")
			a.refreshIfActive("dlq")
			a.refreshIfActive("endpoints")
		}
	}
}

// refreshIfActive is the live ticker's refresh of the page in front: the page's own Refresh, which
// fetches off the loop (offLoop), never switchTo — that closes an open describe panel and rewrites
// the footer (#891). The front page is read on the loop, where pages is written.
func (a *App) refreshIfActive(name string) {
	if resources.EditorSuspended.Load() {
		return // never QueueUpdateDraw against a suspended screen
	}
	resources.QueueUpdate(a.tv, func() {
		if current, _ := a.pages.GetFrontPage(); current != name {
			return
		}
		if p, ok := a.pageByName(name).(interface{ Refresh() }); ok {
			p.Refresh()
		}
	})
}

// setScope is the one place the session scope changes: the value :ask sends, and what the
// header shows. Three writers (the :scopes page, the :scope command, the startup seed) must
// never disagree.
func (a *App) setScope(tag string) {
	a.currentScope = tag
	a.header.SetScope(tag)
	a.askPage.SetScope(tag)
}

// seedScope applies a default read at startup only while the session is still unscoped: a
// scope the user picked before the reply landed must win over it.
func (a *App) seedScope(tag string) {
	if a.currentScope == "" {
		a.setScope(tag)
	}
}

// contextLabel is where the header says lm is: the context name, with the server's host when the
// name does not already say it. It used to read "local" whatever the hub (#1108).
func contextLabel(name, server string) string {
	host := server
	if u, err := url.Parse(server); err == nil && u.Host != "" {
		host = u.Host
	}
	switch {
	case name == "":
		return host
	case host == "" || strings.Contains(name, host):
		return name
	}
	return name + " (" + host + ")"
}

func Run(c *client.Client) error {
	a := newApp(c)
	a.start("sources") // the integrator's home; :modules is often empty for this persona
	return a.tv.Run()
}

// start is what a fresh app does once built — at launch and after a :context switch: name its
// hub, open the live stream and its ticker, show page and load it, seed the session scope from the hub.
func (a *App) start(page string) {
	a.contextLabel = contextLabel(a.client.ContextName(), a.client.Server())
	a.header.SetContext(tview.Escape(a.contextLabel)) // the header parses colour tags
	a.startLive()
	// Show the page's frame at once (no network yet), then load its data AFTER the loop is
	// pumping — so an auth-dead startup can raise the re-login modal via QueueUpdate
	// (feedback #1), and a :context switch never leaves a blank centre pane for a frame.
	a.pages.SwitchToPage(page)
	a.header.SetResource(":" + page)
	if p, ok := a.pageByName(page).(interface{ Hints() string }); ok {
		a.footer.SetHints(p.Hints())
	}
	go resources.QueueUpdate(a.tv, func() { a.switchTo(page) })
	// The hub asks the user's default scope when a request names none (#394), so a header
	// reading "system" would lie for a user with one. Seed the session from it — after the
	// loop is pumping, like the landing data; a failed read (auth-dead, no user row) just
	// leaves the session unscoped, which is what the server falls back to as well.
	go func() {
		if s, err := a.client.GetMyScope(); err == nil && s != "" {
			resources.QueueUpdate(a.tv, func() { a.seedScope(s) })
		}
	}()
}
