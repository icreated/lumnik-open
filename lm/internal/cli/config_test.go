package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
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
