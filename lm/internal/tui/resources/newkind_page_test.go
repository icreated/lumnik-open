package resources

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/tui/newkind"
)

var errFake = errors.New("no editor")

func TestNewKindPageListsAllKinds(t *testing.T) {
	p := &NewKind{kinds: newkind.All()}
	if len(p.kinds) != 13 {
		t.Fatalf("picker must list the 13 registry kinds, got %d", len(p.kinds))
	}
	if _, _, ok := (&NewKind{}).SelectedDescribe(); ok {
		t.Error("no selection on an uninitialized page")
	}
}

// The page is a two-state machine: picker ↔ issues. Abandoning from issues
// must return to the picker AND keep the buffer in a temp file (never lost).
// A failing $EDITOR must NOT eject the user from the issues view — the buffer
// would become unreachable (review round 3).
func TestNewKindEditorErrorKeepsIssuesMode(t *testing.T) {
	p := &NewKind{kinds: newkind.All(),
		suspend: func(f func()) { f() },
		editFn: func(hint, content string) (string, bool, error) {
			return "", false, errFake
		}}
	p.enterIssues("issues", "kind: Connector\n# my edits")
	p.reEdit()
	if p.mode != modeIssues {
		t.Error("editor failure must keep the issues mode (buffer stays reachable)")
	}
	if p.buffer == "" {
		t.Error("editor failure must keep the buffer")
	}
}

// EditExisting is the :processes 'e' entry: same loop, existing buffer.
func TestEditExistingResolvesTheKind(t *testing.T) {
	p := &NewKind{kinds: newkind.All(),
		suspend: func(f func()) { f() },
		editFn: func(hint, content string) (string, bool, error) {
			if content != "kind: Process\n# stored" {
				t.Errorf("EditExisting must open the STORED manifest, got %q", content)
			}
			return "", false, errFake // editor fails: loop must stop safely
		}}
	if ok := p.EditExisting("Process", "kind: Process\n# stored"); !ok {
		t.Fatal("Process is a registry kind — EditExisting must accept it")
	}
	if p.current.Kind != "Process" {
		t.Errorf("current kind = %q, want Process", p.current.Kind)
	}
	if ok := p.EditExisting("NoSuchKind", "x"); ok {
		t.Error("unknown kind must be refused")
	}
}

func TestNewKindModeTransitions(t *testing.T) {
	t.Setenv("TMPDIR", t.TempDir()) // abandon()'s temp file is cleaned with the test
	p := &NewKind{kinds: newkind.All()}
	if p.mode != modePick {
		t.Fatal("initial mode must be the picker")
	}
	p.enterIssues("some issues", "kind: Connector\n# my edits")
	if p.mode != modeIssues {
		t.Fatal("enterIssues must switch mode")
	}
	path := p.abandon()
	if p.mode != modePick {
		t.Error("abandon must return to the picker")
	}
	if path == "" || !strings.Contains(path, "lm-new-") {
		t.Errorf("abandon must persist the buffer to a temp file, got %q", path)
	}
}

// :new applies every apply-able kind through its own door (#1118), and v validates without applying:
// a connector through the hub's validation door, the others locally (they have no dry run) — and
// a then applies the held buffer.
func TestNewKind_everyKindItsDoor_andValidateOnly(t *testing.T) {
	var paths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.Method+" "+r.URL.Path)
		if strings.HasSuffix(r.URL.Path, "/validate") {
			_, _ = w.Write([]byte(`{"valid":true,"errors":[],"warnings":[]}`))
			return
		}
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()
	var toasts []string
	p := NewNewKind(client.New(config.Context{Server: srv.URL}), func(f func()) { f() }, nil,
		func(m string) { toasts = append(toasts, m) }, nil, nil)
	for _, c := range []struct{ kind, door string }{
		{"View", "POST /api/views/apply"},
		{"Export", "POST /api/exports/apply"},
		{"SearchIndex", "POST /api/search-indexes/apply"},
		{"App", "POST /api/apps/apply"},
	} {
		paths = nil
		var k newkind.KindTemplate
		for _, x := range p.kinds {
			if x.Kind == c.kind {
				k = x
			}
		}
		p.editFn = func(_, content string) (string, bool, error) { return content + "\n", true, nil }
		p.edit(k, k.Template)
		if len(paths) != 1 || paths[0] != c.door {
			t.Errorf("%s applied through %v, want %s", c.kind, paths, c.door)
		}
	}

	// v: validate only — nothing applied, the buffer held; a applies it.
	paths = nil
	p.editFn = func(_, content string) (string, bool, error) { return content + "\n", true, nil }
	var csv newkind.KindTemplate // a connector: the one kind the hub can validate without applying
	for _, x := range p.kinds {
		if x.Kind == "csv-file" {
			csv = x
		}
	}
	p.validateOnly(csv)
	if len(paths) != 1 || !strings.HasSuffix(paths[0], "/validate") || p.mode != modeIssues || p.buffer == "" {
		t.Fatalf("validate-only: paths %v mode %v", paths, p.mode)
	}
	if !strings.Contains(p.Hints(), "a: apply") {
		t.Errorf("a held buffer's footer %q does not offer a", p.Hints())
	}
	if !strings.Contains(p.issues.GetText(false), "not applied") {
		t.Errorf("issues view = %q", p.issues.GetText(false))
	}
	p.handleIssuesKey(tcell.NewEventKey(tcell.KeyRune, 'a', tcell.ModNone))
	if len(paths) != 3 || paths[2] != "POST /api/platform/connectors/apply" || p.mode != modePick {
		t.Errorf("a did not apply the held buffer: %v", paths)
	}
}

// A View applied with warnings says so in the toast, like a connector or an app (#1129 review).
func TestNewKind_viewApplyWarningsReachTheToast(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"name":"clients-desk","scope":"clients","warnings":[{"path":"spec.links[0].target","message":"not applied yet"}]}`))
	}))
	defer srv.Close()
	var toasts []string
	p := NewNewKind(client.New(config.Context{Server: srv.URL}), func(f func()) { f() }, nil,
		func(m string) { toasts = append(toasts, m) }, nil, nil)
	p.editFn = func(_, content string) (string, bool, error) { return content + "\n", true, nil }
	for _, k := range p.kinds {
		if k.Kind == "View" {
			p.edit(k, k.Template)
		}
	}
	if len(toasts) != 1 || !strings.Contains(toasts[0], "spec.links[0].target") {
		t.Errorf("toasts = %q, want the warning's path", toasts)
	}
}

// d on a connector kind shows its type's config schema — `lm describe connector-type` (#1138). The
// type is the template's own connector_type: graphql and soap are request shapes of rest-generic,
// not types. A non-connector kind asks the hub nothing; the hub's words are escaped.
func TestNewKindDescribe_showsTheConnectorTypeSchema(t *testing.T) {
	var asked []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = append(asked, r.URL.Path)
		_, _ = w.Write([]byte(`{"fields":[{"name":"base_url","type":"STRING","importance":"HIGH","required":true,
			"doc":"the API [root]"},{"name":"auth","type":"MAP","importance":"MEDIUM","required":false,
			"nested":{"fields":[{"name":"kind","type":"STRING","importance":"HIGH","required":true}]}}]}`))
	}))
	defer srv.Close()
	p := NewNewKind(client.New(config.Context{Server: srv.URL}), nil, nil, nil, nil, nil)
	p.Refresh()
	describe := func(kind string) string {
		for i, k := range p.kinds {
			if k.Kind == kind {
				p.table.Select(i+1, 0)
			}
		}
		_, body, ok := p.SelectedDescribe()
		if !ok {
			t.Fatalf("describe !ok on %s", kind)
		}
		return body
	}

	body := describe("graphql")
	if len(asked) != 1 || asked[0] != "/api/platform/validator/describe/rest-generic" {
		t.Fatalf("asked %q, want the rest-generic type's schema once", asked)
	}
	for _, want := range []string{"connector type: rest-generic", "base_url", "REQUIRED", "the API [root[]", "  kind"} {
		if !strings.Contains(body, want) {
			t.Errorf("describe body missing %q:\n%s", want, body)
		}
	}
	describe("rest-generic") // same type: served from what was fetched
	describe("Entity")       // not a connector: no schema to ask for
	if len(asked) != 1 {
		t.Errorf("asked %q, want one fetch per connector type and none for an Entity", asked)
	}
}
