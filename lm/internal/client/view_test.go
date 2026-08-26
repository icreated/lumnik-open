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

func TestViewRender_parsesTitleColumnsRows(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/views/render", r.URL.Path)
		assert.Equal(t, []string{"segment=GOLD", "name~dup"}, r.URL.Query()["filter"])
		assert.Equal(t, "application/yaml", r.Header.Get("Content-Type"))
		body, _ := io.ReadAll(r.Body)
		assert.Contains(t, string(body), "kind: View")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"title":"Clients",
			"columns":[{"name":"customer_code","label":"Code","pgType":"text"},
			           {"name":"name","label":"Name","pgType":"text"}],
			"rows":[{"customer_code":"C-1","name":"Alice"},
			        {"customer_code":"C-2","name":"Bob"}]}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	res, err := c.ViewRender([]byte("kind: View\n"), []string{"segment=GOLD", "name~dup"})
	require.NoError(t, err)

	assert.Equal(t, "Clients", res.Title)
	assert.Equal(t, []ViewColumn{{"customer_code", "Code", "text"}, {"name", "Name", "text"}}, res.Columns)
	assert.Equal(t, [][]string{{"C-1", "Alice"}, {"C-2", "Bob"}}, res.Rows)
}
