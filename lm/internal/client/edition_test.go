package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestClient_Edition_Open(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/bff/v1/me", r.URL.Path)
		_ = json.NewEncoder(w).Encode(map[string]string{"edition": "open"})
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "tok"})
	edition, err := c.Edition()
	require.NoError(t, err)
	assert.Equal(t, "open", edition)
}

func TestClient_Edition_Commercial(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]string{"edition": "commercial"})
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "tok"})
	edition, err := c.Edition()
	require.NoError(t, err)
	assert.Equal(t, "commercial", edition)
}

func TestClient_Edition_ErrorResponse(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "tok"})
	_, err := c.Edition()
	assert.Error(t, err)
}

// Edition() is a cosmetic, best-effort enrichment during login — it must not ride the full 30s
// c.http budget when the hub is slow or unreachable, or a login (and a suspended TUI re-login)
// would hang for up to 30s waiting on a fallback message. Shrinks the budget to milliseconds and
// serves slower than it, the same technique TestModelBoundCallsSurviveSlowServer uses in reverse.
func TestClient_Edition_DoesNotRideTheFullBudgetOnASlowServer(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(80 * time.Millisecond)
		_ = json.NewEncoder(w).Encode(map[string]string{"edition": "open"})
	}))
	defer srv.Close()

	orig := editionTimeout
	editionTimeout = 20 * time.Millisecond
	defer func() { editionTimeout = orig }()

	c := New(config.Context{Server: srv.URL, Token: "tok"})
	start := time.Now()
	_, err := c.Edition()
	elapsed := time.Since(start)

	assert.Error(t, err, "the short edition budget should abort before the slow server responds")
	assert.Less(t, elapsed, 60*time.Millisecond,
		"Edition() must abort at its own short budget, not ride the slow server")
}
