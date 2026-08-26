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

func TestViewDetail_parsesRecordAndConflicts(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/views/detail", r.URL.Path)
		assert.Equal(t, "C-200", r.URL.Query().Get("key"))
		assert.Equal(t, "application/yaml", r.Header.Get("Content-Type"))
		body, _ := io.ReadAll(r.Body)
		assert.Contains(t, string(body), "kind: View")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"title":"Clients","key":"C-200",
			"fields":[{"name":"customer_code","label":"Code client","value":"C-200"},
			          {"name":"name","label":"Nom","value":"Marie"}],
			"conflictLedger":true,
			"conflicts":[{"column":"name","label":"Nom",
			  "sources":[{"src":"cust_siebel","value":"Marie"},{"src":"t_cust_csv","value":"MARIE A."}]}]}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	res, err := c.ViewDetail([]byte("kind: View\n"), "C-200")
	require.NoError(t, err)

	assert.Equal(t, "Clients", res.Title)
	assert.Equal(t, "C-200", res.Key)
	assert.Equal(t, []DetailField{{"customer_code", "Code client", "C-200"}, {"name", "Nom", "Marie"}}, res.Fields)
	assert.True(t, res.ConflictLedger)
	assert.Equal(t, "Nom", res.Conflicts[0].Label)
	assert.Equal(t, []DetailSource{{"cust_siebel", "Marie"}, {"t_cust_csv", "MARIE A."}}, res.Conflicts[0].Sources)
}

func TestViewDetail_coercesNonTextFieldValues(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"title":"Orders","key":"SO-9",
			"fields":[{"name":"amount","label":"Montant","value":312480},
			          {"name":"paid","label":"Payé","value":true},
			          {"name":"note","label":"Note","value":null}],
			"conflictLedger":false,"conflicts":[]}`))
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})
	res, err := c.ViewDetail([]byte("kind: View\n"), "SO-9")
	require.NoError(t, err)
	assert.Equal(t, "312480", res.Fields[0].Value)
	assert.Equal(t, "true", res.Fields[1].Value)
	assert.Equal(t, "", res.Fields[2].Value) // null → empty string
}
