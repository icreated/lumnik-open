package cli

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

// An explicit --token is an override, not a hint.
//
// The stored context wins over the config file's static `token:` on purpose — a stale token left in
// a file must never shadow a live `lm login`. But `--token` is not a file value: someone typed it,
// now, for this one command. It was still losing to the stored (expired) access token, so every
// call ran the refresh first and died on it before the override was ever used:
//
//	lm --token "$TOK" inbox
//	Error: auth: refresh failed (400): {"error":"invalid_grant",
//	       "error_description":"Token is not active"} (run 'lm login')
//
// Mordu twice in the docs campaigns; the known workaround was a throwaway HOME holding nothing but
// `server` + `token`, which is exactly the shape this makes unnecessary (#491).

// tokenTestRig is a hub that records what it was sent and a token endpoint that counts its callers.
type tokenTestRig struct {
	hub        *httptest.Server
	tokenSrv   *httptest.Server
	gotAuth    atomic.Value // string
	refreshes  atomic.Int32
	configPath string
}

// expiredAccess / futureExpiry pick whether the STORED session is usable: an expired one
// forces the refresh (the #491 case), a live one lets a request reach the hub so its status
// can be asserted on.
const (
	expiredAccess = -time.Hour
	futureExpiry  = time.Hour
)

func newTokenTestRig(t *testing.T) *tokenTestRig {
	t.Helper()
	return newTokenTestRigReturning(t, http.StatusOK, expiredAccess)
}

func newTokenTestRigReturning(t *testing.T, status int, accessLife time.Duration) *tokenTestRig {
	t.Helper()
	r := &tokenTestRig{}
	r.gotAuth.Store("")

	r.hub = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		r.gotAuth.Store(req.Header.Get("Authorization"))
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		fmt.Fprint(w, `{}`)
	}))
	t.Cleanup(r.hub.Close)

	// The refresh the stored context would attempt — and it fails, the way an expired session does.
	r.tokenSrv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		r.refreshes.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		fmt.Fprint(w, `{"error":"invalid_grant","error_description":"Token is not active"}`)
	}))
	t.Cleanup(r.tokenSrv.Close)

	expired := time.Now().Add(accessLife).Unix()
	cfg := fmt.Sprintf(`current-context: local
contexts:
  local:
    server: %s
    access: STORED-AND-EXPIRED
    refresh: STORED-REFRESH
    expires-at: %d
    oidc-client-id: lm
    oidc-token-endpoint: %s
`, r.hub.URL, expired, r.tokenSrv.URL)
	// writeConfig (config_test.go) already does TempDir + HOME + .lm + 0o600.
	r.configPath = writeConfig(t, cfg)
	return r
}

func TestExplicitToken_IsUsedWithoutRunningTheRefresh(t *testing.T) {
	rig := newTokenTestRig(t)

	root := NewRootCmd()
	// ParseFlags, not PersistentFlags().Set: cobra merges the persistent set into
	// cmd.Flags() at parse time, and ClientFromCmd reads cmd.Flags() — setting the
	// persistent flag directly leaves the value invisible to the code under test.
	if err := root.ParseFlags([]string{"--token", "OVERRIDE"}); err != nil {
		t.Fatal(err)
	}
	c, err := ClientFromCmd(root)
	if err != nil {
		t.Fatal(err)
	}

	var out map[string]any
	if err := c.GET("/api/platform/whoami", &out); err != nil {
		t.Fatalf("the call must succeed on the override alone: %v", err)
	}
	if got := rig.gotAuth.Load().(string); got != "Bearer OVERRIDE" {
		t.Errorf("hub saw %q, want %q", got, "Bearer OVERRIDE")
	}
	if n := rig.refreshes.Load(); n != 0 {
		t.Errorf("the token endpoint was called %d time(s); an explicit --token must not refresh", n)
	}
}

// The control. Without it, the test above proves only that the rig is inert — the same trap
// LogProbe's javadoc names on the Java side: assert the failure you expect before trusting its
// absence.
func TestWithoutExplicitToken_TheStoredContextStillRefreshesAndFails(t *testing.T) {
	rig := newTokenTestRig(t)

	c, err := ClientFromCmd(NewRootCmd())
	if err != nil {
		t.Fatal(err)
	}

	var out map[string]any
	if err := c.GET("/api/platform/whoami", &out); err == nil {
		t.Fatal("an expired context with a dead refresh must still fail — that policy is unchanged")
	}
	if n := rig.refreshes.Load(); n == 0 {
		t.Error("the rig never reached the token endpoint, so the test above proves nothing")
	}
}

// ReloadContext re-reads the auth fields from disk after a re-login. It must not use that to put
// the stored access token back in front of a token the operator typed on the command line — the
// same defect, one method further along.
func TestExplicitToken_SurvivesAReloadContext(t *testing.T) {
	rig := newTokenTestRig(t)

	root := NewRootCmd()
	// ParseFlags, not PersistentFlags().Set: cobra merges the persistent set into
	// cmd.Flags() at parse time, and ClientFromCmd reads cmd.Flags() — setting the
	// persistent flag directly leaves the value invisible to the code under test.
	if err := root.ParseFlags([]string{"--token", "OVERRIDE"}); err != nil {
		t.Fatal(err)
	}
	c, err := ClientFromCmd(root)
	if err != nil {
		t.Fatal(err)
	}

	c.ReloadContext()

	var out map[string]any
	if err := c.GET("/api/platform/whoami", &out); err != nil {
		t.Fatalf("after a reload the override must still be the token: %v", err)
	}
	if got := rig.gotAuth.Load().(string); got != "Bearer OVERRIDE" {
		t.Errorf("hub saw %q after ReloadContext, want %q", got, "Bearer OVERRIDE")
	}
	if n := rig.refreshes.Load(); n != 0 {
		t.Errorf("the token endpoint was called %d time(s) after a reload", n)
	}
}

// A 401 on a --token request must not raise the re-login modal.
//
// do() decides whether a 401 means "your SESSION died, re-login can fix it" from
// `c.ctx.Access != ""`, and its own comment states the rule: "A static-token 401 is NOT
// signalled (device-flow login can't help it)." That predicate was accurate only because a
// context holding a stored access token ALWAYS took the OIDC path — which is exactly the
// confinement #491 removed. With --token the credential on the wire is static while
// ctx.Access is still set, so the flag and the request disagreed: a 401 raised a modal
// offering to re-login a session that was never in play. (Copilot round 1.)
func TestExplicitToken_A401DoesNotSignalAuthLost(t *testing.T) {
	_ = newTokenTestRigReturning(t, http.StatusUnauthorized, futureExpiry)

	root := NewRootCmd()
	if err := root.ParseFlags([]string{"--token", "OVERRIDE"}); err != nil {
		t.Fatal(err)
	}
	c, err := ClientFromCmd(root)
	if err != nil {
		t.Fatal(err)
	}
	var lost bool
	c.SetAuthLost(func() { lost = true })

	var out map[string]any
	if err := c.GET("/api/platform/whoami", &out); err == nil {
		t.Fatal("a 401 must still surface as an error")
	}
	if lost {
		t.Error("a static-token 401 must not offer to re-login a session that is not in play")
	}
}

// The control: the same 401, on a live OIDC session, DOES signal — that behaviour is
// unchanged, and without this row the test above would pass against a client that never
// signals anything.
func TestWithoutExplicitToken_A401OnALiveSessionStillSignalsAuthLost(t *testing.T) {
	_ = newTokenTestRigReturning(t, http.StatusUnauthorized, futureExpiry)

	c, err := ClientFromCmd(NewRootCmd())
	if err != nil {
		t.Fatal(err)
	}
	var lost bool
	c.SetAuthLost(func() { lost = true })

	var out map[string]any
	if err := c.GET("/api/platform/whoami", &out); err == nil {
		t.Fatal("a 401 must surface as an error")
	}
	if !lost {
		t.Error("an OIDC session rejected by the server must still raise the re-login signal")
	}
}
