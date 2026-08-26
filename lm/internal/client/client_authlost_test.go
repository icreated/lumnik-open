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
