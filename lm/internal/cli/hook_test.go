package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHookList(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode([]map[string]any{
			{"id": "h1", "type": "before-insert", "entity": "Repair", "scope": "tenant", "status": "LOADED"},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"hook", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "h1") {
		t.Errorf("unexpected: %s", buf.String())
	}
}

// hookDetailStub writes the shape the REAL singular endpoint sends — HookResource.summarize(),
// not the list endpoint's DTO (HookAdminResource.toDto, which is `type`/`entity`/`scope`/`status`
// and is what the old stub here impersonated). See TestHookGetDecodesTheDetailShape (#607).
func hookDetailStub(w http.ResponseWriter, r *http.Request) {
	_ = json.NewEncoder(w).Encode(map[string]any{
		"id": "h1", "module": "billing", "description": "guards invoice totals",
		"target": "io.lumnik.hub.Invoice", "action": "",
		"events":      []string{"BEFORE_CREATE", "AFTER_UPDATE"},
		"handler":     map[string]any{"className": "io.lumnik.hub.hooks.InvoiceGuard"},
		"priority":    100,
		"tenants":     []string{"*"},
		"enabled":     true,
		"actionPhase": "",
	})
}

func TestHookGet(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(hookDetailStub))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"hook", "get", "h1"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "h1") {
		t.Errorf("unexpected: %s", buf.String())
	}
}

// -o is a persistent root flag, so `hook get` accepted -o json and ignored it, always printing
// YAML (#532). And once routed to JSON, it decoded the wrong DTO: GET /api/platform/hooks/{id}
// was unmarshalled into types.Hook — the LIST endpoint's shape (id/type/entity/scope/status).
// json.Unmarshal silently drops unmatched fields, so every field but id came back empty —
// description, target, action, events, handler, tenants, enabled, actionPhase, the real payload
// the hub actually sends — never reached the CLI (#607).
func TestHookGetHonoursJsonWithTheDetailShape(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(hookDetailStub))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"hook", "get", "h1", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}

	var got map[string]any
	if err := json.Unmarshal(buf.Bytes(), &got); err != nil {
		t.Fatalf("-o json must emit parseable JSON, got %q: %v", buf.String(), err)
	}
	if got["id"] != "h1" {
		t.Errorf("id dropped: %v", got)
	}
	if got["description"] != "guards invoice totals" {
		t.Errorf("description dropped: %v", got)
	}
	if got["target"] != "io.lumnik.hub.Invoice" {
		t.Errorf("target dropped: %v", got)
	}
	handler, _ := got["handler"].(map[string]any)
	if handler["className"] != "io.lumnik.hub.hooks.InvoiceGuard" {
		t.Errorf("handler dropped: %v", got)
	}
	if got["enabled"] != true {
		t.Errorf("enabled dropped: %v", got)
	}
	if got["priority"] != float64(100) {
		t.Errorf("priority dropped: %v", got)
	}
}

// hook apply/delete/reload all called hub endpoints that don't exist — HookResource declares
// only GET (list) and GET/{id}; HookRegistry has no update/delete method at all, since hooks are
// registered once, in-memory, from classpath manifests (HookManifestScanner) at startup, not
// mutated through the platform API (#610). They now refuse honestly instead of 404ing.
func TestHookApplyDeleteReload_notImplemented_neverTouchTheHub(t *testing.T) {
	// No server is stubbed: the refusal must happen before ClientFromCmd is ever called, so
	// there is nothing here for a request to reach — an unreachable address is enough to prove
	// that, without a real listener.
	setupCfg(t, "http://127.0.0.1:0")

	for _, args := range [][]string{
		{"hook", "apply"},
		{"hook", "delete", "h1"},
		{"hook", "reload", "h1"},
	} {
		t.Run(strings.Join(args, " "), func(t *testing.T) {
			cmd := NewRootCmd()
			buf := new(bytes.Buffer)
			cmd.SetOut(buf)
			cmd.SetErr(buf)
			cmd.SetArgs(args)
			err := cmd.Execute()
			if err == nil {
				t.Fatal("expected a refusal")
			}
			if !strings.Contains(err.Error(), "not implemented") {
				t.Errorf("error must say the command is not implemented, got: %v", err)
			}
			if !strings.Contains(err.Error(), "deployment manifests") {
				t.Errorf("error must say WHY — hooks come from deployment manifests, got: %v", err)
			}
		})
	}
}

// hookScriptsStub sends what HookScriptResource sends (#1402).
func hookScriptsStub(directory bool, scripts ...map[string]any) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/platform/hooks/scripts" {
			http.NotFound(w, r)
			return
		}
		if scripts == nil {
			scripts = []map[string]any{}
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"directory": directory, "scripts": scripts})
	}
}

func runHook(t *testing.T, handler http.HandlerFunc, args ...string) (string, string, error) {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	return runLm(t, srv, append([]string{"hook"}, args...)...)
}

func TestHookScriptsListsTheHooksDirectoryWithTheFingerprint(t *testing.T) {
	sha := strings.Repeat("ab", 32)
	out, _, err := runHook(t, hookScriptsStub(true, map[string]any{"name": "ean-check.kts", "size": 812, "sha256": sha}), "scripts")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"NAME", "SIZE", "SHA256", "ean-check.kts", "812", sha} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
}

func TestHookScriptsSaysWhenTheHubHasNoHooksDirectory(t *testing.T) {
	out, errOut, err := runHook(t, hookScriptsStub(false), "scripts")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(errOut, "no hooks directory") || !strings.Contains(errOut, "LUMNIK_HOOKS_DIR") {
		t.Errorf("stderr should say the operator sets none, got: %q", errOut)
	}
	if strings.Contains(out, "NAME") {
		t.Errorf("no table for a directory that does not exist, got:\n%s", out)
	}
}

func TestHookScriptsSaysWhenTheDirectoryIsEmpty(t *testing.T) {
	_, errOut, err := runHook(t, hookScriptsStub(true), "scripts")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(errOut, "no scripts") {
		t.Errorf("stderr should say the directory holds no script, got: %q", errOut)
	}
}

func TestHookScriptsJsonKeepsTheDirectoryFlag(t *testing.T) {
	out, _, err := runHook(t, hookScriptsStub(false), "scripts", "-o", "json")
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal([]byte(out), &got); err != nil {
		t.Fatalf("not JSON: %v\n%s", err, out)
	}
	if got["directory"] != false {
		t.Errorf("directory flag lost: %s", out)
	}
}
