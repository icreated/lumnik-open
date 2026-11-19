package cli

import (
	"bytes"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// #1997: `lm token` prints an access token of the `lm login` session, renewed when it is about to expire, so a
// script or a curl recipe never needs the password grant.
func tokenRig(t *testing.T, expiresAt int64, answer func(w http.ResponseWriter)) (*atomic.Int32, string) {
	t.Helper()
	t.Setenv("LUMNIK_TOKEN", "")
	var refreshes atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		refreshes.Add(1)
		if r.FormValue("grant_type") != "refresh_token" || r.FormValue("refresh_token") != "STORED-REFRESH" {
			t.Errorf("unexpected token request: %v", r.Form)
		}
		w.Header().Set("Content-Type", "application/json")
		answer(w)
	}))
	t.Cleanup(srv.Close)
	path := writeConfig(t, fmt.Sprintf(`current-context: local
contexts:
  local:
    server: http://hub.test
    access: STORED-ACCESS
    refresh: STORED-REFRESH
    expires-at: %d
    oidc-client-id: lm-cli
    oidc-token-endpoint: %s
`, expiresAt, srv.URL))
	return &refreshes, path
}

func runToken(t *testing.T) (string, string, error) {
	t.Helper()
	cmd := NewRootCmd()
	out, errOut := new(bytes.Buffer), new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(errOut)
	cmd.SetArgs([]string{"token"})
	err := cmd.Execute()
	return out.String(), errOut.String(), err
}

func TestToken_aFreshTokenIsPrintedWithoutARequest(t *testing.T) {
	refreshes, _ := tokenRig(t, time.Now().Add(4*time.Minute).Unix(), func(w http.ResponseWriter) {})
	out, _, err := runToken(t)
	if err != nil {
		t.Fatal(err)
	}
	if out != "STORED-ACCESS\n" {
		t.Errorf("stdout = %q, want the token alone", out)
	}
	if refreshes.Load() != 0 {
		t.Errorf("a fresh token must not be renewed")
	}
}

func TestToken_anExpiringTokenIsRenewedPrintedAndKept(t *testing.T) {
	refreshes, path := tokenRig(t, time.Now().Add(10*time.Second).Unix(), func(w http.ResponseWriter) {
		fmt.Fprint(w, `{"access_token":"NEW-ACCESS","refresh_token":"NEW-REFRESH","expires_in":300}`)
	})
	out, _, err := runToken(t)
	if err != nil {
		t.Fatal(err)
	}
	if out != "NEW-ACCESS\n" {
		t.Errorf("stdout = %q, want the renewed token alone", out)
	}
	if refreshes.Load() != 1 {
		t.Errorf("refreshes = %d, want 1", refreshes.Load())
	}
	saved, _ := os.ReadFile(path)
	if !strings.Contains(string(saved), "NEW-REFRESH") {
		t.Errorf("the renewed session must be kept for the next command:\n%s", saved)
	}
}

func TestToken_aDeadSessionSaysLoginAndPrintsNothing(t *testing.T) {
	_, _ = tokenRig(t, time.Now().Add(-time.Minute).Unix(), func(w http.ResponseWriter) {
		w.WriteHeader(http.StatusBadRequest)
		fmt.Fprint(w, `{"error":"invalid_grant"}`)
	})
	out, _, err := runToken(t)
	if err == nil || !strings.Contains(err.Error(), "lm login") {
		t.Fatalf("err = %v, want one that says to run lm login", err)
	}
	if out != "" {
		t.Errorf("stdout = %q, want nothing a script could take for a token", out)
	}
}

func TestToken_freshRenewsAStillValidTokenForALongRun(t *testing.T) {
	refreshes, _ := tokenRig(t, time.Now().Add(4*time.Minute).Unix(), func(w http.ResponseWriter) {
		fmt.Fprint(w, `{"access_token":"NEW-ACCESS","refresh_token":"NEW-REFRESH","expires_in":300}`)
	})
	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"token", "--fresh"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if out.String() != "NEW-ACCESS\n" || refreshes.Load() != 1 {
		t.Errorf("stdout = %q after %d refresh(es); --fresh must renew a still-valid token once", out.String(), refreshes.Load())
	}
}
