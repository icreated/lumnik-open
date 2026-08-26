package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestIntegrationAdd_readsSecretFromStdinAndStatesTheContract(t *testing.T) {
	var body map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&body)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(201)
		_, _ = w.Write([]byte(`{"id":7,"name":"n8n-prod","targetUrl":"https://n8n.local/wh","eventTypes":["decision.*"],"enabled":true}`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetIn(strings.NewReader("s3cret-from-stdin\n"))
	cmd.SetArgs([]string{"integration", "add", "n8n-prod",
		"--url", "https://n8n.local/wh", "--events", "decision.*", "--secret-stdin"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("execute: %v", err)
	}
	if body["secret"] != "s3cret-from-stdin" {
		t.Errorf("secret must travel in the body, read from stdin; got %v", body["secret"])
	}
	got := out.String()
	for _, want := range []string{"registered: n8n-prod (id 7)", "from now on"} {
		if !strings.Contains(got, want) {
			t.Errorf("output missing %q:\n%s", want, got)
		}
	}
}

func TestIntegrationList_rendersTheRegistrations(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[{"id":7,"name":"n8n-prod","targetUrl":"https://n8n.local/wh",
			"eventTypes":["decision.*","annotation.decision.acked"],"enabled":true}]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"integration", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("execute: %v", err)
	}
	got := out.String()
	for _, want := range []string{"NAME", "n8n-prod", "decision.*,annotation.decision.acked", "true"} {
		if !strings.Contains(got, want) {
			t.Errorf("output missing %q:\n%s", want, got)
		}
	}
}

func TestIntegrationTest_failedDeliveryIsAnError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":false,"httpStatus":null,"error":"connection refused","durationMs":50}`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"integration", "test", "7"})
	err := cmd.Execute()
	if err == nil || !strings.Contains(err.Error(), "connection refused") {
		t.Errorf("a failed delivery must surface as an error naming the cause; got %v", err)
	}
}
