package tui

import (
	"context"
	"os"
	"strings"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/brand"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/login"
	"github.com/icreated/lumnik-open/lm/internal/tui/resources"
	"github.com/rivo/tview"
)

// showAuthLost raises the "session expired" modal. L re-logs in via the device
// flow (through SuspendEditor); Esc dismisses (it reappears on the next failing
// request).
func (a *App) showAuthLost() {
	// The body is width-dependent (the wordmark is dropped in a narrow terminal), and tview only
	// knows the real width at draw time — so it is rendered from the draw callback, and only when
	// the width actually changed, to avoid rewriting the text on every frame.
	body := tview.NewTextView().SetDynamicColors(true)
	lastWidth := -1
	body.SetDrawFunc(func(_ tcell.Screen, x, y, w, h int) (int, int, int, int) {
		if w != lastWidth {
			lastWidth = w
			body.SetText(authLostBody(w))
		}
		return x, y, w, h
	})
	body.SetBorder(true).SetTitle(" auth ")
	body.SetInputCapture(func(ev *tcell.EventKey) *tcell.EventKey {
		switch {
		case ev.Rune() == 'L' || ev.Rune() == 'l':
			a.setRoot(a.rootFlex)
			a.reLogin()
			return nil
		case ev.Key() == tcell.KeyEscape:
			a.setRoot(a.rootFlex)
			a.client.RearmAuthLost() // so the next failing request re-raises the modal
			return nil
		}
		return ev
	})
	a.setRoot(body)
	a.tv.SetFocus(body)
}

// reLogin runs the device-flow login through the suspend chokepoint, reloads the
// fresh context into the client, and refreshes the current page.
func (a *App) reLogin() {
	var loginErr error
	resources.SuspendEditor(a.tv, a.showError, func() {
		path, err := config.DefaultPath()
		if err != nil {
			loginErr = err
			return
		}
		cfg, err := config.LoadFromFile(path)
		if err != nil {
			loginErr = err
			return
		}
		name := a.client.ContextName()
		if name == "" {
			name = cfg.CurrentContext
		}
		loginErr = login.Run(context.Background(), login.HTTPClient(cfg.Contexts[name]), cfg, path, name, os.Stdout)
	})
	if loginErr != nil {
		// Login failed (cancelled, network, timeout) — re-raise the modal so the
		// human can retry, rather than dumping them on a page with a dead session.
		a.showError("login failed: " + loginErr.Error())
		a.showAuthLost()
		return
	}
	a.client.ReloadContext()
	// Defer the page refresh OFF this input-handler goroutine: SuspendEditor has
	// queued the fresh screen, but the run loop only engages it once this handler
	// returns. Doing switchTo's blocking network Refresh here would hold the loop
	// with NO active screen — the terminal stays frozen on the login output until
	// the request returns (Marc's "reste suspendu" hang). QueueUpdate runs it on
	// the loop AFTER the screen is live.
	if a.switchTo != nil {
		cur, _ := a.pages.GetFrontPage()
		go resources.QueueUpdate(a.tv, func() { a.switchTo(cur) })
	}
}

// authLostBody is what the session-expired modal says. It wears the same wordmark as `lm login`,
// because it is the door back to that same flow — one definition, two surfaces that cannot drift.
// Below the wordmark's width the block is dropped rather than folded: the keys are the point of
// this modal, and a wrapped logo would push them off screen.
func authLostBody(width int) string {
	var b strings.Builder
	if width == 0 || width >= brand.Width {
		b.WriteString("[" + brand.GoldHex + "]")
		b.WriteString(brand.Wordmark)
		b.WriteString("[white]\n")
		b.WriteString(brand.Tagline)
		b.WriteString("\n\n")
	} else {
		b.WriteString("[" + brand.GoldHex + "::b]" + brand.Name + "[white::-]\n\n")
	}
	b.WriteString("[yellow]Session expired[white]\n\n")
	b.WriteString("Your login is no longer valid (token expired or rotated).\n\n")
	b.WriteString("  [green]L[white]  log in again      [green]Esc[white]  dismiss")
	return b.String()
}
