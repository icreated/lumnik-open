package tui

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestNewApp_Constructs(t *testing.T) {
	c := client.New(config.Context{Server: "http://x"})
	app := newApp(c)
	if app == nil {
		t.Fatal("newApp returned nil")
	}
	if app.pages == nil {
		t.Error("pages container not initialised")
	}
	if app.header == nil || app.footer == nil {
		t.Error("header/footer not initialised")
	}
}

// Every palette page must be reachable by name — currentDescribable/currentYankable
// resolve through pageByName, so a page missing here silently loses its d/y keys
// (the :inbox regression Copilot caught on PR #114).
func TestPageByNameCoversAllPaletteEntries(t *testing.T) {
	c := client.New(config.Context{Server: "http://x"})
	app := newApp(c)
	for _, entry := range paletteEntries {
		if app.pageByName(entry) == nil {
			t.Errorf("pageByName(%q) = nil — palette page unreachable for d/y", entry)
		}
	}
}

// A nil page pointer must come back as an UNTYPED nil — a typed nil inside the
// interface would defeat the conservation test above and let d/y type-assert
// their way into a nil-receiver panic.
func TestPageByNameReturnsUntypedNilForNilPage(t *testing.T) {
	c := client.New(config.Context{Server: "http://x"})
	app := newApp(c)
	app.inboxPage = nil
	if got := app.pageByName("inbox"); got != nil {
		t.Errorf("pageByName over a nil page = %#v, want untyped nil", got)
	}
}

// The old mechanism spelling must keep working: :connectors resolves to the
// canonical :sources page (usage-canonical + alias, per the 2026-08-13 spec).
func TestPaletteResolvesConnectorsAliasToSources(t *testing.T) {
	c := client.New(config.Context{Server: "http://x"})
	app := newApp(c)
	if got := app.palette.Resolve("connectors"); got != "sources" {
		t.Errorf("Resolve(connectors) = %q, want sources", got)
	}
	if app.pageByName("sources") == nil {
		t.Error("pageByName(sources) = nil — the canonical page is unreachable")
	}
}

// The startup seed reads the user's default scope over the network, after the loop is
// pumping. It is only a seed: a scope the user picked meanwhile (:scopes, :scope) must win
// over a reply that lands later (Copilot, #394).
func TestSeedScope_NeverOverwritesAScopeTheUserAlreadyChose(t *testing.T) {
	c := client.New(config.Context{Server: "http://x"})
	app := newApp(c)

	app.seedScope("compta")
	if app.currentScope != "compta" {
		t.Fatalf("an unscoped session takes the seed, got %q", app.currentScope)
	}

	app.setScope("sales") // the user chose
	app.seedScope("compta")
	if app.currentScope != "sales" {
		t.Fatalf("a late seed must not overwrite the user's choice, got %q", app.currentScope)
	}
}
