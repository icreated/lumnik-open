package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadFromFile_Valid(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	content := `current-context: local
contexts:
  local:
    server: http://localhost:8080
    token: dev-token
  staging:
    server: https://staging.example.com
    token: tok123
`
	if err := os.WriteFile(path, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}

	cfg, err := LoadFromFile(path)
	if err != nil {
		t.Fatalf("LoadFromFile error: %v", err)
	}
	if cfg.CurrentContext != "local" {
		t.Errorf("want current-context=local, got %s", cfg.CurrentContext)
	}
	if got := cfg.Contexts["local"].Server; got != "http://localhost:8080" {
		t.Errorf("want local.server=http://localhost:8080, got %s", got)
	}
}

func TestResolveContext_FromCurrent(t *testing.T) {
	cfg := &Config{
		CurrentContext: "local",
		Contexts: map[string]Context{
			"local": {Server: "http://localhost:8080", Token: "dev-token"},
		},
	}
	ctx, err := cfg.Resolve("")
	if err != nil {
		t.Fatalf("Resolve error: %v", err)
	}
	if ctx.Server != "http://localhost:8080" {
		t.Errorf("wrong server: %s", ctx.Server)
	}
}

func TestResolveContext_Explicit(t *testing.T) {
	cfg := &Config{
		CurrentContext: "local",
		Contexts: map[string]Context{
			"local":   {Server: "http://localhost:8080"},
			"staging": {Server: "https://staging.example.com"},
		},
	}
	ctx, err := cfg.Resolve("staging")
	if err != nil {
		t.Fatalf("Resolve error: %v", err)
	}
	if ctx.Server != "https://staging.example.com" {
		t.Errorf("wrong server: %s", ctx.Server)
	}
}

func TestResolveContext_Unknown(t *testing.T) {
	cfg := &Config{
		CurrentContext: "local",
		Contexts:       map[string]Context{"local": {}},
	}
	if _, err := cfg.Resolve("nope"); err == nil {
		t.Fatal("want error for unknown context, got nil")
	}
}

func TestValidID(t *testing.T) {
	for v, want := range map[string]bool{"": true, "1": true, "42": true, "0": false, "-3": false, "acme": false, " 1": false} {
		if got := ValidID(v); got != want {
			t.Errorf("ValidID(%q) = %v, want %v", v, got, want)
		}
	}
}

// #1610: one rule for "remote https", shared by set-context, the client factory and the client.
func TestRemoteHTTPS(t *testing.T) {
	for server, want := range map[string]bool{
		"https://hub.customer.example": true, "https://10.0.0.5:8443": true, "https://hub.example.": true,
		"http://hub.customer.example": false, "https://localhost:8443": false, "https://localhost.:8443": false,
		"https://lumnik.local:8443": false, "https://lumnik.local.:8443": false, "https://x.localhost": false,
		"https://kind.test": false, "https://127.0.0.1:8443": false, "https://[::1]:8443": false, "": false,
	} {
		if _, got := RemoteHTTPS(server); got != want {
			t.Errorf("RemoteHTTPS(%q) = %v, want %v", server, got, want)
		}
	}
}

func TestCheckIdentity(t *testing.T) {
	if err := CheckIdentity(Context{Server: "https://ops:s3cr3t@hub.example", Tenant: "1"}); err == nil ||
		!strings.Contains(err.Error(), "hub.example") || strings.Contains(err.Error(), "s3cr3t") {
		t.Errorf("want a refusal naming the host only, got %v", err)
	}
	for _, ok := range []Context{{Server: "https://hub.example"}, {Server: "http://localhost:18090", Tenant: "1", User: "1"}} {
		if err := CheckIdentity(ok); err != nil {
			t.Errorf("%+v: %v", ok, err)
		}
	}
}
