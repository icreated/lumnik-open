package client

import (
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestDo_401_staticToken_doesNotSignal(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(401)
	}))
	defer srv.Close()
	var lost int32
	c := New(config.Context{Server: srv.URL, Token: "static"}) // dev/static path
	c.SetAuthLost(func() { atomic.AddInt32(&lost, 1) })
	_ = c.GET("/anything", nil)
	if atomic.LoadInt32(&lost) != 0 {
		t.Fatal("a static-token 401 must NOT signal auth-lost (device-flow can't fix it)")
	}
}

func TestSignalAuthLost_debounced(t *testing.T) {
	var n int32
	c := New(config.Context{Server: "http://x", Access: "A", Refresh: "R", OidcClientID: "lm-cli"})
	c.SetAuthLost(func() { atomic.AddInt32(&n, 1) })
	c.signalAuthLost()
	c.signalAuthLost()
	c.signalAuthLost()
	if got := atomic.LoadInt32(&n); got != 1 {
		t.Fatalf("auth-lost must debounce: fired %d times, want 1", got)
	}
}

func TestDo_401_signalsAuthLost(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(401)
	}))
	defer srv.Close()
	var lost int32
	// OIDC context: a 401 on an authenticated session must signal auth-lost.
	c := New(config.Context{Server: srv.URL, Access: "AT",
		ExpiresAt: 1 << 62, OidcClientID: "lm-cli"})
	c.SetAuthLost(func() { atomic.AddInt32(&lost, 1) })
	_ = c.GET("/anything", nil)
	if atomic.LoadInt32(&lost) != 1 {
		t.Fatal("a 401 on an OIDC session must signal auth-lost")
	}
}

// #1610: a dev-bypass hub (make k8s-up) ignores the bearer and answers "no tenant" — a login cannot fix that, so the
// TUI must not open its re-login modal: the identity the hub reads is the context's tenant/user.
func TestDo_401_noTenant_onAnOidcSession_doesNotSignal(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(401)
		_, _ = w.Write([]byte(`{"error":"no tenant in security context"}`))
	}))
	defer srv.Close()
	var lost int32
	c := New(config.Context{Server: srv.URL, Access: "AT", ExpiresAt: 1 << 62, OidcClientID: "lm-cli"})
	c.SetAuthLost(func() { atomic.AddInt32(&lost, 1) })
	_ = c.GET("/anything", nil)
	if atomic.LoadInt32(&lost) != 0 {
		t.Fatal("a 'no tenant' 401 must NOT signal auth-lost: re-login cannot give a dev-bypass hub a tenant")
	}
}

type roundTrip func(*http.Request) (*http.Response, error)

func (f roundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

// #1610 (review of #1614): whatever path built the client, the dev-bypass identity never leaves for a remote https hub.
func TestAuthorize_neverSendsTheIdentityToARemoteHttpsHub(t *testing.T) {
	var got http.Header
	c := New(config.Context{Server: "https://hub.example", Token: "t", Tenant: "1", User: "1"})
	c.http = &http.Client{Transport: roundTrip(func(r *http.Request) (*http.Response, error) {
		got = r.Header.Clone()
		return &http.Response{StatusCode: 200, Body: http.NoBody, Header: http.Header{}}, nil
	})}
	_ = c.GET("/anything", nil)
	if got.Get("X-Tenant-ID") != "" || got.Get("X-User-ID") != "" {
		t.Fatalf("identity sent to a remote https hub: %v", got)
	}
	if got.Get("Authorization") == "" {
		t.Fatal("the request itself must still go, signed")
	}
}
