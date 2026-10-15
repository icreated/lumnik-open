package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestEntityResolveCmd_printsTheSummary(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/entities/Customer/resolve" {
			t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"rowsSeen": 10, "resolved": 7, "ambiguous": 1, "unresolvedNoRule": 1,
			"unresolvedProvider": 1, "newlyMinted": 3, "providerCalls": 2, "providerFailures": 0,
			"ruleHits": map[string]int{"customer:email": 5},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"entity", "resolve", "Customer"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	for _, want := range []string{"rows seen: 10", "resolved: 7", "ambiguous: 1", "customer:email: 5"} {
		if !strings.Contains(out, want) {
			t.Errorf("output missing %q, got: %s", want, out)
		}
	}
}

func TestEntityAmbiguousCmd_listsRows(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/entities/Customer/resolution/ambiguous" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		if got := r.URL.Query().Get("limit"); got != "100" {
			t.Errorf("limit = %q, want the default 100", got)
		}
		_ = json.NewEncoder(w).Encode([]map[string]any{
			{
				"identityRef": "11111111-1111-1111-1111-111111111111", "source": "erp", "sourceId": "cust-1",
				"candidates": []string{
					"22222222-2222-2222-2222-222222222222", "33333333-3333-3333-3333-333333333333",
				},
				"discardedCandidates": []any{}, "decidedBy": nil, "occurredAt": "2026-09-20T10:00:00Z",
			},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"entity", "ambiguous", "Customer"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	if !strings.Contains(out, "erp") || !strings.Contains(out, "cust-1") {
		t.Errorf("table must name the source identity: %s", out)
	}
	if !strings.Contains(out, "22222222-2222-2222-2222-222222222222") {
		t.Errorf("table must list the candidates: %s", out)
	}
}

func TestEntityHistoryCmd_listsEvents(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/entities/Customer/resolution/identity/erp/cust-1" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		_ = json.NewEncoder(w).Encode([]map[string]any{
			{
				"ruleDeclaredId": "email", "ruleVersion": "1", "provider": nil,
				"candidates": []string{"22222222-2222-2222-2222-222222222222"}, "discardedCandidates": []any{},
				"setAside": []any{map[string]any{"entityId": "66666666-6666-6666-6666-666666666666",
					"reason": "same-source", "rule": "email"}},
				"outcome": "RESOLVED", "decidedBy": "automatic",
				"runId": "55555555-5555-5555-5555-555555555555", "occurredAt": "2026-09-20T10:00:00Z",
			},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"entity", "history", "Customer", "erp", "cust-1"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	if !strings.Contains(out, "RESOLVED") || !strings.Contains(out, "email") {
		t.Errorf("history must name the outcome and the rule: %s", out)
	}
	if !strings.Contains(out, "SET_ASIDE") || !strings.Contains(out, "66666666-6666-6666-6666-666666666666") {
		t.Errorf("history must name the entity set aside: %s", out)
	}
}

func TestEntityHistoryCmd_jsonKeepsANullRuleNull(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`[{"ruleDeclaredId":null,"candidates":[],"discardedCandidates":[],
			"setAside":[{"entityId":"66666666-6666-6666-6666-666666666666","reason":"same-source","rule":null}],
			"outcome":"RESOLVED","decidedBy":"asserted","occurredAt":"2026-09-20T10:00:00Z"}]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"entity", "history", "Customer", "erp", "cust-1", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	var events []struct {
		SetAside []map[string]any `json:"setAside"`
	}
	if err := json.Unmarshal(buf.Bytes(), &events); err != nil {
		t.Fatalf("not JSON: %v\n%s", err, buf.String())
	}
	rule, present := events[0].SetAside[0]["rule"]
	if !present || rule != nil {
		t.Errorf("a set-aside from a same assertion must keep \"rule\": null, got %v (present=%v)", rule, present)
	}
}

func TestEntityHistoryCmd_notFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":"no resolution history for erp/cust-1 of entity 'Customer'"}`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SilenceErrors = true
	cmd.SetArgs([]string{"entity", "history", "Customer", "erp", "cust-1"})
	err := cmd.Execute()
	if err == nil {
		t.Fatal("expected an error for an unknown identity")
	}
	if !strings.Contains(err.Error(), "no resolution history") {
		t.Errorf("the hub's real message must surface, got: %v", err)
	}
}

func TestEntityAssertSameCmd_requiresLeftAndRight(t *testing.T) {
	setupCfg(t, "http://unused.invalid")

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SilenceErrors = true
	cmd.SetArgs([]string{"entity", "assert-same", "Customer"})
	err := cmd.Execute()
	if err == nil {
		t.Fatal("expected an error when --left/--right are missing")
	}
}

func TestEntityAssertSameCmd_posts(t *testing.T) {
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/entities/Customer/assertions/same" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"entity", "assert-same", "Customer",
		"--left", "11111111-1111-1111-1111-111111111111",
		"--right", "22222222-2222-2222-2222-222222222222",
		"--reason", "same customer, two emails"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if gotBody["leftRef"] != "11111111-1111-1111-1111-111111111111" {
		t.Errorf("leftRef not sent: %v", gotBody)
	}
	if gotBody["reason"] != "same customer, two emails" {
		t.Errorf("reason not sent: %v", gotBody)
	}
	if !strings.Contains(buf.String(), "same") {
		t.Errorf("expected a confirmation line, got: %s", buf.String())
	}
}

func TestEntityAssertDistinctCmd_posts(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/entities/Customer/assertions/distinct" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"entity", "assert-distinct", "Customer",
		"--left", "11111111-1111-1111-1111-111111111111",
		"--right", "22222222-2222-2222-2222-222222222222"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "distinct") {
		t.Errorf("expected a confirmation line, got: %s", buf.String())
	}
}
