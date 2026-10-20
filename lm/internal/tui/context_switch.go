package tui

import (
	"sort"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/rivo/tview"
)

// contextChoice is one row :context offers: a configured context, said as the header would say
// it, marked when it is the one this session talks to.
type contextChoice struct{ name, label string }

// contextChoices names each context with the host a switch would reach — Resolve's, so a LUMNIK_URL
// pinning every context to one hub shows — and the current one with server, the hub this session
// talks to (lm ui --server).
func contextChoices(cfg *config.Config, current, server string) []contextChoice {
	names := make([]string, 0, len(cfg.Contexts))
	for name := range cfg.Contexts {
		names = append(names, name)
	}
	sort.Strings(names)
	out := make([]contextChoice, len(names))
	for i, name := range names {
		mark, host := "  ", cfg.Contexts[name].Server
		if ctx, err := cfg.Resolve(name); err == nil {
			host = ctx.Server
		}
		if name == current {
			mark, host = "● ", server
		}
		out[i] = contextChoice{name, mark + contextLabel(name, host)}
	}
	return out
}

// loadConfig reads ~/.lm/config.yaml — what the CLI reads.
func loadConfig() (*config.Config, error) {
	path, err := config.DefaultPath()
	if err != nil {
		return nil, err
	}
	return config.LoadFromFile(path)
}

// showContexts is :context — the configured contexts over the screen; Enter switches, Esc leaves.
func (a *App) showContexts() {
	cfg, err := loadConfig()
	if err != nil {
		a.showError("context: " + err.Error())
		return
	}
	choices := contextChoices(cfg, a.client.ContextName(), a.client.Server())
	if len(choices) == 0 {
		a.showError("context: no context configured — lm config set-context adds one")
		return
	}
	list := tview.NewList().ShowSecondaryText(false)
	for i, c := range choices {
		name := c.name
		list.AddItem(tview.Escape(c.label), "", 0, func() { a.useContext(name) })
		if name == a.client.ContextName() {
			list.SetCurrentItem(i)
		}
	}
	list.SetBorder(true).SetTitle(" :context — Enter switches this session, Esc leaves ")
	list.SetInputCapture(func(ev *tcell.EventKey) *tcell.EventKey {
		if ev.Key() == tcell.KeyEscape {
			a.restoreRoot()
			return nil
		}
		return ev
	})
	a.setRoot(list)
	a.tv.SetFocus(list)
}

// useContext switches lm ui to the named context and returns the app that now owns the screen, or
// nil when it stays where it was. Pages hold the client they were built with, so the switch builds
// a new app on the same terminal rather than re-pointing a dozen pages: every page then refreshes
// against the new hub by construction. The config is read again on Enter, so a context removed
// meanwhile is said rather than used. It does not touch the config's current-context — that is
// `lm config use-context`, the default for the next command; this is the session, like
// `lm ui --context`.
func (a *App) useContext(name string) *App {
	cfg, err := loadConfig()
	var ctx config.Context
	if err == nil {
		ctx, err = cfg.Resolve(name)
	}
	if err != nil {
		a.restoreRoot()
		a.showError("context: " + err.Error())
		return nil
	}
	page, _ := a.pages.GetFrontPage()
	if page == "" {
		page = "sources"
	}
	a.retire()
	b := newAppOn(a.tv, client.NewForContext(name, ctx))
	b.start(page)
	b.footer.SetToast(b.tv, "✓ context: "+tview.Escape(b.contextLabel)+" — this session only (lm config use-context sets the default)", 4*time.Second)
	return b
}

// retire is start's undoing, for an app :context replaces: its stream and ticker stop, its
// client's late auth-lost signal stays silent, and its idle connections to the old hub close.
func (a *App) retire() {
	a.retired.Store(true)
	if a.sseStop != nil { // nil only for an app never started (tests)
		a.sseStop()
	}
	a.client.CloseIdleConnections()
}

// frontIs is a page's isFront seam: true while page is the front page of an app not retired, so
// a late answer moves the focus only where the user is (#1160).
func (a *App) frontIs(page string) func() bool {
	return func() bool { n, _ := a.pages.GetFrontPage(); return n == page && !a.retired.Load() }
}
