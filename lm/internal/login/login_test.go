package login

import (
	"bytes"
	"context"
	"net/http"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/textsafe"
)

func TestRun_missingOidcConfig_isClearError(t *testing.T) {
	cfg := &config.Config{Contexts: map[string]config.Context{
		"c": {Server: "http://x"},
	}}
	err := Run(context.Background(), http.DefaultClient, cfg, "/dev/null", "c", &bytes.Buffer{})
	if err == nil {
		t.Fatal("expected an error when the context has no OIDC config")
	}
	if !strings.Contains(strings.ToLower(err.Error()), "oidc") {
		t.Errorf("error should mention OIDC config, got: %v", err)
	}
}

func TestRun_unknownContext_isError(t *testing.T) {
	cfg := &config.Config{Contexts: map[string]config.Context{}}
	if err := Run(context.Background(), http.DefaultClient, cfg, "/dev/null", "nope", &bytes.Buffer{}); err == nil {
		t.Fatal("expected an error for an unknown context")
	}
}

// The device flow is the closest thing lm has to a login page: the wordmark belongs there, above
// the two things a human must actually do.
func TestPrompt_showsTheWordmarkThenTheInstructions(t *testing.T) {
	var out bytes.Buffer
	prompt(&out, "https://kc.test/device?user_code=WXYZ-1234", "WXYZ-1234")

	s := out.String()
	for _, want := range []string{"██", "lumnik 2026", "https://kc.test/device", "WXYZ-1234", "Waiting"} {
		if !strings.Contains(s, want) {
			t.Errorf("the login prompt must contain %q, got:\n%s", want, s)
		}
	}
	if strings.Index(s, "██") > strings.Index(s, "To sign in") {
		t.Error("the wordmark comes first — it is the header of the page, not a footer")
	}
}

// lm wraps stdout in textsafe.Writer, which escapes control runes — and ESC is one. The wordmark's
// colour is ours, a compile-time constant, so it goes to the raw writer; anything the server said
// keeps the guard. These two facts have to be asserted together, or "fixing" one breaks the other.
func TestPrompt_goldReachesTheTerminalButServerTextStaysGuarded(t *testing.T) {
	var raw bytes.Buffer
	prompt(textsafe.NewWriter(&raw), "https://kc.test/device", "AB"+string(rune(0x202E))+"CD")

	s := raw.String()
	if !strings.ContainsRune(s, 0x1B) {
		t.Errorf("the gold must survive the SafeWriter, got:\n%q", s)
	}
	if strings.ContainsRune(s, 0x202E) {
		t.Errorf("an RTL override from the server must not reach the terminal, got:\n%q", s)
	}
	if !strings.Contains(s, "202E") {
		t.Errorf("it should arrive escaped and visible, got:\n%q", s)
	}
}

func TestPrompt_honorsNoColor(t *testing.T) {
	t.Setenv("NO_COLOR", "1")
	var raw bytes.Buffer
	prompt(textsafe.NewWriter(&raw), "https://kc.test/device", "WXYZ-1234")
	if strings.ContainsRune(raw.String(), 0x1B) {
		t.Errorf("NO_COLOR=1 must reach the login page too, got:\n%q", raw.String())
	}
}

// The device flow's token request leaves this machine over plain http only on an allowed context (#2004).
func TestRun_clearTextIdentityProvider_refused(t *testing.T) {
	t.Setenv("LUMNIK_ALLOW_HTTP", "")
	cfg := &config.Config{Contexts: map[string]config.Context{
		"lan": {Server: "https://hub", OidcDeviceEndpoint: "http://192.0.2.1/dev", OidcTokenEndpoint: "http://192.0.2.1/tok"},
	}}
	err := Run(context.Background(), http.DefaultClient, cfg, "/dev/null", "lan", &bytes.Buffer{})
	if err == nil || !strings.Contains(err.Error(), "lm config set-context lan --allow-insecure-http") {
		t.Fatalf("err = %v", err)
	}
}

// The login client follows a redirect on the same origin only (#2004).
func TestHTTPClient_refusesCrossOriginRedirect(t *testing.T) {
	if HTTPClient(config.Context{}).CheckRedirect == nil {
		t.Fatal("no CheckRedirect on the login client")
	}
}

// #2004 review: insecure-skip-verify is said before the first request to the identity provider, even on a failed login.
func TestRun_insecureSkipVerify_warnsBeforeTheFirstRequest(t *testing.T) {
	var warn bytes.Buffer
	prev := client.WarnOut
	client.WarnOut = &warn
	t.Cleanup(func() { client.WarnOut = prev })
	cfg := &config.Config{Contexts: map[string]config.Context{
		"lab": {Server: "https://hub.test", OidcIssuer: "https://127.0.0.1:1/realms/x", InsecureSkipVerify: true},
	}}
	if err := Run(context.Background(), http.DefaultClient, cfg, "/dev/null", "lab", &bytes.Buffer{}); err == nil {
		t.Fatal("login against nothing succeeded")
	}
	if !strings.Contains(warn.String(), "insecure-skip-verify is on for context 'lab'") {
		t.Errorf("no warning before the failed login: %q", warn.String())
	}
}
