package client

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gopkg.in/yaml.v3"
)

func TestEntityRows_buildsEncodedRequest(t *testing.T) {
	var gotPath, gotRaw string
	var gotQuery map[string][]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotRaw, gotQuery = r.URL.Path, r.URL.EscapedPath(), r.URL.Query()
		_, _ = fmt.Fprint(w, `[]`)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	_, err := c.EntityRows("Product/With Category", []EntityFilter{
		{Column: "product id", Value: "a&b=c"},
		{Column: "city", Value: "Lyon é"},
	}, 50, 100)
	require.NoError(t, err)

	assert.Equal(t, "/api/entities/Product/With Category", gotPath)
	assert.Equal(t, "/api/entities/Product%2FWith%20Category", gotRaw, "the name is one path segment")
	assert.Equal(t, []string{"50"}, gotQuery["limit"])
	assert.Equal(t, []string{"100"}, gotQuery["offset"])
	assert.Equal(t, []string{"a&b=c"}, gotQuery["product id"], "values are encoded, never concatenated raw")
	assert.Equal(t, []string{"Lyon é"}, gotQuery["city"])
}

func TestEntityRows_keepsColumnOrderAndBigNumbers(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = fmt.Fprint(w, `[{"zeta":1,"alpha":"x","id":12345678901234567890,"nothing":null,"_conflicts":"{}"}]`)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	rows, err := c.EntityRows("E", nil, 100, 0)
	require.NoError(t, err)
	require.Len(t, rows, 1)

	assert.Equal(t, []string{"zeta", "alpha", "id", "nothing", "_conflicts"}, rows[0].Columns(), "server order, not alphabetical")
	assert.Equal(t, "12345678901234567890", rows[0].Cell("id"), "a wide id must not round through float64")
	assert.Equal(t, "", rows[0].Cell("nothing"))

	js, err := json.Marshal(rows)
	require.NoError(t, err)
	assert.JSONEq(t, `[{"zeta":1,"alpha":"x","id":12345678901234567890,"nothing":null,"_conflicts":"{}"}]`, string(js))
	assert.Equal(t, `[{"zeta":1,"alpha":"x","id":12345678901234567890,"nothing":null,"_conflicts":"{}"}]`, string(js), "order survives re-encoding")

	ym, err := yaml.Marshal(rows)
	require.NoError(t, err)
	assert.Equal(t, "- zeta: 1\n  alpha: x\n  id: 12345678901234567890\n  nothing: null\n  _conflicts: '{}'\n", string(ym))
}

func TestEntityRows_hubErrorsKeepStatusAndMessage(t *testing.T) {
	for _, tc := range []struct {
		status int
		body   string
		want   string
	}{
		{404, `{"error":"entity Ghost not found"}`, "entity Ghost not found"},
		{403, `{"error":"scope not granted"}`, "scope not granted"},
	} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(tc.status)
			_, _ = fmt.Fprint(w, tc.body)
		}))
		c := New(config.Context{Server: srv.URL})
		_, err := c.EntityRows("Ghost", nil, 1, 0)
		srv.Close()
		var api *APIError
		require.ErrorAs(t, err, &api)
		assert.Equal(t, tc.status, api.Status)
		assert.Contains(t, err.Error(), tc.want)
	}
}

func TestEntityRow_columnsIsACopy(t *testing.T) {
	var r EntityRow
	require.NoError(t, json.Unmarshal([]byte(`{"a":1,"b":2}`), &r))
	r.Columns()[0] = "zzz"
	assert.Equal(t, []string{"a", "b"}, r.Columns(), "a caller cannot reorder the row")
}
