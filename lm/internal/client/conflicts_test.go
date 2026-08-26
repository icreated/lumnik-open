package client

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestConflictList_decodesLedgerAndHitsTheScopedPath(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.RequestURI()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[{"entity":"FusedCustomer","key":"C-200","columns":["name"],
			"conflicts":{"name":[{"src":"t_erp","value":"Marie"},{"src":"t_csv","value":"MARIE A."}]}}]`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "static"})
	got, err := c.ConflictList(50)
	require.NoError(t, err)
	assert.Equal(t, "/api/entities/conflicts?limit=50", gotPath)
	require.Len(t, got, 1)
	assert.Equal(t, "FusedCustomer", got[0].Entity)
	assert.Equal(t, "C-200", got[0].Key)
	vals := got[0].Conflicts["name"]
	require.Len(t, vals, 2)
	assert.Equal(t, "t_erp", vals[0].Src)
	assert.Equal(t, "MARIE A.", vals[1].Value)
}

// The table's SOURCES column and the describe panel are both derived from the ledger:
// distinct sources in first-mention order, and the columns in the server's order.
func TestConflictDerivedHelpers(t *testing.T) {
	c := Conflict{
		Entity:  "E",
		Key:     "K",
		Columns: []string{"name", "city"},
		Conflicts: map[string][]DetailSource{
			"name": {{Src: "erp", Value: "Marie"}, {Src: "csv", Value: "MARIE"}},
			"city": {{Src: "csv", Value: "Lyon"}, {Src: "sap", Value: "Paris"}},
		},
	}
	names := c.SourceNames()
	require.Len(t, names, 3)
	assert.Equal(t, []string{"erp", "csv", "sap"}, names)

	led := c.Ledger()
	require.Len(t, led, 2)
	assert.Equal(t, "name", led[0].Column)
	assert.Equal(t, "city", led[1].Column)
	require.Len(t, led[1].Sources, 2)
	assert.Equal(t, "Paris", led[1].Sources[1].Value)
}
