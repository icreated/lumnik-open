package cli

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func writeConfig(t *testing.T, body string) string {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("HOME", dir)
	_ = os.MkdirAll(filepath.Join(dir, ".lm"), 0o700)
	path := filepath.Join(dir, ".lm", "config.yaml")
	if body != "" {
		_ = os.WriteFile(path, []byte(body), 0o600)
	}
	return path
}

func TestConfigView(t *testing.T) {
	writeConfig(t, "current-context: x\ncontexts:\n  x:\n    server: http://h\n    token: t\n")
	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetArgs([]string{"config", "view"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "current-context: x") {
		t.Errorf("unexpected: %s", buf.String())
	}
}

const credentialConfig = "current-context: x\ncontexts:\n" +
	"  x:\n    server: http://h\n    token: legacy-secret\n    access: access-secret\n    refresh: refresh-secret\n" +
	"  y:\n    server: http://h2\n    token: \"\"\n"

func TestConfigViewRedactsCredentials(t *testing.T) {
	path := writeConfig(t, credentialConfig)
	out := runRoot(t, "config", "view")
	for _, secret := range []string{"legacy-secret", "access-secret", "refresh-secret"} {
		if strings.Contains(out, secret) {
			t.Errorf("config view leaked %q:\n%s", secret, out)
		}
	}
	for _, field := range []string{"token: REDACTED", "access: REDACTED", "refresh: REDACTED"} {
		if !strings.Contains(out, field) {
			t.Errorf("config view missing %q:\n%s", field, out)
		}
	}
	if n := strings.Count(out, "REDACTED"); n != 3 {
		t.Errorf("an empty credential must not be marked REDACTED; got %d markers:\n%s", n, out)
	}
	if data, _ := os.ReadFile(path); !strings.Contains(string(data), "access-secret") {
		t.Errorf("config view must not rewrite the file: %s", data)
	}
}

func TestConfigViewRawPrintsCredentials(t *testing.T) {
	writeConfig(t, credentialConfig)
	out := runRoot(t, "config", "view", "--raw")
	for _, secret := range []string{"legacy-secret", "access-secret", "refresh-secret"} {
		if !strings.Contains(out, secret) {
			t.Errorf("config view --raw must print %q:\n%s", secret, out)
		}
	}
	if strings.Contains(out, "REDACTED") {
		t.Errorf("config view --raw must not redact:\n%s", out)
	}
}

func TestConfigUseContext(t *testing.T) {
	path := writeConfig(t, "current-context: x\ncontexts:\n  x: {server: h}\n  y: {server: h2}\n")
	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetArgs([]string{"config", "use-context", "y"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	s := string(data)
	if !strings.Contains(s, "current-context: \"y\"") && !strings.Contains(s, "current-context: y") {
		t.Errorf("config not updated: %s", s)
	}
}

func TestSetContextStoresOidcIssuer(t *testing.T) {
	path := writeConfig(t, "")
	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetArgs([]string{"config", "set-context", "sh",
		"--server", "http://hub", "--oidc-issuer", "http://kc/realms/lumnik", "--oidc-client-id", "lm-cli"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	s := string(data)
	if !strings.Contains(s, "oidc-issuer: http://kc/realms/lumnik") {
		t.Errorf("oidc-issuer not stored: %s", s)
	}
	if !strings.Contains(s, "oidc-client-id: lm-cli") {
		t.Errorf("oidc-client-id not stored: %s", s)
	}
}

func TestSetContextOidcIssuerClearsStaleEndpoints(t *testing.T) {
	// A context that already has discovered endpoints AND tokens from the old issuer...
	path := writeConfig(t, "current-context: sh\ncontexts:\n  sh:\n    server: http://hub\n    oidc-issuer: http://old/realms/x\n    oidc-device-endpoint: http://old/dev\n    oidc-token-endpoint: http://old/tok\n    access: OLD-ACCESS\n    refresh: OLD-REFRESH\n    expires-at: 9999999999\n")
	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	// ...pointed at a NEW issuer must drop the stale endpoints AND tokens (they belong to
	// the old issuer) so lm login re-discovers and re-authenticates.
	cmd.SetArgs([]string{"config", "set-context", "sh", "--oidc-issuer", "http://new/realms/y"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	s := string(data)
	if strings.Contains(s, "old/dev") || strings.Contains(s, "old/tok") {
		t.Errorf("stale endpoints not cleared: %s", s)
	}
	if strings.Contains(s, "OLD-ACCESS") || strings.Contains(s, "OLD-REFRESH") {
		t.Errorf("stale tokens not cleared: %s", s)
	}
	if !strings.Contains(s, "oidc-issuer: http://new/realms/y") {
		t.Errorf("new issuer not stored: %s", s)
	}
}

// Re-running set-context with the SAME issuer must be idempotent — it must NOT
// wipe the stored tokens/endpoints (scripts/CI re-run it; that shouldn't log you out).
func TestSetContextSameOidcIssuerKeepsTokens(t *testing.T) {
	path := writeConfig(t, "current-context: sh\ncontexts:\n  sh:\n    server: http://hub\n    oidc-issuer: http://kc/realms/x\n    oidc-device-endpoint: http://kc/dev\n    access: KEEP-ACCESS\n    refresh: KEEP-REFRESH\n")
	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetArgs([]string{"config", "set-context", "sh", "--oidc-issuer", "http://kc/realms/x"}) // unchanged
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	s := func() string { b, _ := os.ReadFile(path); return string(b) }()
	if !strings.Contains(s, "KEEP-ACCESS") || !strings.Contains(s, "KEEP-REFRESH") {
		t.Errorf("tokens wiped on unchanged issuer: %s", s)
	}
	if !strings.Contains(s, "http://kc/dev") {
		t.Errorf("endpoint wiped on unchanged issuer: %s", s)
	}
}

// #1610: a dev-bypass hub (make k8s-up) identifies the caller by X-Tenant-ID / X-User-ID, which the client
// sends from the context's tenant/user — set here, not by hand-editing config.yaml.
func TestSetContextStoresDevBypassIdentity(t *testing.T) {
	path := writeConfig(t, "")
	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetArgs([]string{"config", "set-context", "kind", "--server", "http://localhost:18090", "--tenant", "1", "--user", "1"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	s := string(data)
	if !strings.Contains(s, `tenant: "1"`) || !strings.Contains(s, `user: "1"`) {
		t.Errorf("dev-bypass identity not stored: %s", s)
	}
}

func TestSetContextEmptyTenantClearsTheIdentity(t *testing.T) {
	path := writeConfig(t, "current-context: kind\ncontexts:\n  kind:\n    server: http://localhost:18090\n    tenant: \"1\"\n    user: \"1\"\n")
	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetArgs([]string{"config", "set-context", "kind", "--tenant", "", "--user", ""})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	if s := string(data); strings.Contains(s, "tenant:") || strings.Contains(s, "user:") {
		t.Errorf("identity not cleared: %s", s)
	}
}

// The hub reads the headers as numbers and ignores anything else in silence. Refused here, by the flag's name.
func TestSetContextRefusesANonPositiveIdentity(t *testing.T) {
	writeConfig(t, "")
	for _, args := range [][]string{{"--tenant", "acme"}, {"--user", "-3"}, {"--tenant", "0"}} {
		cmd := NewRootCmd()
		cmd.SetOut(new(bytes.Buffer))
		cmd.SetErr(new(bytes.Buffer))
		cmd.SetArgs(append([]string{"config", "set-context", "kind"}, args...))
		err := cmd.Execute()
		if err == nil || !strings.Contains(err.Error(), args[0]+" must be a positive integer") {
			t.Errorf("%v: want a refusal naming %s, got %v", args, args[0], err)
		}
	}
}

// #1610 (code-review): the dev-bypass identity stays off a remote https hub — a production context must not carry it.
func TestSetContextRefusesAnIdentityForARemoteHttpsServer(t *testing.T) {
	writeConfig(t, "")
	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"config", "set-context", "prod", "--server", "https://ops:s3cr3t@hub.customer.example/", "--tenant", "1"})
	err := cmd.Execute()
	if err == nil || !strings.Contains(err.Error(), "dev-bypass identity") || !strings.Contains(err.Error(), "hub.customer.example") {
		t.Fatalf("want a refusal naming the host, got %v", err)
	}
	if strings.Contains(err.Error(), "s3cr3t") {
		t.Errorf("the refusal must not echo the URL's credentials: %v", err)
	}
	for _, server := range []string{"http://localhost:18090", "https://lumnik.local:8443", "https://127.0.0.1:8443", "http://hub.lan:8080",
		"https://localhost.:8443", "https://lumnik.local.:8443"} { // a fully-qualified name keeps its trailing dot
		cmd := NewRootCmd()
		cmd.SetOut(new(bytes.Buffer))
		cmd.SetArgs([]string{"config", "set-context", "dev", "--server", server, "--tenant", "1"})
		if err := cmd.Execute(); err != nil {
			t.Errorf("%s: a local or plain-http dev hub must accept an identity: %v", server, err)
		}
	}
}

// #1610 (Copilot on #1614): the hub strips the header before parsing it; a pasted space is trimmed, and the trimmed
// value is what is stored and sent.
func TestSetContextTrimsTheIdentity(t *testing.T) {
	path := writeConfig(t, "")
	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetArgs([]string{"config", "set-context", "kind", "--server", "http://localhost:18090", "--tenant", " 1 ", "--user", "\t2"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	if s := string(data); !strings.Contains(s, `tenant: "1"`) || !strings.Contains(s, `user: "2"`) {
		t.Errorf("identity not trimmed: %s", s)
	}
}

// --allow-insecure-http lets one context send its credentials over plain http off this machine; config view
// shows it, and =false takes it back (#2004).
func TestSetContextAllowInsecureHTTP(t *testing.T) {
	writeConfig(t, "")
	runRoot(t, "config", "set-context", "lan", "--server", "http://192.168.1.20:8080", "--allow-insecure-http")
	if out := runRoot(t, "config", "view"); !strings.Contains(out, "allow-insecure-http: true") {
		t.Errorf("config view does not show the option:\n%s", out)
	}
	runRoot(t, "config", "set-context", "lan", "--allow-insecure-http=false")
	if out := runRoot(t, "config", "view"); strings.Contains(out, "allow-insecure-http") {
		t.Errorf("the option was not taken back:\n%s", out)
	}
}

// --server pointing at another hub keeps the context's own server, so its stored token stays home (#2004).
func TestServerFlagToAnotherHubWithholdsStoredToken(t *testing.T) {
	var auth atomic.Value
	auth.Store("unset")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth.Store(r.Header.Get("Authorization"))
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()
	writeConfig(t, "current-context: local\ncontexts:\n  local:\n    server: http://localhost:1\n    token: STORED\n")
	t.Setenv("LUMNIK_URL", "")
	t.Setenv("LUMNIK_TOKEN", "")
	cmd := NewRootCmd()
	if err := cmd.ParseFlags([]string{"--server", srv.URL}); err != nil {
		t.Fatal(err)
	}
	c, err := ClientFromCmd(cmd)
	if err != nil {
		t.Fatal(err)
	}
	_ = c.GET("/x", nil)
	if auth.Load() != "" {
		t.Errorf("Authorization = %q", auth.Load())
	}
}

// #2004: a token read from stdin never shows in ps or the shell history.
func TestSetContextTokenFromStdin(t *testing.T) {
	path := writeConfig(t, "")
	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetIn(strings.NewReader("tok-from-stdin\n"))
	cmd.SetArgs([]string{"config", "set-context", "ci", "--server", "https://hub.example", "--token-stdin"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if data, _ := os.ReadFile(path); !strings.Contains(string(data), "token: tok-from-stdin\n") {
		t.Errorf("token not stored from stdin: %s", data)
	}
	cmd = NewRootCmd()
	cmd.SetIn(strings.NewReader("x"))
	cmd.SetArgs([]string{"config", "set-context", "ci", "--token", "a", "--token-stdin"})
	if err := cmd.Execute(); err == nil {
		t.Error("--token and --token-stdin together were accepted")
	}
}

