package resources

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/rivo/tview"
)

func TestSuggestedFileName(t *testing.T) {
	cases := []struct{ name, yaml, want string }{
		{"plain", "# lm view -f clients-view.yaml --filter x\nkind: View\n", "clients-view.yaml"},
		{"newline-terminated", "# lm view -f v.yaml\nkind: View\n", "v.yaml"},
		{"absent", "kind: View\n", "view-generated.yaml"},
		{"posix-quoted", "# lm view -f 'my view.yaml' --x\nkind: View\n", "my view.yaml"},
		{"embedded-quote", "# lm view -f 'o'\\''brien.yaml' --x\nkind: View\n", "o'brien.yaml"},
		{"unterminated-quote", "# lm view -f 'broken.yaml\nkind: View\n", "view-generated.yaml"},
		{"path-stripped", "# lm view -f ../../etc/x.yaml\nkind: View\n", "x.yaml"},
		{"not-yaml-ext", "# lm view -f weird.txt\nkind: View\n", "view-generated.yaml"},
	}
	for _, c := range cases {
		if got := suggestedFileName(c.yaml); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
}

func TestUniquify(t *testing.T) {
	dir := t.TempDir()
	free := filepath.Join(dir, "a.yaml")
	if got := uniquify(free); got != free {
		t.Errorf("free path must come back unchanged, got %q", got)
	}
	if err := os.WriteFile(free, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := uniquify(free); got != filepath.Join(dir, "a-2.yaml") {
		t.Errorf("first collision must suffix -2, got %q", got)
	}
	if err := os.WriteFile(filepath.Join(dir, "a-2.yaml"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := uniquify(free); got != filepath.Join(dir, "a-3.yaml") {
		t.Errorf("second collision must suffix -3, got %q", got)
	}
}

// A Stat error that is NOT ErrNotExist (here ENOTDIR: a file used as a parent
// directory) must not spin the probe loop forever — the candidate comes back
// and the exclusive write is the real overwrite guard.
func TestUniquifyStatErrorDoesNotSpin(t *testing.T) {
	dir := t.TempDir()
	notADir := filepath.Join(dir, "file.yaml")
	if err := os.WriteFile(notADir, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(notADir, "a.yaml") // Stat → ENOTDIR on every candidate
	if got := uniquify(p); got != p {
		t.Errorf("un-probeable path must come back unchanged, got %q", got)
	}
}

// writeNew is the overwrite invariant itself: O_EXCL means even a raced or
// mis-probed name can never silently clobber an existing file.
func TestWriteNewRefusesExisting(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "a.yaml")
	if err := os.WriteFile(p, []byte("precious"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := writeNew(p, []byte("clobber")); err == nil {
		t.Fatal("writing over an existing file must error")
	}
	data, _ := os.ReadFile(p)
	if string(data) != "precious" {
		t.Errorf("existing content must survive, got %q", data)
	}
	fresh := filepath.Join(dir, "b.yaml")
	if err := writeNew(fresh, []byte("kind: View\n")); err != nil {
		t.Fatalf("fresh write must succeed: %v", err)
	}
	data, _ = os.ReadFile(fresh)
	if string(data) != "kind: View\n" {
		t.Errorf("fresh content = %q", data)
	}
}

func TestPersistBuffer(t *testing.T) {
	path, err := persistBuffer("lm-view-*.yaml", "kind: View\n")
	if err != nil {
		t.Fatal(err)
	}
	defer os.Remove(path)
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "kind: View\n" {
		t.Errorf("persisted %q", data)
	}
	if filepath.Ext(path) != ".yaml" {
		t.Errorf("pattern must shape the name, got %q", path)
	}
}

// newTestViewPage builds a page with inline seams and no real client calls.
func newTestViewPage(scopeTag string) (*ViewPage, *[]string, *[]string) {
	var errs, toasts []string
	v := &ViewPage{
		pages:     tview.NewPages(),
		openFlex:  tview.NewFlex(),
		pathInput: tview.NewInputField(),
		recentsUI: tview.NewTable(),
		list:      tview.NewTable().SetSelectable(true, false),
		genIssues: tview.NewTextView(),
		scope:     func() string { return scopeTag },
		spawn:     func(f func()) { f() },
		enqueue:   func(f func()) { f() },
		suspend:   func(f func()) { f() },
		showError: func(m string) { errs = append(errs, m) },
		toast:     func(m string) { toasts = append(toasts, m) },
	}
	v.pages.AddPage("open", tview.NewBox(), true, true)
	v.pages.AddPage("list", v.list, true, false)
	v.pages.AddPage("genissues", v.genIssues, true, false)
	return v, &errs, &toasts
}

func TestGenerateNoScopeNotice(t *testing.T) {
	v, errs, _ := newTestViewPage("")
	v.promptGenerate()
	if len(*errs) != 1 || !strings.Contains((*errs)[0], ":scope") {
		t.Fatalf("no session scope must yield the crucial-ordering notice, got %v", *errs)
	}
	if v.pages.HasPage("generate") {
		t.Error("no prompt may open without a scope")
	}
}

func TestGenerateSingleFlight(t *testing.T) {
	v, _, _ := newTestViewPage("clients")
	v.generating = true
	v.promptGenerate()
	if v.pages.HasPage("generate") {
		t.Error("a second g during an in-flight generation must be ignored")
	}
}

func TestGeneratePromptPrefilledAfterFailure(t *testing.T) {
	v, _, _ := newTestViewPage("clients")
	v.genPhrase = "combien de clients Platinum"
	v.promptGenerate()
	if !v.pages.HasPage("generate") {
		t.Fatal("prompt must open")
	}
	if got := v.genInput.GetText(); got != "combien de clients Platinum" {
		t.Errorf("prompt must keep the failed sentence, got %q", got)
	}
}

// fakeHub serves /api/views/generate and /api/views/render with canned bodies.
func fakeHub(t *testing.T, generateStatus int, generateBody string,
	renderStatus int, renderBody string) *client.Client {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/views/generate":
			w.WriteHeader(generateStatus)
			_, _ = w.Write([]byte(generateBody))
		case "/api/views/render":
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(renderStatus)
			_, _ = w.Write([]byte(renderBody))
		default:
			t.Errorf("unexpected call: %s", r.URL.Path)
			w.WriteHeader(500)
		}
	}))
	t.Cleanup(srv.Close)
	return client.New(config.Context{Server: srv.URL, Token: "static"})
}

const genYAML = "# lm view -f clients-view.yaml --filter city=Lyon\nkind: View\nscope: clients\n"
const renderJSON = `{"title":"Clients","columns":[{"name":"code","label":"Code"}],"rows":[{"code":"C-100"}]}`

// renderStep is one scripted /api/views/render response.
type renderStep struct {
	status int
	body   string
}

// scriptedRenderHub is fakeHub's sibling for the from-issues recovery path: it
// serves generate with a single fixed response, but render with a SEQUENCE of
// responses consumed in call order (refuse, then succeed on retry). Kept
// separate so fakeHub's existing callers are untouched.
func scriptedRenderHub(t *testing.T, generateBody string, renders ...renderStep) *client.Client {
	t.Helper()
	i := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/views/generate":
			w.WriteHeader(200)
			_, _ = w.Write([]byte(generateBody))
		case "/api/views/render":
			idx := i
			if idx >= len(renders) {
				idx = len(renders) - 1
			}
			i++
			step := renders[idx]
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(step.status)
			_, _ = w.Write([]byte(step.body))
		default:
			t.Errorf("unexpected call: %s", r.URL.Path)
			w.WriteHeader(500)
		}
	}))
	t.Cleanup(srv.Close)
	return client.New(config.Context{Server: srv.URL, Token: "static"})
}

func TestGenerateHappyPathWritesFileAtRender(t *testing.T) {
	t.Chdir(t.TempDir())
	v, _, toasts := newTestViewPage("clients")
	v.client = fakeHub(t, 200, genYAML, 200, renderJSON)
	edited := false
	v.editFn = func(hint, content string) (string, bool, error) {
		edited = true
		if content != genYAML {
			t.Errorf("editor must open the generated YAML, got %q", content)
		}
		return content, false, nil // closed unmodified — still renders (spec)
	}
	v.generate("clients", "la liste de mes clients")
	if !edited {
		t.Fatal("the editor turn must run")
	}
	if _, err := os.Stat("clients-view.yaml"); err != nil {
		t.Fatalf("file must be born at render: %v", err)
	}
	if v.state != viewList {
		t.Errorf("state = %v, want viewList", v.state)
	}
	if len(v.recents) == 0 || v.recents[0] != "clients-view.yaml" {
		t.Errorf("recents must lead with the new file: %v", v.recents)
	}
	joined := strings.Join(*toasts, " | ")
	if !strings.Contains(joined, "clients-view.yaml") {
		t.Errorf("toast must carry the path: %v", *toasts)
	}
	if v.genPhrase != "" {
		t.Error("a successful generation must clear the kept phrase")
	}
}

func TestGenerateNameCollisionUniquifies(t *testing.T) {
	t.Chdir(t.TempDir())
	if err := os.WriteFile("clients-view.yaml", []byte("existing"), 0o644); err != nil {
		t.Fatal(err)
	}
	v, _, _ := newTestViewPage("clients")
	v.client = fakeHub(t, 200, genYAML, 200, renderJSON)
	v.editFn = func(hint, content string) (string, bool, error) { return content, false, nil }
	v.generate("clients", "encore")
	if _, err := os.Stat("clients-view-2.yaml"); err != nil {
		t.Fatalf("collision must suffix -2: %v", err)
	}
	data, _ := os.ReadFile("clients-view.yaml")
	if string(data) != "existing" {
		t.Error("the existing file must stay untouched")
	}
}

func TestGenerateAbstentionKeepsPhraseAndReopensPrompt(t *testing.T) {
	v, errs, _ := newTestViewPage("clients")
	v.client = fakeHub(t, 400, `{"error":"generation abstained: PLATINUM out of domain"}`, 200, renderJSON)
	v.editFn = func(hint, content string) (string, bool, error) {
		t.Fatal("no editor on abstention")
		return "", false, nil
	}
	v.generate("clients", "combien de clients Platinum")
	if len(*errs) == 0 || !strings.Contains((*errs)[0], "abstained") {
		t.Fatalf("the honest refusal must surface verbatim, got %v", *errs)
	}
	if v.genPhrase != "combien de clients Platinum" {
		t.Errorf("the sentence must be kept, got %q", v.genPhrase)
	}
	if !v.pages.HasPage("generate") {
		t.Error("the prompt must re-open pre-filled")
	}
	if v.generating {
		t.Error("the single-flight guard must be released")
	}
}

func TestGenerateRenderRefusalEntersIssuesLoop(t *testing.T) {
	t.Chdir(t.TempDir())
	v, _, _ := newTestViewPage("clients")
	v.client = fakeHub(t, 200, genYAML, 400, `{"error":"column 'segment' is not in the card"}`)
	v.editFn = func(hint, content string) (string, bool, error) { return content + "# edited\n", true, nil }
	v.generate("clients", "x")
	if v.state != viewGenIssues {
		t.Fatalf("state = %v, want viewGenIssues", v.state)
	}
	if !strings.Contains(v.genBuffer, "# edited") {
		t.Error("the issues state must hold the HUMAN's buffer")
	}
	if _, err := os.Stat("clients-view.yaml"); err == nil {
		t.Error("no file may be born from a refused render")
	}
	// e re-edits the SAME buffer
	var reopened string
	v.editFn = func(hint, content string) (string, bool, error) {
		reopened = content
		return "", false, errFake // stop the loop here
	}
	v.handleGenIssuesKey(tcell.NewEventKey(tcell.KeyRune, 'e', tcell.ModNone))
	if !strings.Contains(reopened, "# edited") {
		t.Errorf("e must re-open the same buffer, got %q", reopened)
	}
	if v.state != viewGenIssues {
		t.Error("an editor failure must keep the issues state")
	}
}

func TestGenerateAbandonPersistsBuffer(t *testing.T) {
	v, _, toasts := newTestViewPage("clients")
	v.enterGenIssues("refused", "kind: View\n# precious\n")
	v.handleGenIssuesKey(tcell.NewEventKey(tcell.KeyEscape, 0, tcell.ModNone))
	joined := strings.Join(*toasts, " | ")
	if !strings.Contains(joined, "abandoned — buffer kept at ") {
		t.Fatalf("abandon must toast the temp path, got %v", *toasts)
	}
	path := strings.TrimSpace(joined[strings.LastIndex(joined, " at ")+4:])
	defer os.Remove(path)
	data, err := os.ReadFile(path)
	if err != nil || !strings.Contains(string(data), "# precious") {
		t.Errorf("buffer must be in the temp file: %v %q", err, data)
	}
	if v.state == viewGenIssues {
		t.Error("abandon must leave the issues state")
	}
}

// TestGenerateDeferredAcrossEditorSuspension covers finding 1: enqueue
// (QueueUpdate) silently DROPS updates while an editor session owns the
// terminal on another page. Without DeferIfSuspended the callback vanishes
// and v.generating wedges shut for the rest of the session.
func TestGenerateDeferredAcrossEditorSuspension(t *testing.T) {
	t.Chdir(t.TempDir())
	EditorSuspended.Store(true)
	t.Cleanup(func() { EditorSuspended.Store(false) })

	v, _, _ := newTestViewPage("clients")
	v.client = fakeHub(t, 200, genYAML, 200, renderJSON)
	edited := false
	v.editFn = func(hint, content string) (string, bool, error) {
		edited = true
		return content, false, nil
	}
	v.generate("clients", "la liste de mes clients")

	if edited {
		t.Fatal("the editor must not open while another page's editor session is live")
	}
	if !v.generating {
		t.Error("generating must stay true — the outcome is deferred, not dropped")
	}

	EditorSuspended.Store(false)
	drainDeferred()

	if !edited {
		t.Fatal("the deferred delivery must run once the editor session ends")
	}
	if v.generating {
		t.Error("generating must clear once the deferred delivery lands")
	}
}

// TestGenerateAbstentionHiddenSuppressesPromptButShowsError covers finding 2:
// the callback must not re-open the prompt (and steal focus) on a HIDDEN
// :view page — but the honest refusal still surfaces globally.
func TestGenerateAbstentionHiddenSuppressesPromptButShowsError(t *testing.T) {
	v, errs, _ := newTestViewPage("clients")
	v.isFront = func() bool { return false }
	v.client = fakeHub(t, 400, `{"error":"generation abstained: PLATINUM out of domain"}`, 200, renderJSON)
	v.editFn = func(hint, content string) (string, bool, error) {
		t.Fatal("no editor on abstention")
		return "", false, nil
	}
	v.generate("clients", "combien de clients Platinum")
	if len(*errs) == 0 || !strings.Contains((*errs)[0], "abstained") {
		t.Fatalf("the honest refusal must still surface globally, got %v", *errs)
	}
	if v.genPhrase != "combien de clients Platinum" {
		t.Errorf("the sentence must be kept, got %q", v.genPhrase)
	}
	if v.pages.HasPage("generate") {
		t.Error("the prompt must NOT re-open while :view is hidden — it would steal focus off-screen")
	}
	if v.generating {
		t.Error("the single-flight guard must be released")
	}
}

// TestGenerateSuccessHiddenPersistsInsteadOfOpeningEditor covers finding 2:
// a success landing while :view is hidden must never pop the editor over
// whatever page the user is on — the YAML is held as a temp file instead.
func TestGenerateSuccessHiddenPersistsInsteadOfOpeningEditor(t *testing.T) {
	v, _, toasts := newTestViewPage("clients")
	v.isFront = func() bool { return false }
	v.client = fakeHub(t, 200, genYAML, 200, renderJSON)
	edited := false
	v.editFn = func(hint, content string) (string, bool, error) {
		edited = true
		return content, false, nil
	}
	v.generate("clients", "la liste de mes clients")

	if edited {
		t.Error("the editor must never pop over another page")
	}
	if v.generating {
		t.Error("generating must clear")
	}
	if v.genPhrase != "" {
		t.Error("a successful generation must clear the kept phrase even when hidden")
	}
	joined := strings.Join(*toasts, " | ")
	if !strings.Contains(joined, "kept at") {
		t.Fatalf("toast must announce the held file, got %v", *toasts)
	}
	path := strings.TrimSpace(joined[strings.LastIndex(joined, "kept at")+len("kept at"):])
	defer os.Remove(path)
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("the YAML must be persisted to a temp file: %v", err)
	}
	if string(data) != genYAML {
		t.Errorf("persisted content = %q, want the generated YAML", data)
	}
	if len(v.recents) == 0 || v.recents[0] != path {
		t.Errorf("the held file must be discoverable via :view recents, got %v", v.recents)
	}
}

// TestConstructorWiresGenIssuesInputCapture covers finding 4: the tests build
// ViewPage by hand elsewhere, so deleting the SetInputCapture wiring line in
// NewViewPage would pass the rest of the suite while stranding a live user
// with dead keys in the issues state. This constructs through NewViewPage
// itself and drives the wired capture.
func TestConstructorWiresGenIssuesInputCapture(t *testing.T) {
	app := tview.NewApplication() // never Run — only used to satisfy the constructor
	v := NewViewPage(app, nil, func(f func()) { f() }, func() string { return "clients" }, nil,
		func(string) {}, func(string) {}, func(string) {})
	v.spawn = func(f func()) { f() }
	v.enqueue = func(f func()) { f() }
	v.suspend = func(f func()) { f() }

	var reopened string
	v.editFn = func(hint, content string) (string, bool, error) {
		reopened = content
		return "", false, errFake // stop the loop here — the point is the re-open, not the render
	}
	v.enterGenIssues("refused", "kind: View\n# held\n")

	capture := v.genIssues.GetInputCapture()
	if capture == nil {
		t.Fatal("the constructor must wire genIssues.SetInputCapture — dead keys otherwise")
	}
	capture(tcell.NewEventKey(tcell.KeyRune, 'e', tcell.ModNone))
	if !strings.Contains(reopened, "# held") {
		t.Errorf("the wired capture must re-open the held buffer, got %q", reopened)
	}
}

// TestGenerateIssuesRecoveryRendersOnRetry covers finding 5: after a refused
// render enters the issues loop, e re-edits and a SUCCEEDING retry must
// still birth the file and clear the issues state — the recovery half of the
// loop, not just the refusal half.
func TestGenerateIssuesRecoveryRendersOnRetry(t *testing.T) {
	t.Chdir(t.TempDir())
	v, _, _ := newTestViewPage("clients")
	v.client = scriptedRenderHub(t, genYAML,
		renderStep{400, `{"error":"column 'segment' is not in the card"}`},
		renderStep{200, renderJSON})
	v.editFn = func(hint, content string) (string, bool, error) { return content + "# edited\n", true, nil }
	v.generate("clients", "x")
	if v.state != viewGenIssues {
		t.Fatalf("state = %v, want viewGenIssues", v.state)
	}

	// e re-edits the SAME buffer; this time it closes unmodified and the
	// scripted render's second response succeeds.
	v.editFn = func(hint, content string) (string, bool, error) { return content, false, nil }
	v.handleGenIssuesKey(tcell.NewEventKey(tcell.KeyRune, 'e', tcell.ModNone))

	if v.state != viewList {
		t.Fatalf("state = %v, want viewList after the recovered render", v.state)
	}
	if v.genBuffer != "" {
		t.Errorf("genBuffer must clear once the file is born, got %q", v.genBuffer)
	}
	if _, err := os.Stat("clients-view.yaml"); err != nil {
		t.Fatalf("file must be born on the recovered render: %v", err)
	}
}

// fakeApplyHub serves /api/views/apply with a single canned response — the
// 'a' gesture's round-trip.
func fakeApplyHub(t *testing.T, status int, body string) *client.Client {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/views/apply" {
			t.Errorf("unexpected call: %s", r.URL.Path)
			w.WriteHeader(500)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	return client.New(config.Context{Server: srv.URL, Token: "static"})
}

// TestApplyToHubSuccessToastsName covers the 'a' gesture on a rendered view:
// the held manifest is posted to the saved-view store and a successful
// upsert toasts the confirmed name (the same chokepoint style as the
// local-write toast).
func TestApplyToHubSuccessToastsName(t *testing.T) {
	v, _, toasts := newTestViewPage("clients")
	v.manifest = []byte(genYAML)
	v.client = fakeApplyHub(t, 200, `{"name":"clients-view","scope":"clients"}`)
	v.handleListKey(tcell.NewEventKey(tcell.KeyRune, 'a', tcell.ModNone))
	joined := strings.Join(*toasts, " | ")
	if !strings.Contains(joined, "applied: clients-view") {
		t.Fatalf("success must toast the confirmed name, got %v", *toasts)
	}
}

// TestApplyToHubRefusalSurfacesVerbatim covers a scope refusal on apply: the
// honest string (Task 1's uniform 403 rendering) surfaces verbatim, not a
// silent no-op.
func TestApplyToHubRefusalSurfacesVerbatim(t *testing.T) {
	v, errs, toasts := newTestViewPage("clients")
	v.manifest = []byte(genYAML)
	v.client = fakeApplyHub(t, 403, `{"error":"scope not granted"}`)
	v.handleListKey(tcell.NewEventKey(tcell.KeyRune, 'a', tcell.ModNone))
	if len(*errs) != 1 || !strings.Contains((*errs)[0], "access refused (403): scope not granted") {
		t.Fatalf("refusal must surface the honest string verbatim, got %v", *errs)
	}
	if len(*toasts) != 0 {
		t.Errorf("no success toast on a refusal, got %v", *toasts)
	}
}

// TestApplyToHubAppliesTheOpenedFile pins the SANCTIONED broadened contract:
// 'a' applies whatever manifest the page holds — here the non-generated entry
// point, a real open(path) on a file — and the POSTed body IS that file's
// bytes, verbatim. Locks the contract so it can neither regress (back to
// generate-only) nor widen silently (some other bytes reaching the store).
func TestApplyToHubAppliesTheOpenedFile(t *testing.T) {
	const fileYAML = "kind: View\nscope: clients\ntitle: Opened, not generated\n"
	path := filepath.Join(t.TempDir(), "opened-view.yaml")
	if err := os.WriteFile(path, []byte(fileYAML), 0o644); err != nil {
		t.Fatal(err)
	}

	var applied []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/views/render": // open() renders before entering viewList
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(renderJSON))
		case "/api/views/apply":
			applied, _ = io.ReadAll(r.Body)
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"name":"clients-view","scope":"clients"}`))
		default:
			t.Errorf("unexpected call: %s", r.URL.Path)
			w.WriteHeader(500)
		}
	}))
	t.Cleanup(srv.Close)

	v, errs, toasts := newTestViewPage("clients")
	v.client = client.New(config.Context{Server: srv.URL, Token: "static"})
	v.open(path)
	if v.state != viewList {
		t.Fatalf("open must land on viewList, state = %v (errs=%v)", v.state, *errs)
	}
	v.handleListKey(tcell.NewEventKey(tcell.KeyRune, 'a', tcell.ModNone))

	if string(applied) != fileYAML {
		t.Errorf("the POSTed body must be the opened file's bytes, got %q", applied)
	}
	joined := strings.Join(*toasts, " | ")
	if !strings.Contains(joined, "applied: clients-view") {
		t.Errorf("success must toast the confirmed name, got %v", *toasts)
	}
}

// TestApplyToHubNoManifestIsNoOp covers the guard: pressing 'a' before any
// view has been opened/generated (no manifest held) must not call the client
// or emit any toast/error — there is nothing to apply yet.
func TestApplyToHubNoManifestIsNoOp(t *testing.T) {
	v, errs, toasts := newTestViewPage("clients")
	v.handleListKey(tcell.NewEventKey(tcell.KeyRune, 'a', tcell.ModNone))
	if len(*errs) != 0 || len(*toasts) != 0 {
		t.Errorf("no manifest held: no-op expected, got errs=%v toasts=%v", *errs, *toasts)
	}
}

func TestHintsCarryGenerate(t *testing.T) {
	v := &ViewPage{}
	v.state = viewOpen
	if h := v.Hints(); !strings.Contains(h, "g") || !strings.Contains(h, "generate") {
		t.Errorf("open hints must teach the gesture: %q", h)
	}
	v.state = viewList
	if h := v.Hints(); !strings.Contains(h, "g: generate") {
		t.Errorf("list hints must teach g: %q", h)
	}
	v.state = viewGenIssues
	h := v.Hints()
	if !strings.Contains(h, "e: re-edit") || !strings.Contains(h, "Esc") {
		t.Errorf("issues hints must teach e/Esc: %q", h)
	}
}
