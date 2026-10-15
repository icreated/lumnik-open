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

func TestEntityDelete_success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodDelete, r.Method)
		assert.Equal(t, "/api/entities/Customer", r.URL.Path)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	require.NoError(t, c.EntityDelete("Customer"))
}

func TestEntityDelete_404(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = fmt.Fprint(w, `{"error":"entity nope not found"}`)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	err := c.EntityDelete("nope")
	require.Error(t, err)
	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, http.StatusNotFound, apiErr.Status)
}

func TestEntityResolve_parses(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/api/entities/Customer/resolve", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `{"rowsSeen":10,"resolved":7,"ambiguous":1,"unresolvedNoRule":1,
			"unresolvedProvider":1,"newlyMinted":3,"providerCalls":2,"providerFailures":0,
			"ruleHits":{"customer:email":5}}`)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	res, err := c.EntityResolve("Customer")
	require.NoError(t, err)
	assert.Equal(t, &EntityResolveResult{
		RowsSeen: 10, Resolved: 7, Ambiguous: 1, UnresolvedNoRule: 1, UnresolvedProvider: 1,
		NewlyMinted: 3, ProviderCalls: 2, ProviderFailures: 0,
		RuleHits: map[string]int64{"customer:email": 5},
	}, res)
}

func TestEntityResolve_404(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = fmt.Fprint(w, `{"error":"entity 'nope' not found"}`)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	_, err := c.EntityResolve("nope")
	require.Error(t, err)
	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, http.StatusNotFound, apiErr.Status)
}

func TestEntityAmbiguous_parses(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/entities/Customer/resolution/ambiguous", r.URL.Path)
		assert.Equal(t, "50", r.URL.Query().Get("limit"))
		assert.Equal(t, "10", r.URL.Query().Get("offset"))
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `[{"identityRef":"11111111-1111-1111-1111-111111111111",
			"source":"erp","sourceId":"cust-1",
			"candidates":["22222222-2222-2222-2222-222222222222","33333333-3333-3333-3333-333333333333"],
			"discardedCandidates":[{"entityId":"44444444-4444-4444-4444-444444444444","score":0.4}],
			"decidedBy":null,"occurredAt":"2026-09-20T10:00:00Z"}]`)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	items, err := c.EntityAmbiguous("Customer", 50, 10)
	require.NoError(t, err)
	require.Len(t, items, 1)
	assert.Equal(t, "11111111-1111-1111-1111-111111111111", items[0].IdentityRef)
	assert.Equal(t, "erp", items[0].Source)
	assert.Equal(t, "cust-1", items[0].SourceID)
	assert.Equal(t, []string{
		"22222222-2222-2222-2222-222222222222", "33333333-3333-3333-3333-333333333333",
	}, items[0].Candidates)
	require.Len(t, items[0].DiscardedCandidates, 1)
	assert.Equal(t, "44444444-4444-4444-4444-444444444444", items[0].DiscardedCandidates[0].EntityID)
	assert.Equal(t, 0.4, items[0].DiscardedCandidates[0].Score)
	assert.Equal(t, "", items[0].DecidedBy)
}

func TestEntityHistory_parses(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/entities/Customer/resolution/identity/erp/cust-1", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `[{"ruleDeclaredId":"email","ruleVersion":"1","provider":null,
			"candidates":["22222222-2222-2222-2222-222222222222"],"discardedCandidates":[],
			"setAside":[{"entityId":"66666666-6666-6666-6666-666666666666","reason":"same-source","rule":"email"}],
			"outcome":"RESOLVED","decidedBy":"automatic",
			"runId":"55555555-5555-5555-5555-555555555555","occurredAt":"2026-09-20T10:00:00Z"}]`)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	events, err := c.EntityHistory("Customer", "erp", "cust-1")
	require.NoError(t, err)
	require.Len(t, events, 1)
	assert.Equal(t, "email", events[0].RuleDeclaredID)
	assert.Equal(t, "RESOLVED", events[0].Outcome)
	assert.Equal(t, "automatic", events[0].DecidedBy)
	assert.Equal(t, "55555555-5555-5555-5555-555555555555", events[0].RunID)
	rule := "email"
	assert.Equal(t, []SetAside{{EntityID: "66666666-6666-6666-6666-666666666666", Reason: "same-source", Rule: &rule}},
		events[0].SetAside)
}

func TestSetAside_keepsANullRuleNull(t *testing.T) {
	var got []SetAside
	require.NoError(t, json.Unmarshal([]byte(`[{"entityId":"E1","reason":"same-source","rule":null}]`), &got))
	require.Len(t, got, 1)
	assert.Nil(t, got[0].Rule)

	out, err := json.Marshal(got)
	require.NoError(t, err)
	assert.JSONEq(t, `[{"entityId":"E1","reason":"same-source","rule":null}]`, string(out))
}

func TestEntityHistory_404(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = fmt.Fprint(w, `{"error":"no resolution history for erp/cust-1 of entity 'Customer'"}`)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	_, err := c.EntityHistory("Customer", "erp", "cust-1")
	require.Error(t, err)
	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, http.StatusNotFound, apiErr.Status)
}

func TestEntityAssertSame_postsBody(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/api/entities/Customer/assertions/same", r.URL.Path)
		b, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		var body map[string]any
		require.NoError(t, json.Unmarshal(b, &body))
		assert.Equal(t, "11111111-1111-1111-1111-111111111111", body["leftRef"])
		assert.Equal(t, "22222222-2222-2222-2222-222222222222", body["rightRef"])
		assert.Equal(t, "same customer, two emails", body["reason"])
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	err := c.EntityAssertSame("Customer", EntityAssertion{
		LeftRef: "11111111-1111-1111-1111-111111111111", RightRef: "22222222-2222-2222-2222-222222222222",
		Reason: "same customer, two emails",
	})
	require.NoError(t, err)
}

func TestEntityAssertDistinct_postsBody(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/entities/Customer/assertions/distinct", r.URL.Path)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	err := c.EntityAssertDistinct("Customer", EntityAssertion{
		LeftRef: "11111111-1111-1111-1111-111111111111", RightRef: "22222222-2222-2222-2222-222222222222",
	})
	require.NoError(t, err)
}

func TestEntityAssertSame_400(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = fmt.Fprint(w, `{"error":"leftRef and rightRef are required"}`)
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	err := c.EntityAssertSame("Customer", EntityAssertion{})
	require.Error(t, err)
	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, http.StatusBadRequest, apiErr.Status)
}
