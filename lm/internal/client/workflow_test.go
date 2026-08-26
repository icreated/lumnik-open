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

func TestApplyWorkflow_postsYaml(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/workflows/apply", r.URL.Path)
		assert.Equal(t, "application/yaml", r.Header.Get("Content-Type"))
		body, _ := io.ReadAll(r.Body)
		assert.Contains(t, string(body), "kind: Workflow")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"name":"commande-lifecycle","table":"connector.t_commandes_csv","column":"statut"}`))
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})
	raw, err := c.ApplyWorkflow([]byte("kind: Workflow\n"))
	require.NoError(t, err)
	assert.Contains(t, string(raw), "commande-lifecycle")
}

func TestListWorkflows_returnsBody(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/workflows", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[{"name":"commande-lifecycle","table":"connector.t_commandes_csv","column":"statut","scope":"magasin","enabled":true}]`))
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})
	raw, err := c.ListWorkflows()
	require.NoError(t, err)
	assert.Contains(t, string(raw), "commande-lifecycle")
}

func TestGetWorkflowManifest_urlEscapesName(t *testing.T) {
	const yaml = "apiVersion: apps.lumnik.io/v1\nkind: Workflow\nmetadata:\n  name: a/b c\n"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/workflows/a%2Fb%20c/manifest", r.URL.EscapedPath())
		w.Header().Set("Content-Type", "application/yaml")
		_, _ = w.Write([]byte(yaml))
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})
	data, err := c.GetWorkflowManifest("a/b c")
	require.NoError(t, err)
	assert.Equal(t, yaml, string(data))
}
