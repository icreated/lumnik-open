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

func TestViewsList_noScope_getsPlainPathAndDecodesSummaries(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "GET", r.Method)
		assert.Equal(t, "/api/views", r.URL.Path)
		assert.Equal(t, "", r.URL.RawQuery)
		assert.Equal(t, "application/json", r.Header.Get("Accept"))
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[{"name":"gold-desk","scope":"clients","title":"Gold desk"}]`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	got, err := c.ViewsList("")
	require.NoError(t, err)
	assert.Equal(t, []ViewSummary{{Name: "gold-desk", Scope: "clients", Title: "Gold desk"}}, got)
}

func TestViewsList_withScope_addsEscapedScopeQueryParam(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/views", r.URL.Path)
		assert.Equal(t, "clients gold", r.URL.Query().Get("scope"))
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	got, err := c.ViewsList("clients gold")
	require.NoError(t, err)
	assert.Empty(t, got)
}

func TestViewsList_scopeRefusal_surfacesHonestRendering(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(403)
		_, _ = w.Write([]byte(`{"error":"scope not granted"}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	_, err := c.ViewsList("stock")
	require.Error(t, err)
	assert.Equal(t, "access refused (403): scope not granted", err.Error())
}

func TestViewGet_escapesNameAsOnePathSegment(t *testing.T) {
	var gotRequestURI string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotRequestURI = r.RequestURI
		w.Header().Set("Content-Type", "application/yaml")
		_, _ = w.Write([]byte("kind: View\n"))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	_, err := c.ViewGet("week/../sneaky")
	require.NoError(t, err)
	// A free-typed name must never be split into extra path segments (path traversal /
	// routing confusion) — it lands escaped as a single segment.
	assert.Equal(t, "/api/views/week%2F..%2Fsneaky", gotRequestURI)
}

func TestViewGet_returnsManifestYamlBytesVerbatim(t *testing.T) {
	const yaml = "apiVersion: apps.lumnik.io/v1\nkind: View\n"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "GET", r.Method)
		assert.Equal(t, "/api/views/gold-desk", r.URL.Path)
		assert.Equal(t, "application/yaml, application/json;q=0.9", r.Header.Get("Accept"))
		w.Header().Set("Content-Type", "application/yaml")
		_, _ = w.Write([]byte(yaml))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	got, err := c.ViewGet("gold-desk")
	require.NoError(t, err)
	assert.Equal(t, yaml, string(got))
}

func TestViewGet_notFound_surfacesHonestRendering(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(404)
		_, _ = w.Write([]byte(`{"error":"no saved view named 'nope'"}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	_, err := c.ViewGet("nope")
	require.Error(t, err)
	assert.Equal(t, "API error 404: no saved view named 'nope'", err.Error())
}

func TestViewGet_scopeRefusal_surfacesHonestRendering(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(403)
		_, _ = w.Write([]byte(`{"error":"scope not granted"}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	_, err := c.ViewGet("stock-view")
	require.Error(t, err)
	assert.Equal(t, "access refused (403): scope not granted", err.Error())
}

func TestViewApply_postsRawYamlAndDecodesResult(t *testing.T) {
	const yaml = "apiVersion: apps.lumnik.io/v1\nkind: View\nmetadata:\n  name: gold-desk\n"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "POST", r.Method)
		assert.Equal(t, "/api/views/apply", r.URL.Path)
		assert.Equal(t, "application/yaml", r.Header.Get("Content-Type"))
		assert.Equal(t, "application/json", r.Header.Get("Accept"))
		body, _ := io.ReadAll(r.Body)
		assert.Equal(t, yaml, string(body))
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"name":"gold-desk","scope":"clients"}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	got, err := c.ViewApply([]byte(yaml))
	require.NoError(t, err)
	assert.Equal(t, &ViewApplyResult{Name: "gold-desk", Scope: "clients"}, got)
}

func TestViewApply_validationRefusal_surfacesHonestRendering(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(400)
		_, _ = w.Write([]byte(`{"error":"filter column 'nope' is not part of this view"}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	_, err := c.ViewApply([]byte("kind: View\n"))
	require.Error(t, err)
	assert.Equal(t, "API error 400: filter column 'nope' is not part of this view", err.Error())
}
