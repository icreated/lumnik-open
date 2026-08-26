package client

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestViewGenerate_postsScopeAndDescription_returnsYamlVerbatim(t *testing.T) {
	const yaml = "# lm view -f gold-desk.yaml --filter 'segment=GOLD'\napiVersion: apps.lumnik.io/v1\nkind: View\n"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/views/generate", r.URL.Path)
		assert.Equal(t, "application/json", r.Header.Get("Content-Type"))
		// Explicit q-values: header ORDER at equal quality is not spec-guaranteed to steer
		// negotiation — JSON must win for error bodies deterministically.
		assert.Equal(t, "application/json, application/yaml;q=0.9", r.Header.Get("Accept"))
		body, _ := io.ReadAll(r.Body)
		assert.JSONEq(t, `{"scope":"clients","description":"les clients gold"}`, string(body))
		w.Header().Set("Content-Type", "application/yaml")
		_, _ = w.Write([]byte(yaml))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	got, err := c.ViewGenerate("clients", "les clients gold")
	require.NoError(t, err)
	assert.Equal(t, yaml, got)
}

func TestViewGenerate_surfacesTheServerRefusalVerbatim(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(400)
		_, _ = w.Write([]byte(`{"error":"generation abstained: no order data in this scope"}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	_, err := c.ViewGenerate("clients", "les commandes")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "generation abstained")
}

func TestViewGenerate_aTruncatedBodyFailsLoudly(t *testing.T) {
	// The raw body IS the payload here (no JSON decode to catch truncation downstream):
	// a connection dropped mid-body must surface as an error, never as partial YAML + nil.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "1000")
		w.WriteHeader(200)
		_, _ = w.Write([]byte("# partial"))
		// handler returns having written fewer bytes than declared → the server closes the
		// connection and the client's read fails with an unexpected EOF
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	_, err := c.ViewGenerate("clients", "les clients")
	require.Error(t, err)
}
