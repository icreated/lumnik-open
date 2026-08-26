package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

// lm login needs only server + oidc-issuer in the context: it discovers the
// device/token endpoints from the issuer's .well-known doc and defaults the
// client-id to lm-cli — no hand-copied OIDC endpoints.
func TestLoginDiscoversFromIssuer(t *testing.T) {
	var sawDeviceClientID string
	mux := http.NewServeMux()
	var base string
	mux.HandleFunc("/realms/lumnik/.well-known/openid-configuration", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"device_authorization_endpoint": base + "/dev",
			"token_endpoint":                base + "/tok",
		})
	})
	mux.HandleFunc("/dev", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		sawDeviceClientID = r.FormValue("client_id")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"device_code": "dc", "user_code": "UC-1",
			"verification_uri": "https://idp/verify", "interval": 1, "expires_in": 60,
		})
	})
	mux.HandleFunc("/tok", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"access_token": "AT-123", "refresh_token": "RT-456", "expires_in": 300,
		})
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()
	base = srv.URL

	dir := t.TempDir()
	t.Setenv("HOME", dir)
	_ = os.MkdirAll(filepath.Join(dir, ".lm"), 0o700)
	_ = os.WriteFile(filepath.Join(dir, ".lm", "config.yaml"),
		[]byte("current-context: sh\ncontexts:\n  sh:\n    server: http://hub\n    oidc-issuer: "+srv.URL+"/realms/lumnik\n"),
		0o600)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"login"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("login failed: %v\n%s", err, buf.String())
	}

	if sawDeviceClientID != "lm-cli" {
		t.Errorf("expected default client-id lm-cli, device endpoint saw %q", sawDeviceClientID)
	}
	cfg, _ := config.LoadFromFile(filepath.Join(dir, ".lm", "config.yaml"))
	if got := cfg.Contexts["sh"].Access; got != "AT-123" {
		t.Errorf("access token not stored: %q", got)
	}
	if !strings.Contains(buf.String(), "UC-1") {
		t.Errorf("user code not shown to the user: %s", buf.String())
	}
}

// A device/token endpoint set explicitly in the context must survive discovery —
// login fills only the missing endpoint, never clobbers a manual one.
func TestLoginDoesNotClobberManualEndpoint(t *testing.T) {
	var manualHit bool
	mux := http.NewServeMux()
	var base string
	mux.HandleFunc("/realms/lumnik/.well-known/openid-configuration", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"device_authorization_endpoint": base + "/discovered-dev", // NOT what we want used
			"token_endpoint":                base + "/tok",
		})
	})
	mux.HandleFunc("/manual-dev", func(w http.ResponseWriter, r *http.Request) {
		manualHit = true
		_ = json.NewEncoder(w).Encode(map[string]any{
			"device_code": "dc", "user_code": "UC-2", "verification_uri": "https://idp/v", "interval": 1, "expires_in": 60,
		})
	})
	mux.HandleFunc("/discovered-dev", func(w http.ResponseWriter, r *http.Request) {
		t.Error("discovery clobbered the manually-set device endpoint")
		w.WriteHeader(500)
	})
	mux.HandleFunc("/tok", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "AT", "refresh_token": "RT", "expires_in": 300})
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()
	base = srv.URL

	dir := t.TempDir()
	t.Setenv("HOME", dir)
	_ = os.MkdirAll(filepath.Join(dir, ".lm"), 0o700)
	_ = os.WriteFile(filepath.Join(dir, ".lm", "config.yaml"),
		[]byte("current-context: sh\ncontexts:\n  sh:\n    server: http://hub\n    oidc-issuer: "+srv.URL+"/realms/lumnik\n    oidc-device-endpoint: "+srv.URL+"/manual-dev\n"),
		0o600)

	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"login"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("login failed: %v", err)
	}
	if !manualHit {
		t.Error("the manually-set device endpoint was not used")
	}
}
