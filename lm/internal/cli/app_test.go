package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"gopkg.in/yaml.v3"
)

func TestAppApplyCmd_postsToTheAppEndpoint(t *testing.T) {
	var gotPath, gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		b := new(bytes.Buffer)
		_, _ = b.ReadFrom(r.Body)
		gotBody = b.String()
		if r.Method != http.MethodPost || r.URL.Path != "/api/apps/apply" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `{"name":"lumilait","scope":"lumilait","default":false}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	f := filepath.Join(t.TempDir(), "lumilait.yaml")
	manifest := "kind: App\nmetadata:\n  name: lumilait\nspec:\n  title: Lumilait\n  scope: lumilait\n  icon: generic\n"
	if err := os.WriteFile(f, []byte(manifest), 0o600); err != nil {
		t.Fatal(err)
	}

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"app", "apply", "-f", f})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// The routing is the point: a kind:App manifest handed to `lm apply -f` lands at the
	// CONNECTOR endpoint, silently. Assert where it went, not only what was printed.
	if gotPath != "/api/apps/apply" {
		t.Errorf("posted to %q, want /api/apps/apply", gotPath)
	}
	if gotBody != manifest {
		t.Errorf("body was rewritten in flight:\n%q", gotBody)
	}
	if !strings.Contains(buf.String(), "lumilait") {
		t.Errorf("expected the app name in output, got: %s", buf.String())
	}
}

func TestAppApplyCmd_serverError_returnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = fmt.Fprint(w, `{"error":"kind must be 'App'"}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	f := filepath.Join(t.TempDir(), "bad.yaml")
	if err := os.WriteFile(f, []byte("kind: Connector\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"app", "apply", "-f", f})
	if err := cmd.Execute(); err == nil {
		t.Fatal("expected error on 400, got nil")
	}
}

func TestAppRmCmd_printsDeleted(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodDelete || r.URL.Path != "/api/apps/lumilait" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"app", "rm", "lumilait"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !strings.Contains(buf.String(), "lumilait") {
		t.Errorf("expected the app name in output, got: %s", buf.String())
	}
}

func TestAppRmCmd_serverError_returnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = fmt.Fprint(w, `{"error":"cannot remove the last default app — apply a different app with default: true first, which demotes this one automatically"}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"app", "rm", "clients"})
	if err := cmd.Execute(); err == nil {
		t.Fatal("expected error on 400, got nil")
	}
}

func TestAppApplyCmd_requiresAFile(t *testing.T) {
	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"app", "apply"})
	if err := cmd.Execute(); err == nil {
		t.Fatal("expected -f to be required")
	}
}

// A tab this hub does not offer is KEPT and reported: the apply succeeded (exit 0) and the advice
// goes to stderr, so a script piping stdout is not disturbed (the Connector door's convention).
func TestAppApplyCmd_printsTheDeclaredTabsAndTheWarningsOnStderr(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `{"name":"stock","scope":"stock","default":false,"tabs":["fiches","holodeck"],`+
			`"warnings":[{"path":"spec.tabs[1]","severity":"WARNING",`+
			`"message":"tab 'holodeck' is not offered by this hub; the declaration is kept","recommendations":["offered here: [fiches, inbox]"]}]}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	f := filepath.Join(t.TempDir(), "stock.yaml")
	if err := os.WriteFile(f, []byte("kind: App\nmetadata:\n  name: stock\nspec:\n  title: Stock\n  scope: stock\n  icon: stock\n  tabs: [fiches, holodeck]\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	cmd := NewRootCmd()
	out, errOut := new(bytes.Buffer), new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(errOut)
	cmd.SetArgs([]string{"app", "apply", "-f", f})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("a warning must never fail an apply: %v", err)
	}
	if !strings.Contains(out.String(), "tabs: fiches, holodeck") {
		t.Errorf("summary lacks the declared tabs: %s", out.String())
	}
	if strings.Contains(out.String(), "tab 'holodeck' is not offered") || strings.Contains(out.String(), "spec.tabs[1]") {
		t.Errorf("the advice belongs on stderr, not stdout: %s", out.String())
	}
	if !strings.Contains(errOut.String(), "spec.tabs[1]") || !strings.Contains(errOut.String(), "holodeck") {
		t.Errorf("warning not rendered on stderr: %s", errOut.String())
	}
}

func TestAppApplyCmd_withoutTabsSaysNothingAboutThem(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = fmt.Fprint(w, `{"name":"stock","scope":"stock","default":false}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)
	f := filepath.Join(t.TempDir(), "stock.yaml")
	if err := os.WriteFile(f, []byte("kind: App\nmetadata:\n  name: stock\nspec:\n  title: Stock\n  scope: stock\n  icon: stock\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cmd := NewRootCmd()
	out, errOut := new(bytes.Buffer), new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(errOut)
	cmd.SetArgs([]string{"app", "apply", "-f", f})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out.String(), "tabs") || errOut.Len() != 0 {
		t.Errorf("nothing to say about tabs: out=%q err=%q", out.String(), errOut.String())
	}
}

// --- lm app list (#1061) ---------------------------------------------------------------------

const appListBody = `[` +
	`{"name":"pwa-pagination","scope":"catalogue","title":"Catalogue","icon":"stock","accentColor":"#8b7ff0",` +
	`"backgroundColor":"#14121f","default":true,"tabs":["fiches","search"],"futureField":{"kept":1}},` +
	`{"name":"stock","scope":"stock","title":"Stock","icon":"generic","accentColor":"#f5b83d",` +
	`"backgroundColor":"#14121f","default":false,"tabs":null}]`

func appListServer(t *testing.T, status int, body string) {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/api/apps" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = fmt.Fprint(w, body)
	}))
	t.Cleanup(srv.Close)
	setupCfg(t, srv.URL)
}

func runApp(t *testing.T, args ...string) (string, error) {
	t.Helper()
	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs(append([]string{"app"}, args...))
	err := cmd.Execute()
	return buf.String(), err
}

func TestAppListCmd_tableShowsTheIdentityAndTheTheme(t *testing.T) {
	appListServer(t, http.StatusOK, appListBody)

	out, err := runApp(t, "list")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) != 3 {
		t.Fatalf("want a header and two apps, got:\n%s", out)
	}
	for _, h := range []string{"NAME", "TITLE", "SCOPE", "ICON", "ACCENT", "BACKGROUND", "DEFAULT", "TABS"} {
		if !strings.Contains(lines[0], h) {
			t.Errorf("header lacks %s: %q", h, lines[0])
		}
	}
	first, second := strings.Fields(lines[1]), strings.Fields(lines[2])
	// pwa-pagination: a default app with two declared tabs
	if strings.Join(first, " ") != "pwa-pagination Catalogue catalogue stock #8b7ff0 #14121f yes fiches,search" {
		t.Errorf("first row: %q", lines[1])
	}
	// stock: not the default (blank), tabs null = it inherits every tab the hub offers
	if strings.Join(second, " ") != "stock Stock stock generic #f5b83d #14121f (all)" {
		t.Errorf("second row: %q", lines[2])
	}
}

// The acceptance of the ticket, as a test: the theme survives -o json, with every field the hub sent.
func TestAppListCmd_jsonKeepsEveryFieldTheHubSent(t *testing.T) {
	appListServer(t, http.StatusOK, appListBody)

	out, err := runApp(t, "list", "-o", "json")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	var apps []map[string]any
	if err := json.Unmarshal([]byte(out), &apps); err != nil {
		t.Fatalf("not JSON: %v\n%s", err, out)
	}
	if len(apps) != 2 || apps[0]["name"] != "pwa-pagination" {
		t.Fatalf("unexpected apps: %v", apps)
	}
	if apps[0]["accentColor"] != "#8b7ff0" || apps[0]["backgroundColor"] != "#14121f" || apps[0]["default"] != true {
		t.Errorf("theme or default lost: %v", apps[0])
	}
	if _, ok := apps[0]["futureField"]; !ok {
		t.Errorf("a field this lm does not know was dropped: %v", apps[0])
	}
	if v, present := apps[1]["tabs"]; !present || v != nil {
		t.Errorf("tabs: null must stay null (inherits everything), got %v (present=%v)", v, present)
	}
}

// -o json prints the hub's own values: no HTML escaping of a title, no float64 rounding of a big number,
// the hub's key order — what a script diffing two registries (or a future numeric field) relies on.
func TestAppListCmd_jsonIsTheHubsBodyReIndentedNotRebuilt(t *testing.T) {
	appListServer(t, http.StatusOK,
		`[{"name":"a","title":"R&D <lab>","future":12345678901234567890,"scope":"s"}]`)

	out, err := runApp(t, "list", "-o", "json")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	for _, want := range []string{`"title": "R&D <lab>"`, `"future": 12345678901234567890`} {
		if !strings.Contains(out, want) {
			t.Errorf("want %q in:\n%s", want, out)
		}
	}
	if strings.Index(out, `"name"`) > strings.Index(out, `"scope"`) || strings.Index(out, `"title"`) > strings.Index(out, `"scope"`) {
		t.Errorf("the hub's key order was not kept:\n%s", out)
	}
}

func TestAppListCmd_aBodyThatIsNotAListIsSaidPlainly(t *testing.T) {
	// an object, and the JSON null: neither is a list, and a hub that sends one is not to be read as "no apps"
	for _, body := range []string{`{"error":"surprise"}`, `null`} {
		appListServer(t, http.StatusOK, body)
		for _, format := range []string{"table", "json", "yaml"} {
			_, err := runApp(t, "list", "-o", format)
			if err == nil || !strings.Contains(err.Error(), "not a list of apps") || strings.Contains(err.Error(), "Go value") {
				t.Errorf("body %s, -o %s: want a plain 'not a list of apps' error, got %v", body, format, err)
			}
		}
	}
}

// -o json is byte-exact (the repo's rule for an explicitly requested machine format), so what keeps a hub-supplied
// title from driving the terminal is JSON itself: a control character is escaped, and a raw one is not valid JSON.
func TestAppListCmd_jsonNeverCarriesARawControlCharacterToTheTerminal(t *testing.T) {
	appListServer(t, http.StatusOK, `[{"name":"a","title":"x\u001b[31mred"}]`)
	out, err := runApp(t, "list", "-o", "json")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if strings.ContainsRune(out, 0x1b) || !strings.Contains(out, `\u001b[31m`) {
		t.Errorf("an escaped control character must stay escaped, got %q", out)
	}

	appListServer(t, http.StatusOK, "[{\"name\":\"a\",\"title\":\"x\x1b[31mred\"}]") // a RAW escape byte: not JSON
	out, err = runApp(t, "list", "-o", "json")
	if err == nil || strings.ContainsRune(out, 0x1b) {
		t.Errorf("a body with a raw control character must be refused, not printed: err=%v out=%q", err, out)
	}
}

// "Is it a list" must not be decided by turning numbers into float64: 1e400 is valid JSON the hub could send.
func TestAppListCmd_aNumberOutOfFloatRangeIsNotARefusal(t *testing.T) {
	appListServer(t, http.StatusOK, `[{"name":"a","title":"A","scope":"s","future":1e400}]`)

	out, err := runApp(t, "list", "-o", "json")
	if err != nil || !strings.Contains(out, `"future": 1e400`) {
		t.Fatalf("-o json must print the body verbatim: err=%v out=%q", err, out)
	}
	if _, err := runApp(t, "list"); err != nil {
		t.Fatalf("the table does not read that field at all: %v", err)
	}
	out, err = runApp(t, "list", "-o", "yaml")
	if err != nil || !strings.Contains(out, "future: 1e400") {
		t.Fatalf("-o yaml keeps what it cannot hold as a number as text: err=%v out=%q", err, out)
	}
}

func TestAppListCmd_yamlKeepsIntegersExactAndFloatsAsFloats(t *testing.T) {
	appListServer(t, http.StatusOK, `[{"name":"a","big":12345678901234567890,"small":3,"ratio":1.5}]`)

	out, err := runApp(t, "list", "-o", "yaml")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	for _, want := range []string{`big: "12345678901234567890"`, "small: 3", "ratio: 1.5"} {
		if !strings.Contains(out, want) {
			t.Errorf("yaml lacks %q:\n%s", want, out)
		}
	}
}

func TestAppListCmd_yamlKeepsEveryFieldTheHubSent(t *testing.T) {
	appListServer(t, http.StatusOK, appListBody)

	out, err := runApp(t, "list", "-o", "yaml")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	for _, want := range []string{"name: pwa-pagination", "accentColor: '#8b7ff0'", "futureField:", "default: true"} {
		if !strings.Contains(out, want) {
			t.Errorf("yaml lacks %q:\n%s", want, out)
		}
	}
}

func TestAppListCmd_anEmptyRegistryIsAnEmptyListNotAnError(t *testing.T) {
	appListServer(t, http.StatusOK, `[]`)

	out, err := runApp(t, "list", "-o", "json")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if strings.TrimSpace(out) != "[]" {
		t.Errorf("want [], got %q", out)
	}
}

// A refusal from the hub is the error (an expired token, a missing role) — never a silent empty list.
func TestAppListCmd_theHubsRefusalIsPreserved(t *testing.T) {
	appListServer(t, http.StatusForbidden, `{"error":"scope not granted"}`)

	_, err := runApp(t, "list")
	if err == nil || !strings.Contains(err.Error(), "scope not granted") {
		t.Fatalf("want the hub's refusal, got %v", err)
	}
}

// `lm app list` used to print the help and exit 0: an unknown subcommand must now fail.
func TestAppCmd_anUnknownSubcommandFailsInsteadOfPrintingHelp(t *testing.T) {
	out, err := runApp(t, "nimportequoi")
	if err == nil {
		t.Fatalf("an unknown subcommand must not exit 0, got:\n%s", out)
	}
	if !strings.Contains(err.Error(), "nimportequoi") {
		t.Errorf("the error should name what was typed, got %v", err)
	}
}

// --- lm app get (#1176) ----------------------------------------------------------------------

// The hub keeps no app manifest text, but a kind:App is exactly the row's fields: what `lm app get`
// prints, read the way AppManifestParser reads it, is the row again — so `get | apply` changes
// nothing. tabs: null (inherit every tab) is no key at all; the hub refuses an empty list.
func TestAppGetCmd_printsAManifestThatAppliesBackToTheSameRow(t *testing.T) {
	for name, row := range map[string]string{
		"tabs declared": `{"name":"desk","scope":"clients","title":"R&D: desk #1","icon":"clients",` +
			`"accentColor":"#8b7ff0","backgroundColor":"#14121f","default":true,"tabs":["fiches","search"]}`,
		"tabs inherited": `{"name":"stock","scope":"stock","title":"Stock","icon":"generic",` +
			`"accentColor":"#f5b83d","backgroundColor":"#f4f1ea","default":false,"tabs":null}`,
	} {
		t.Run(name, func(t *testing.T) {
			var want client.AppInfo
			if err := json.Unmarshal([]byte(row), &want); err != nil {
				t.Fatal(err)
			}
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodGet || r.URL.Path != "/api/apps/"+want.Name {
					w.WriteHeader(http.StatusNotFound)
					return
				}
				_, _ = fmt.Fprint(w, row)
			}))
			t.Cleanup(srv.Close)
			setupCfg(t, srv.URL)

			out, err := runApp(t, "get", want.Name)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			// the fields AppManifestParser reads, by the paths it reads them at
			var m struct {
				APIVersion string `yaml:"apiVersion"`
				Kind       string
				Metadata   struct{ Name string }
				Spec       struct {
					Title, Scope, Icon string
					Theme              struct {
						AccentColor     string `yaml:"accentColor"`
						BackgroundColor string `yaml:"backgroundColor"`
					}
					Default bool
					Tabs    []string
				}
			}
			if err := yaml.Unmarshal([]byte(out), &m); err != nil {
				t.Fatalf("not YAML: %v\n%s", err, out)
			}
			if m.Kind != "App" || m.APIVersion != "apps.lumnik.io/v1" {
				t.Errorf("envelope: kind %q apiVersion %q", m.Kind, m.APIVersion)
			}
			if want.Tabs == nil && strings.Contains(out, "tabs") {
				t.Errorf("tabs: null must be no key at all (an empty list is refused):\n%s", out)
			}
			s := m.Spec
			got := client.AppInfo{Name: m.Metadata.Name, Title: s.Title, Scope: s.Scope, Icon: s.Icon,
				AccentColor: s.Theme.AccentColor, BackgroundColor: s.Theme.BackgroundColor, Default: s.Default, Tabs: s.Tabs}
			if fmt.Sprint(got) != fmt.Sprint(want) {
				t.Errorf("round trip:\n got %+v\nwant %+v\n%s", got, want, out)
			}
		})
	}
}

func TestAppGetCmd_theHubsRefusalIsTheError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = fmt.Fprint(w, `{"error":"no app named 'ghost'"}`)
	}))
	t.Cleanup(srv.Close)
	setupCfg(t, srv.URL)
	out, err := runApp(t, "get", "ghost")
	if err == nil || !strings.Contains(err.Error(), "no app named") || strings.Contains(out, "kind: App") {
		t.Fatalf("want the hub's 404 and no manifest, got err=%v out=%q", err, out)
	}
}

func TestAppCmd_bareStillPrintsTheHelp(t *testing.T) {
	out, err := runApp(t)
	if err != nil {
		t.Fatalf("bare `lm app` is the help, not an error: %v", err)
	}
	for _, sub := range []string{"apply", "get", "list", "rm"} {
		if !strings.Contains(out, sub) {
			t.Errorf("help should list %q:\n%s", sub, out)
		}
	}
}
