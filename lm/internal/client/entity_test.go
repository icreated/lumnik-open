package client

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestEntityApply_success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/api/entities/apply", r.URL.Path)
		assert.Equal(t, "application/yaml", r.Header.Get("Content-Type"))
		b, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.NotEmpty(t, b)
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `{"name":"Customer","view":"connector.\"v_customer\""}`)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	result, err := c.EntityApply([]byte("kind: Entity\nname: Customer\n"))
	require.NoError(t, err)
	var out map[string]string
	require.NoError(t, json.Unmarshal(result, &out))
	assert.Equal(t, "Customer", out["name"])
}

func TestEntityApply_headersForwarded(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "Bearer tok", r.Header.Get("Authorization"))
		assert.Equal(t, "acme", r.Header.Get("X-Tenant-ID"))
		assert.Equal(t, "u42", r.Header.Get("X-User-ID"))
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `{"name":"T","view":"v"}`)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "tok", Tenant: "acme", User: "u42"})
	_, err := c.EntityApply([]byte("kind: Entity\nname: T\n"))
	require.NoError(t, err)
}

func TestEntityApply_400(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(400)
		_, _ = fmt.Fprint(w, `{"error":"kind must be Entity"}`)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	_, err := c.EntityApply([]byte("kind: Connector"))
	require.Error(t, err)
	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, 400, apiErr.Status)
}
