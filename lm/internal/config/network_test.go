package config

import (
	"strings"
	"testing"
)

// A credential goes over plain http only to this machine: localhost, loopback, *.localhost, *.test — not *.local,
// which mDNS resolves on the LAN (#2004).
func TestClearText(t *testing.T) {
	for raw, want := range map[string]bool{
		"http://localhost:8080":       false,
		"http://LOCALHOST.:8080":      false,
		"http://127.0.0.2:8080":       false,
		"http://[::1]:8080":           false,
		"http://hub.localhost":        false,
		"http://hub.test./x":          false,
		"https://192.168.1.20":        false,
		"http://192.168.1.20:8080":    true,
		"http://lumnik.local":         true,
		"http://hub.example.com/path": true,
	} {
		if got := ClearText(raw); got != want {
			t.Errorf("ClearText(%q) = %v, want %v", raw, got, want)
		}
	}
}

func TestCheckClearText(t *testing.T) {
	t.Setenv("LUMNIK_ALLOW_HTTP", "")
	err := CheckClearText(Context{}, "local", "your token", "http://192.168.1.20:8080")
	if err == nil || !strings.Contains(err.Error(), "lm config set-context local --allow-insecure-http") ||
		!strings.Contains(err.Error(), "http://192.168.1.20:8080 in clear text") {
		t.Fatalf("err = %v", err)
	}
	if err := CheckClearText(Context{AllowInsecureHTTP: true}, "local", "your token", "http://192.168.1.20:8080"); err != nil {
		t.Errorf("allowed context refused: %v", err)
	}
	t.Setenv("LUMNIK_ALLOW_HTTP", "1")
	if err := CheckClearText(Context{}, "local", "your token", "http://192.168.1.20:8080"); err != nil {
		t.Errorf("LUMNIK_ALLOW_HTTP=1 refused: %v", err)
	}
}

func TestSameOrigin(t *testing.T) {
	for _, c := range []struct {
		a, b string
		want bool
	}{
		{"https://h", "https://H:443/", true},
		{"http://h/x/", "http://h:80", true},
		{"https://h", "https://h:8443", false},
		{"https://h", "http://h", false},
		{"http://a:8080", "http://b:8080", false},
	} {
		if got := SameOrigin(c.a, c.b); got != c.want {
			t.Errorf("SameOrigin(%q,%q) = %v, want %v", c.a, c.b, got, c.want)
		}
	}
}

// Resolve keeps the context's own server when LUMNIK_URL overrides it, so the client can tell a foreign hub (#2004).
func TestResolveKeepsStoredServer(t *testing.T) {
	t.Setenv("LUMNIK_URL", "http://other:8080")
	cfg := &Config{Contexts: map[string]Context{"local": {Server: "http://localhost:8080"}}}
	ctx, err := cfg.Resolve("local")
	if err != nil {
		t.Fatal(err)
	}
	if ctx.Server != "http://other:8080" || ctx.StoredServer() != "http://localhost:8080" {
		t.Errorf("server %q stored %q", ctx.Server, ctx.StoredServer())
	}
	if !ctx.Foreign() {
		t.Error("expected a foreign server")
	}
}
