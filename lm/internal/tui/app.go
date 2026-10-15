package tui

import (
	"context"
	"encoding/json"
	"strings"
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

// Yankable: pages that support `y` (copy current selection to clipboard).
type Yankable interface {
	SelectedYank() (text string, ok bool)
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
	processesPage    *resources.Processes
	viewPage         *resources.ViewPage
	hooksPage        *resources.Hooks
	migrationsPage   *resources.Migrations
	scopesPage       *resources.Scopes
	endpointsPage    *resources.Endpoints
	ambiguousPage    *resources.Ambiguous
	askPage          *resources.Ask
	live             *LiveStatus
	sseStop          context.CancelFunc
	describe         *DescribePanel
	describeOpen     bool
	describeSrc      Describable
	currentScope     string
}

func newApp(c *client.Client) *App {
	a := &App{
		tv:     tview.NewApplication(),
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
	a.endpointsPage = resources.NewEndpoints(c)
	a.pages.AddPage("endpoints", a.endpointsPage.View(), true, false)
	a.sourcesPage.SetOnEnter(func(connectorID, kind string) {
		if kind == "rest" {
			a.endpointsPage.SetFilter(connectorID)
			a.switchTo("endpoints")
		} else {
			a.runsPage.SetFilter(connectorID)
			a.switchTo("runs")
		}
	})
	a.endpointsPage.SetOnEnter(func(endpointID string) {
		a.runsPage.SetEndpointFilter(endpointID)
		a.switchTo("runs")
	})
	a.runsPage = resources.NewRuns(a.tv, c, func(m string) {
		a.footer.SetToast(a.tv, m, 3*time.Second)
	})
	a.pages.AddPage("runs", a.runsPage.View(), true, false)
	a.dlqPage = resources.NewDLQ(c, a.showError)
	a.pages.AddPage("dlq", a.dlqPage.View(), true, false)
	a.conflictsPage = resources.NewConflicts(c, a.showError)
	a.pages.AddPage("conflicts", a.conflictsPage.View(), true, false)
	a.observationsPage = resources.NewObservations(c, a.showError)
	a.pages.AddPage("observations", a.observationsPage.View(), true, false)
	a.outputsPage = resources.NewOutputs(c, a.showError, func(m string) {
		a.footer.SetToast(a.tv, m, 3*time.Second)
	})
	a.pages.AddPage("outputs", a.outputsPage.View(), true, false)
	a.hooksPage = resources.NewHooks(a.tv, c, a.showError)
	a.pages.AddPage("hooks", a.hooksPage.View(), true, false)
	a.migrationsPage = resources.NewMigrations(a.tv, c)
	a.pages.AddPage("migrations", a.migrationsPage.View(), true, false)
	a.scopesPage = resources.NewScopes(c)
	a.pages.AddPage("scopes", a.scopesPage.View(), true, false)
	a.inboxPage = resources.NewInbox(a.tv, c, a.showError, func(m string) {
		a.footer.SetToast(a.tv, m, 2*time.Second)
	})
	a.pages.AddPage("inbox", a.inboxPage.View(), true, false)
	a.newPage = resources.NewNewKind(c, func(f func()) { resources.SuspendEditor(a.tv, a.showError, f) }, a.showError, func(m string) {
		a.footer.SetToast(a.tv, m, 4*time.Second)
	}, func(page string) {
		if a.switchTo != nil {
			a.switchTo(page)
		}
	}, func(h string) {
		a.footer.SetHints(h)
	})
	a.pages.AddPage("new", a.newPage.View(), true, false)
	a.processesPage = resources.NewProcesses(a.tv, c, func(name string) {
		yaml, err := c.ProcessManifest(name)
		if err != nil {
			a.showError("manifest: " + err.Error())
			return
		}
		a.switchTo("new")
		if !a.newPage.EditExisting("Process", yaml) {
			a.showError("Process is not in the :new kind registry — cannot edit")
		}
	}, a.showError)
	a.pages.AddPage("processes", a.processesPage.View(), true, false)
	a.viewPage = resources.NewViewPage(a.tv, c,
		func(f func()) { resources.SuspendEditor(a.tv, a.showError, f) },
		func() string { return a.currentScope },
		func() bool { n, _ := a.pages.GetFrontPage(); return n == "view" },
		a.showError,
		func(m string) { a.footer.SetToast(a.tv, m, 3*time.Second) },
		func(h string) { a.footer.SetHints(h) })
	a.pages.AddPage("view", a.viewPage.View(), true, false)
	a.askPage = resources.NewAsk(a.tv, c, a.showError, func(m string) {
		a.footer.SetToast(a.tv, m, 2*time.Second)
	})
	a.pages.AddPage("ask", a.askPage.View(), true, false)
	a.ambiguousPage = resources.NewAmbiguous(a.tv, c, a.showError, func(m string) {
		a.footer.SetToast(a.tv, m, 3*time.Second)
	})
	a.pages.AddPage("ambiguous", a.ambiguousPage.View(), true, false)
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
			a.tv.SetRoot(a.rootFlex, true)
			if key == tcell.KeyEnter {
				scopes := client.SplitScopes(input.GetText())
				if _, err := a.client.ConnectorSetTags(id, scopes); err != nil {
					a.showError("set scopes: " + err.Error())
					return
				}
				a.sourcesPage.Refresh()
				a.footer.SetToast(a.tv, "✓ scopes updated", 2*time.Second)
			}
		})
		modal := tview.NewFlex().SetDirection(tview.FlexRow).
			AddItem(a.rootFlex, 0, 1, false).
			AddItem(input, 1, 0, true)
		a.tv.SetRoot(modal, true)
		a.tv.SetFocus(input)
	})
	a.switchTo = func(name string) {
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
		case "processes":
			a.processesPage.Refresh()
			a.pages.SwitchToPage("processes")
			a.header.SetResource(":processes")
			a.footer.SetHints(a.processesPage.Hints())
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
		case "ask":
			a.askPage.SetScope(a.currentScope)
			a.askPage.Refresh()
			a.pages.SwitchToPage("ask")
			a.header.SetResource(":ask")
			a.footer.SetHints(a.askPage.Hints())
			a.tv.SetFocus(a.askPage.FocusPrimitive())
		}
	}

	a.rootFlex = tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(a.header.View(), 1, 0, false).
		AddItem(a.pages, 0, 1, true).
		AddItem(a.footer.View(), 1, 0, false)

	a.tv.SetRoot(a.rootFlex, true).EnableMouse(false).EnablePaste(false)
	a.bindGlobalKeys()
	return a
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
		if _, ok := a.tv.GetFocus().(*tview.InputField); ok {
			return ev
		}
		if ev.Key() == tcell.KeyEscape {
			if a.describeOpen {
				a.closeDescribe()
				return nil
			}
			pageName, _ := a.pages.GetFrontPage()
			if pageName == "runs" && a.runsPage.FilterActive() {
				a.runsPage.ClearFilter()
				a.footer.SetHints(a.runsPage.Hints())
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
}

func (a *App) closeDescribe() {
	if !a.describeOpen {
		return
	}
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
	case "scopes":
		return pageOrNil(a.scopesPage)
	case "endpoints":
		return pageOrNil(a.endpointsPage)
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
	y, ok := a.currentYankable(pageName)
	if !ok {
		return
	}
	text, hasSel := y.SelectedYank()
	if !hasSel {
		return
	}
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
	input.SetAutocompletedFunc(func(text string, index, source int) bool {
		if source == tview.AutocompletedNavigate {
			return false // let arrows browse the list without committing
		}
		input.SetText(text)
		return true // Tab/Enter/click commits the highlighted suggestion
	})
	input.SetDoneFunc(func(key tcell.Key) {
		text := strings.TrimSpace(input.GetText())
		a.tv.SetRoot(a.rootFlex, true)
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
		if name := a.palette.Resolve(text); name != "" && a.switchTo != nil {
			a.switchTo(name)
		} else if name == "" {
			// Silence here cost the rehearsal real minutes (carnet #2): an unknown
			// or ambiguous entry must SAY so, not swallow the Enter.
			a.footer.SetToast(a.tv, "unknown page: :"+text+"  (? lists them)", 3*time.Second)
		}
	})
	modalFlex := tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(a.rootFlex, 0, 1, false).
		AddItem(input, 1, 0, true)
	a.tv.SetRoot(modalFlex, true)
	a.tv.SetFocus(input)
}

func (a *App) startLive() {
	ctx, cancel := context.WithCancel(context.Background())
	a.sseStop = cancel
	events := make(chan client.SSEEvent, 64)
	go func() {
		a.live.Set(LivePolling)
		a.client.SSEStreamWithReconnect(ctx, "/api/platform/events/stream",
			[]string{"connector.run.*", "connector.dlq.*"}, events, 5*time.Second)
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
				a.live.Set(LiveSSE)
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
	resources.QueueUpdate(a.tv, func() {
		switch {
		case strings.HasPrefix(topic, "connector.run."):
			// Only in front: switchTo("runs") refetches on arrival, so a hidden :runs has nothing to
			// keep fresh — and each fetch is the RunList of #892, two per endpoint run (#891).
			if front, _ := a.pages.GetFrontPage(); front == "runs" {
				a.runsPage.ApplyEvent(m)
			}
		case strings.HasPrefix(topic, "connector.dlq."):
			a.dlqPage.ApplyEvent(m)
		}
	})
}

func (a *App) liveTicker() {
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

func Run(c *client.Client) error {
	a := newApp(c)
	a.header.SetContext("local")
	// Show the landing frame immediately (no network yet), then load its data AFTER
	// the loop is pumping — so an auth-dead startup can raise the re-login modal via
	// QueueUpdate (feedback #1). Loading before tv.Run() would block that modal.
	a.pages.SwitchToPage("sources") // the integrator's home; :modules is often empty for this persona
	a.header.SetResource(":sources")
	a.footer.SetHints(a.sourcesPage.Hints())
	a.startLive()
	go a.liveTicker()
	go resources.QueueUpdate(a.tv, func() { a.switchTo("sources") })
	// The hub asks the user's default scope when a request names none (#394), so a header
	// reading "system" would lie for a user with one. Seed the session from it — after the
	// loop is pumping, like the landing data; a failed read (auth-dead, no user row) just
	// leaves the session unscoped, which is what the server falls back to as well.
	go func() {
		if s, err := c.GetMyScope(); err == nil && s != "" {
			resources.QueueUpdate(a.tv, func() { a.seedScope(s) })
		}
	}()
	return a.tv.Run()
}
