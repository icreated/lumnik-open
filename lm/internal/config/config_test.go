package config

import (
	"os"
	"path/filepath"
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
