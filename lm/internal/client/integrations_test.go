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

func TestIntegrationCreateListDelete_platformPaths(t *testing.T) {
	var paths []string
	var createdBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.Method+" "+r.URL.RequestURI())
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == "POST" && r.URL.Path == "/api/platform/integrations":
			_ = json.NewDecoder(r.Body).Decode(&createdBody)
			w.WriteHeader(201)
			_, _ = w.Write([]byte(`{"id":7,"name":"n8n","targetUrl":"https://n8n.local/wh","eventTypes":["decision.*"],"enabled":true}`))
		case r.Method == "GET" && r.URL.Path == "/api/platform/integrations":
			_, _ = w.Write([]byte(`[{"id":7,"name":"n8n","targetUrl":"https://n8n.local/wh","eventTypes":["decision.*"],"enabled":true}]`))
		case r.Method == "DELETE":
			w.WriteHeader(204)
		case r.Method == "POST": // test-fire
			_, _ = w.Write([]byte(`{"ok":true,"httpStatus":200,"durationMs":12}`))
		}
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "static"})
	created, err := c.IntegrationCreate(IntegrationCreate{
		Name: "n8n", TargetURL: "https://n8n.local/wh", EventTypes: []string{"decision.*"}, Secret: "s3cret"})
	require.NoError(t, err)
	assert.Equal(t, int64(7), created.ID)
	assert.Equal(t, "s3cret", createdBody["secret"], "the secret travels in the body, never the URL")

	list, err := c.IntegrationList()
	require.NoError(t, err)
	require.Len(t, list, 1)
	assert.Equal(t, "n8n", list[0].Name)

	res, err := c.IntegrationTest(7, "org.created")
	require.NoError(t, err)
	assert.True(t, res.OK)

	require.NoError(t, c.IntegrationDelete(7))
	assert.Contains(t, paths, "POST /api/platform/integrations")
	assert.Contains(t, paths, "GET /api/platform/integrations")
	assert.Contains(t, paths, "POST /api/platform/integrations/7/test")
	assert.Contains(t, paths, "DELETE /api/platform/integrations/7")
}
