package client

import (
	"bytes"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

// hub answers 200 and records the Authorization header of the last request.
func hub(t *testing.T) (*httptest.Server, *atomic.Value) {
	t.Helper()
	var got atomic.Value
	got.Store("")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got.Store(r.Header.Get("Authorization"))
		_, _ = w.Write([]byte(`{}`))
	}))
	t.Cleanup(srv.Close)
	return srv, &got
}

// overridden resolves a context stored with server stored, then points it at server — what LUMNIK_URL or
// --server does.
func overridden(t *testing.T, stored, server string, ctx config.Context) config.Context {
	t.Helper()
	t.Setenv("LUMNIK_URL", "")
	t.Setenv("LUMNIK_TOKEN", "")
	ctx.Server = stored
	cfg := &config.Config{Contexts: map[string]config.Context{"local": ctx}}
	out, err := cfg.Resolve("local")
	if err != nil {
		t.Fatal(err)
	}
	out.Server = server
	return out
}

func captureWarnings(t *testing.T) *bytes.Buffer {
	t.Helper()
	var buf bytes.Buffer
	prev := WarnOut
	WarnOut = &buf
	t.Cleanup(func() { WarnOut = prev })
	return &buf
}

var session = config.Context{Token: "STORED", Access: "ACCESS", ExpiresAt: time.Now().Add(time.Hour).Unix()}

func TestForeignServer_sameOriginOtherForm_sendsSession(t *testing.T) {
	srv, got := hub(t)
	port := srv.URL[strings.LastIndex(srv.URL, ":"):]
	c := NewForContext("local", overridden(t, "http://LOCALHOST"+port+"/", "http://localhost"+port, session))
	if err := c.GET("/x", nil); err != nil {
		t.Fatal(err)
	}
	if got.Load() != "Bearer ACCESS" {
		t.Errorf("Authorization = %q", got.Load())
	}
}

func TestForeignServer_otherPort_withholdsSession(t *testing.T) {
	warn := captureWarnings(t)
	srv, got := hub(t)
	c := NewForContext("local", overridden(t, "http://127.0.0.1:1", srv.URL, session))
	for i := 0; i < 2; i++ {
		if err := c.GET("/x", nil); err != nil {
			t.Fatal(err)
		}
	}
	if got.Load() != "" {
		t.Errorf("stored session sent to another hub: %q", got.Load())
	}
	want := "not sending the token of context 'local' (http://127.0.0.1:1) to " + srv.URL
	if strings.Count(warn.String(), want) != 1 {
		t.Errorf("want the warning once, got %q", warn.String())
	}
}

func TestForeignServer_otherScheme_withholdsSession(t *testing.T) {
	captureWarnings(t)
	srv, got := hub(t)
	c := NewForContext("local", overridden(t, "https"+strings.TrimPrefix(srv.URL, "http"), srv.URL, session))
	if err := c.GET("/x", nil); err != nil {
		t.Fatal(err)
	}
	if got.Load() != "" {
		t.Errorf("stored session sent over another scheme: %q", got.Load())
	}
}

func TestForeignServer_explicitToken_sent(t *testing.T) {
	srv, got := hub(t)
	ctx := overridden(t, "http://127.0.0.1:1", srv.URL, session)
	ctx.Token = "EXPLICIT"
	c := NewForContext("local", ctx)
	c.PinStaticToken()
	if err := c.GET("/x", nil); err != nil {
		t.Fatal(err)
	}
	if got.Load() != "Bearer EXPLICIT" {
		t.Errorf("Authorization = %q", got.Load())
	}
}

func TestForeignServer_neverRefreshesNorPersists(t *testing.T) {
	captureWarnings(t)
	home := t.TempDir()
	t.Setenv("HOME", home)
	var hits atomic.Int32
	idp := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		_, _ = w.Write([]byte(`{"access_token":"NEW","refresh_token":"NEWR","expires_in":300}`))
	}))
	defer idp.Close()
	stored := config.Context{Server: "http://127.0.0.1:1", Access: "OLD", Refresh: "R", ExpiresAt: 1,
		OidcTokenEndpoint: idp.URL, OidcClientID: "lm-cli"}
	path := filepath.Join(home, ".lm", "config.yaml")
	if err := (&config.Config{CurrentContext: "local", Contexts: map[string]config.Context{"local": stored}}).Save(path); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(path)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()
	c := NewForContext("local", overridden(t, stored.Server, srv.URL, stored))
	err := c.GET("/x", nil)
	if err == nil || !strings.Contains(err.Error(), "not sending the token of context 'local'") {
		t.Errorf("401 does not repeat why the token was withheld: %v", err)
	}
	var api *APIError
	if !errors.As(err, &api) || api.Status != 401 {
		t.Errorf("the APIError is lost: %v", err)
	}
	if hits.Load() != 0 {
		t.Errorf("the context's identity provider was called %d times", hits.Load())
	}
	if after, _ := os.ReadFile(path); !bytes.Equal(before, after) {
		t.Errorf("the context was rewritten:\n%s", after)
	}
}

// A credential never leaves this machine over plain http, unless the context allows it (#2004).
func TestClearText_tokenRefused(t *testing.T) {
	c := NewForContext("lan", config.Context{Server: "http://192.0.2.1:8080", Token: "T"})
	req := httptest.NewRequest("GET", "http://192.0.2.1:8080/x", nil)
	err := c.authorize(req)
	if err == nil || !strings.Contains(err.Error(), "lm config set-context lan --allow-insecure-http") {
		t.Fatalf("err = %v", err)
	}
	if req.Header.Get("Authorization") != "" {
		t.Error("the header was set anyway")
	}
}

func TestClearText_noCredential_notRefused(t *testing.T) {
	c := NewForContext("lan", config.Context{Server: "http://192.0.2.1:8080"})
	if err := c.authorize(httptest.NewRequest("GET", "http://192.0.2.1:8080/x", nil)); err != nil {
		t.Errorf("a request without a credential was refused: %v", err)
	}
}

func TestClearText_allowedContext_sent(t *testing.T) {
	c := NewForContext("lan", config.Context{Server: "http://192.0.2.1:8080", Token: "T", AllowInsecureHTTP: true})
	req := httptest.NewRequest("GET", "http://192.0.2.1:8080/x", nil)
	if err := c.authorize(req); err != nil || req.Header.Get("Authorization") != "Bearer T" {
		t.Errorf("err %v, header %q", err, req.Header.Get("Authorization"))
	}
}

func TestClearText_refreshTokenToIdentityProviderRefused(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	c := NewForContext("lan", config.Context{Server: "https://hub.example", Access: "A", Refresh: "R", ExpiresAt: 1,
		OidcTokenEndpoint: "http://192.0.2.1/realms/x/token", OidcClientID: "lm-cli"})
	_, err := c.Token(false)
	if err == nil || !strings.Contains(err.Error(), "refusing to send your refresh token to http://192.0.2.1") {
		t.Fatalf("err = %v", err)
	}
}

// A redirect that changes scheme or host:port is not followed (#2004).
func TestRedirect_toAnotherOrigin_refused(t *testing.T) {
	var hits atomic.Int32
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1) }))
	defer other.Close()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL+"/x", http.StatusTemporaryRedirect)
	}))
	defer srv.Close()
	c := NewForContext("local", config.Context{Server: srv.URL, Token: "T"})
	if err := c.POST("/x", map[string]string{"a": "b"}, nil); err == nil || !strings.Contains(err.Error(), "redirect") {
		t.Errorf("err = %v", err)
	}
	if hits.Load() != 0 {
		t.Error("the redirect was followed")
	}
}

func TestRedirect_sameOrigin_followed(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/a" {
			http.Redirect(w, r, "/b", http.StatusFound)
			return
		}
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()
	if err := NewForContext("local", config.Context{Server: srv.URL}).GET("/a", nil); err != nil {
		t.Error(err)
	}
}

func TestInsecureSkipVerify_warns(t *testing.T) {
	warn := captureWarnings(t)
	NewForContext("lab", config.Context{Server: "https://hub", InsecureSkipVerify: true})
	if !strings.Contains(warn.String(), "insecure-skip-verify is on for context 'lab': the hub's and Keycloak's certificates are not checked") {
		t.Errorf("warning = %q", warn.String())
	}
}

// #2004 review: a URL may carry credentials (https://ops:s3cr3t@hub); the warning and the 401 name origins only.
func TestForeignServer_messageNamesOriginsOnly(t *testing.T) {
	warn := captureWarnings(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(401) }))
	t.Cleanup(srv.Close)
	effective := strings.Replace(srv.URL, "http://", "http://u:hunter2@", 1) + "/api?k=v"
	c := NewForContext("local", overridden(t, "http://ops:s3cr3t@127.0.0.1:1/base", effective, session))
	err := c.GET("/x", nil)
	for _, s := range []string{warn.String(), fmt.Sprint(err)} {
		if strings.Contains(s, "s3cr3t") || strings.Contains(s, "hunter2") || strings.Contains(s, "k=v") || strings.Contains(s, "/base") {
			t.Errorf("credentials or path leaked: %q", s)
		}
		if !strings.Contains(s, "http://127.0.0.1:1") {
			t.Errorf("stored origin missing: %q", s)
		}
	}
}
