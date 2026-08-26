package cli

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// These command-level regression tests lock in the security contract of the OTK
// Gold Partie 1 chantier: a default command's stdout, run through cli.Run, must
// NEVER reflect a raw control/bidi rune even when the hub echoes one in a 200
// response body. They exercise the real commands end-to-end (config → client →
// HTTP → render) so that reintroducing machineOut/textsafe.Unwrap on a default
// output path makes the test fail loudly.

// bidiName is a connector name carrying a raw U+202E (RTL override), built from a
// rune so the source stays ASCII-auditable.
var bidiName = "otk" + string(rune(0x202E)) + "adv"

// withConfig points lm at srv by writing an isolated ~/.lm/config.yaml under a
// temp HOME, and returns nothing (HOME is restored by t.Cleanup via t.Setenv).
func withConfig(t *testing.T, srvURL string) {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)
	dir := filepath.Join(home, ".lm")
	// Match production secrecy perms (config.Save: 0700 dir / 0600 file) so the
	// token stays non-world-readable and the setup mirrors real config loading.
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	cfg := "current-context: otk\ncontexts:\n  otk:\n    server: " + srvURL + "\n    token: t\n"
	if err := os.WriteFile(filepath.Join(dir, "config.yaml"), []byte(cfg), 0o600); err != nil {
		t.Fatal(err)
	}
}

// runCmd builds a fresh root, runs the given args through cli.Run (which installs
// the textsafe writers exactly as production does), and returns stdout/stderr/code.
func runCmd(t *testing.T, args ...string) (string, string, int) {
	t.Helper()
	root := NewRootCmd()
	root.SetArgs(args)
	var out, errb strings.Builder
	code := Run(root, &out, &errb)
	return out.String(), errb.String(), code
}

// assertSanitized fails if s carries the raw rune, and requires the escaped form.
func assertSanitized(t *testing.T, where, s string) {
	t.Helper()
	if strings.ContainsRune(s, rune(0x202E)) {
		t.Fatalf("%s reflected a RAW U+202E (sanitization bypassed — machineOut on a default path?): %q", where, s)
	}
	// Require the exact escape textsafe.Writer emits (backslash-u + 4 hex), not
	// just the "202E" substring (which could appear unrelated to escaping).
	want := fmt.Sprintf(`\u%04X`, rune(0x202E))
	if !strings.Contains(s, want) {
		t.Fatalf("%s did not emit the exact escape %q: %q", where, want, s)
	}
}

func TestApplyCommand_sanitizesServerEchoedBidi(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/platform/connectors/apply" {
			w.WriteHeader(200)
			_, _ = w.Write([]byte(`{"connectorId":"c1","name":"` + bidiName + `"}`))
			return
		}
		http.NotFound(w, r)
	}))
	defer srv.Close()
	withConfig(t, srv.URL)

	manifest := filepath.Join(t.TempDir(), "m.yaml")
	if err := os.WriteFile(manifest, []byte("name: x\nconnector_type: csv\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	out, errb, code := runCmd(t, "apply", "-f", manifest)
	if code != 0 {
		t.Fatalf("apply exit=%d, stderr=%q", code, errb)
	}
	assertSanitized(t, "apply stdout", out)
}

func TestSourceRestProbeCommand_sanitizesServerEchoedBidi(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/platform/connectors":
			_, _ = w.Write([]byte(`[{"id":"c1","name":"conn"}]`))
		case r.URL.Path == "/api/platform/sources/rest/probe":
			_, _ = w.Write([]byte(`{"draft":"` + bidiName + `"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	withConfig(t, srv.URL)

	out, errb, code := runCmd(t, "source", "rest", "probe", "conn", "--endpoint", "/x")
	if code != 0 {
		t.Fatalf("probe exit=%d, stderr=%q", code, errb)
	}
	assertSanitized(t, "source rest probe stdout", out)
}

func TestSourceRestTestCommand_sanitizesServerEchoedBidi(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/platform/connectors":
			_, _ = w.Write([]byte(`[{"id":"c1","name":"conn"}]`))
		case strings.Contains(r.URL.Path, "/endpoints/") && strings.HasSuffix(r.URL.Path, "/test"):
			_, _ = w.Write([]byte(`{"report":"` + bidiName + `"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	withConfig(t, srv.URL)

	out, errb, code := runCmd(t, "source", "rest", "test", "conn")
	if code != 0 {
		t.Fatalf("source rest test exit=%d, stderr=%q", code, errb)
	}
	assertSanitized(t, "source rest test stdout", out)
}

func TestSourceRestFromOpenApiCommand_sanitizesDefaultStdout(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/platform/sources/rest/from-openapi" {
			_, _ = w.Write([]byte("connector_type: rest\nname: " + bidiName + "\n"))
			return
		}
		http.NotFound(w, r)
	}))
	defer srv.Close()
	withConfig(t, srv.URL)

	// No --output: the generated manifest is echoed to stdout and must be sanitized.
	out, errb, code := runCmd(t, "source", "rest", "from-openapi", "http://spec", "--name", "x")
	if code != 0 {
		t.Fatalf("from-openapi exit=%d, stderr=%q", code, errb)
	}
	assertSanitized(t, "source rest from-openapi stdout", out)
}
