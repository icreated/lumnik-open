package tui

import (
	"github.com/icreated/lumnik-open/lm/internal/tui/newkind"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// The in-app help is the TUI's only documentation: every palette page
// must have a help row, or the page is undiscoverable.
func TestHelpCoversAllPaletteEntries(t *testing.T) {
	for _, entry := range paletteEntries {
		if !strings.Contains(helpBody, ":"+entry) {
			t.Errorf("help body missing palette page :%s", entry)
		}
	}
}

func TestHelpTeachesViewGenerate(t *testing.T) {
	if !strings.Contains(helpBody, "generate") {
		t.Error("the ? help must mention the :view generate gesture")
	}
}

// The a gesture completes loop 2 (generate → render → apply into the saved-view store);
// the page footer teaches it, the ? help must too.
func TestHelpTeachesViewApply(t *testing.T) {
	if !strings.Contains(helpBody, "apply to hub") {
		t.Error("the ? help must mention the :view a (apply to hub) gesture")
	}
}

// Two registers, one nav: the help tells the product story in two chapters.
func TestHelpHasTwoChapters(t *testing.T) {
	for _, chapter := range []string{"Your data", "Workshop"} {
		if !strings.Contains(helpBody, chapter) {
			t.Errorf("help body missing chapter %q", chapter)
		}
	}
}

// The alias must be discoverable, or renaming breaks trained fingers silently.
func TestHelpMentionsConnectorsAlias(t *testing.T) {
	if !strings.Contains(helpBody, "alias :connectors") {
		t.Error("help body must mention the :connectors alias on the :sources row")
	}
}

// pageBlock returns the slice of helpBody covering :page's section (up to the
// next top-level ":page" line), the same split TestHelpCoversMutatingKeys has
// always used. Shared here so every help_test.go check reads the same section.
func pageBlock(t *testing.T, page string) string {
	t.Helper()
	blocks := strings.Split(helpBody, "\n  :")
	for _, b := range blocks {
		if strings.HasPrefix(b, page+" ") {
			return b
		}
	}
	t.Fatalf("no :%s section found in help body (page name mismatch)", page)
	return ""
}

// Every mutating key in mutatingKeyEntries must have its own bullet row under
// its :page — the footer teaches it (Hints()), the ? help must too, or a user
// who presses ? to find out how to do it is told, wrongly, that it can't be done.
func TestHelpCoversMutatingKeys(t *testing.T) {
	bullet := strings.Repeat(" ", 21)
	for _, e := range mutatingKeyEntries {
		block := pageBlock(t, e.page)
		if !strings.Contains(block, "\n"+bullet+e.key+" ") {
			t.Errorf(":%s section has no bullet row for mutating key %q", e.page, e.key)
		}
	}
}

// --- Re-anchoring on production code -----------------------------------
//
// mutatingKeyEntries itself is necessarily hand-curated: "mutating" (writes
// state) vs. "read-only nav" is a product judgment that isn't recoverable
// from syntax alone — see the investigation notes in this package's history
// for why deriving it from the Hints() footer strings was rejected:
//
//   - Hints() uses at least three different bullet separators across pages
//     ("  ", "   ", " · "), plus combo keys ("g/^G") and prose that embeds a
//     real key inside a parenthetical aside (viewpage's render-state hint).
//   - Hints() can be TERSER than what's real: :sources' Hints() mentions only
//     "^R", but handleKey also accepts the modifier-free alias "R" (and
//     helpBody correctly documents both) — so Hints() text is not a reliable
//     upper bound on the real key set either.
//   - Key dispatch isn't even uniformly named "handleKey": Ask uses
//     tableKeys, ViewPage splits across handleListKey/handleGenIssuesKey,
//     NewKind across handlePickKey/handleIssuesKey. A parser would need to
//     find every such function per page, not just one.
//   - Some real mutations are one or more hops away via an injected callback
//     (:sources' "t" calls c.onScopeEdit, wired in app.go to
//     client.ConnectorSetTags) — invisible to any scan confined to the
//     resources package.
//
// So the list stays curated. What CAN be checked mechanically, without that
// classification, is existence: does the literal rune or tcell key constant
// this entry/bullet names actually appear in that page's own source file?
// That anchors both the curated list and helpBody's prose on the real
// dispatch code (stronger than Hints(), which the "R"/"^R" case above shows
// can itself be stale-by-omission) and fails in both directions:
//   - TestMutatingKeyEntriesAreReal: curated list → source (catches a key
//     renamed/removed in code while the list and helpBody lag behind).
//   - TestHelpBulletsAreReal: every bullet helpBody prints for a :page →
//     source (catches a stale/typo'd row for a key that isn't dispatched
//     there — or anywhere — any more).
//
// What this still cannot catch, by construction: a NEW mutating key added to
// a page's dispatch code and handleKey-equivalent function while the author
// forgets BOTH mutatingKeyEntries and the helpBody bullet. Neither check
// above scans for keys the two documentation artifacts don't already
// mention — closing that would mean the full Hints()/dispatch parser this
// comment just argued against. (This is not hypothetical: sources' "t" —
// scope-tag editing, a real client.ConnectorSetTags call — was exactly such
// a gap, found during this audit and fixed alongside these tests: it is now
// in mutatingKeyEntries and in helpBody.)

// keyGoLiteral maps a display key name (as used in mutatingKeyEntries and in
// helpBody's bullet rows) to the Go source token its page's key-dispatch code
// uses to recognize it. Single printable keys are matched as a quoted rune
// literal; the rest are tcell's named key constants. This table only grows
// when a genuinely new non-rune key (e.g. a new Ctrl-chord) is documented —
// tcell's constant names don't change.
var keyGoLiteral = map[string]string{
	"Del":   "KeyDelete",
	"Enter": "KeyEnter",
	"Esc":   "KeyEscape",
	"^P":    "KeyCtrlP",
	"^R":    "KeyCtrlR",
	"^L":    "KeyCtrlL",
	"^D":    "KeyCtrlD",
	"^G":    "KeyCtrlG",
}

// globalKeys are dispatched once, app-wide (App.bindGlobalKeys), not by any
// one page's own key-handling code — they are exactly the "Global keys"
// helpBody documents once at the top. A :page section is allowed to repeat
// one of these with page-specific detail (e.g. :outputs' "d / y  the failure
// in full") without that key needing to appear in the page's own source file.
var globalKeys = map[string]bool{
	"d": true, "y": true, "q": true, "?": true, "r": true, ":": true, "Esc": true,
}

// resourceFile maps a :page name to the file in internal/tui/resources/ whose
// source dispatches its keys.
var resourceFile = map[string]string{
	"sources": "sources.go", "dlq": "dlq.go", "outputs": "outputs.go",
	"inbox": "inbox.go", "view": "viewpage.go", "hooks": "hooks.go",
	"runs": "runs.go", "conflicts": "conflicts.go", "observations": "observations.go",
	"scopes": "scopes.go", "ask": "ask.go", "new": "newkind_page.go",
	"ambiguous": "ambiguous.go",
	"endpoints": "endpoints.go", "processes": "manifests.go", "secrets": "secrets.go", "entities": "entities.go", "tables": "tables.go", "apps": "manifests.go", "workflows": "manifests.go", "views": "manifests.go",
	"exports": "exports.go", "search": "search.go",
	"migrations": "migrations.go", "modules": "modules.go", "rag": "rag.go",
	"integrations": "integrations.go", "notify": "notify.go",
}

var resourceFileCache = map[string]string{}

// pageSource returns the full text of the resource file that dispatches
// page's keys — the whole file, not a single named function, since key
// dispatch isn't uniformly named across pages (see the comment above).
func pageSource(t *testing.T, page string) string {
	t.Helper()
	if src, ok := resourceFileCache[page]; ok {
		return src
	}
	file := resourceFile[page]
	if file == "" {
		t.Fatalf("no resource file registered for :%s in resourceFile", page)
	}
	b, err := os.ReadFile(filepath.Join("resources", file))
	if err != nil {
		t.Fatalf("read resources/%s: %v", file, err)
	}
	resourceFileCache[page] = string(b)
	return string(b)
}

// keyIsDispatched reports whether page's own source file recognizes key —
// the actual dispatch code, not the (sometimes terser) Hints() footer text.
func keyIsDispatched(t *testing.T, page, key string) bool {
	t.Helper()
	src := pageSource(t, page)
	if tok, ok := keyGoLiteral[key]; ok {
		return strings.Contains(src, tok)
	}
	if len([]rune(key)) == 1 {
		return strings.Contains(src, "'"+key+"'")
	}
	t.Fatalf("key %q (:%s) has no rune or keyGoLiteral mapping — add one", key, page)
	return false
}

// mutatingKeyEntries must name keys the page's own dispatch code still
// recognizes — a key renamed or removed there, with the list and helpBody
// left stale, is caught here instead of drifting silently.
func TestMutatingKeyEntriesAreReal(t *testing.T) {
	for _, e := range mutatingKeyEntries {
		if !keyIsDispatched(t, e.page, e.key) {
			t.Errorf(":%s no longer dispatches %q — mutatingKeyEntries is stale", e.page, e.key)
		}
	}
}

// bulletKeyRe finds a bullet row's leading key token(s): 21 spaces (the
// bullet indent every :page section in helpBody uses), then the shortest
// run of non-space-padded text before the column gap that separates key
// from description. Matches "e   edit YAML" (key "e") and "d / y  the
// failure..." (key text "d / y", split into "d" and "y" below).
var bulletKeyRe = regexp.MustCompile(`(?m)^ {21}(\S(?:.*?\S)?)  +\S`)

// Reverse of TestHelpCoversMutatingKeys: every bullet row helpBody prints
// under a :page section must name a key that page (or the global key set)
// genuinely dispatches — catching a stale or mistyped row left behind after
// a key was renamed or removed, which nothing before this checked against
// production code.
func TestHelpBulletsAreReal(t *testing.T) {
	for _, page := range paletteEntries {
		block := pageBlock(t, page)
		for _, m := range bulletKeyRe.FindAllStringSubmatch(block, -1) {
			for _, key := range strings.Split(m[1], "/") {
				key = strings.TrimSpace(key)
				if key == "" || globalKeys[key] {
					continue
				}
				if !keyIsDispatched(t, page, key) {
					t.Errorf("help body :%s section documents key %q, but :%s no longer dispatches it",
						page, key, page)
				}
			}
		}
	}
}

// Every :new kind lands, after its apply, on a page that exists — and the one that shows what was
// applied: a View on :views, the hub's saved views, not :view, the file renderer (#1129 review).
func TestNewKindObservePagesExist(t *testing.T) {
	pages := map[string]bool{}
	for _, p := range paletteEntries {
		pages[p] = true
	}
	for _, k := range newkind.All() {
		if !pages[k.Observe] {
			t.Errorf("%s observes %q, which is not a page", k.Kind, k.Observe)
		}
		if k.Kind == "View" && k.Observe != "views" {
			t.Errorf("an applied View lands on %q, want views (the saved views)", k.Observe)
		}
	}
}
