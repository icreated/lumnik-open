package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestClient_GET_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "Bearer tok", r.Header.Get("Authorization"))
		assert.Equal(t, "/api/platform/version", r.URL.Path)
		_ = json.NewEncoder(w).Encode(map[string]string{"version": "0.1.0"})
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "tok"})
	var out map[string]string
	require.NoError(t, c.GET("/api/platform/version", &out))
	assert.Equal(t, "0.1.0", out["version"])
}

func TestClient_GET_4xx_ReturnsAPIError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(404)
		_, _ = w.Write([]byte(`{"error":"not found"}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	err := c.GET("/missing", nil)
	require.Error(t, err)
	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, 404, apiErr.Status)
}

func TestClient_POST_Body(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var got map[string]string
		_ = json.NewDecoder(r.Body).Decode(&got)
		assert.Equal(t, "hello", got["msg"])
		_ = json.NewEncoder(w).Encode(map[string]string{"echo": got["msg"]})
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	var out map[string]string
	require.NoError(t, c.POST("/echo", map[string]string{"msg": "hello"}, &out))
	assert.Equal(t, "hello", out["echo"])
}
