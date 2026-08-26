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

func TestProcessApply_postsYaml(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/processes/apply", r.URL.Path)
		assert.Equal(t, "application/yaml", r.Header.Get("Content-Type"))
		body, _ := io.ReadAll(r.Body)
		assert.Contains(t, string(body), "kind: Process")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"name":"gold-arrive","table":"connector.t_cust_csv","emit":"decision.gold-arrive"}`))
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})
	raw, err := c.ProcessApply([]byte("kind: Process\n"))
	require.NoError(t, err)
	assert.Contains(t, string(raw), "gold-arrive")
}

func TestProcessManifest_escapesName(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/processes/gold%20arrive%2Fv2/manifest", r.URL.EscapedPath())
		w.Header().Set("Content-Type", "application/yaml")
		_, _ = w.Write([]byte("kind: Process\n"))
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})
	raw, err := c.ProcessManifest("gold arrive/v2")
	require.NoError(t, err)
	assert.Contains(t, raw, "kind: Process")
}

func TestProcessList_parses(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/processes", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[{"name":"gold-arrive","table":"connector.t_cust_csv","emit":"decision.gold-arrive","enabled":true}]`))
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})
	ps, err := c.ProcessList()
	require.NoError(t, err)
	require.Len(t, ps, 1)
	assert.Equal(t, ProcessInfo{Name: "gold-arrive", Table: "connector.t_cust_csv",
		Emit: "decision.gold-arrive", Enabled: true}, ps[0])
}
