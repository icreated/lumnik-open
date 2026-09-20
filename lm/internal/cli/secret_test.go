package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSecretCmd_setStdinSentinel(t *testing.T) {
	if !isStdinSentinel("-") {
		t.Errorf("expected '-' to be sentinel")
	}
	if isStdinSentinel("value") {
		t.Errorf("expected 'value' NOT to be sentinel")
	}
}

func TestSecretList(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" || r.URL.Path != "/api/platform/secrets" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode([]map[string]any{
			{"id": "1", "name": "db-password", "description": "main db"},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"secret", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "db-password") {
		t.Errorf("unexpected output: %s", buf.String())
	}
}

func TestSecretDescribe(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" || r.URL.Path != "/api/platform/secrets/db-password" {
			http.NotFound(w, r)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "1", "name": "db-password", "description": "main db",
			"createdAt": "2026-01-01T00:00:00Z",
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"secret", "describe", "db-password"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "db-password") {
		t.Errorf("unexpected output: %s", buf.String())
	}
}

// `lm secret describe NAME -o json` was silently ignored (#532): the flag is accepted (root
// persistent) but the command always printed the aligned card. Same never-carries-a-value
// pin as TestSecretListHonoursJsonAndNeverCarriesAValue: SecretMeta has no value field.
func TestSecretDescribeHonoursJsonAndNeverCarriesAValue(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"id":"1","name":"db-password","description":"main db","createdAt":"2026-01-01T00:00:00Z","value":"hunter2"}`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"secret", "describe", "db-password", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}

	var got map[string]any
	if err := json.Unmarshal(buf.Bytes(), &got); err != nil {
		t.Fatalf("-o json must emit parseable JSON, got %q: %v", buf.String(), err)
	}
	if got["name"] != "db-password" {
		t.Fatalf("the metadata must survive: %v", got)
	}
	if strings.Contains(buf.String(), "hunter2") {
		t.Errorf("a secret value reached stdout: %s", buf.String())
	}
}

func TestSecretSet_Create(t *testing.T) {
	var gotMethod, gotPath string
	var gotBody map[string]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.WriteHeader(201)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"secret", "set", "my-key", "my-value"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if gotMethod != "POST" {
		t.Errorf("expected POST, got %s", gotMethod)
	}
	if gotPath != "/api/platform/secrets" {
		t.Errorf("unexpected path: %s", gotPath)
	}
	if gotBody["name"] != "my-key" || gotBody["value"] != "my-value" {
		t.Errorf("unexpected body: %v", gotBody)
	}
	if !strings.Contains(buf.String(), "Created") {
		t.Errorf("unexpected output: %s", buf.String())
	}
}

func TestSecretRm_Force(t *testing.T) {
	var gotMethod, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		w.WriteHeader(204)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"secret", "rm", "--force", "my-key"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if gotMethod != "DELETE" {
		t.Errorf("expected DELETE, got %s", gotMethod)
	}
	if gotPath != "/api/platform/secrets/my-key" {
		t.Errorf("unexpected path: %s", gotPath)
	}
	if !strings.Contains(buf.String(), "Deleted") {
		t.Errorf("unexpected output: %s", buf.String())
	}
}

// `lm secret list -o json` is the pre-flight a pipeline runs before an apply: are the secrets
// this environment references actually set? -o being a persistent root flag, it was accepted
// here and ignored, so a pipeline asking for JSON got a column-aligned page instead.
//
// The second assertion is the one that matters more: a value must not travel even when the
// server sends one. SecretMeta has no value field, so the decode drops it — this pins that,
// because the day someone adds one for another reason, the leak would be silent and here.
func TestSecretListHonoursJsonAndNeverCarriesAValue(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`[{"id":"s1","name":"ERP_PASSWORD","description":"prod","rotatedAt":"2026-09-01T10:00:00Z","value":"hunter2"}]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"secret", "list", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}

	var got []map[string]any
	if err := json.Unmarshal(buf.Bytes(), &got); err != nil {
		t.Fatalf("-o json must emit parseable JSON, got %q: %v", buf.String(), err)
	}
	if len(got) != 1 || got[0]["name"] != "ERP_PASSWORD" {
		t.Fatalf("the metadata must survive: %v", got)
	}
	if strings.Contains(buf.String(), "hunter2") {
		t.Errorf("a secret value reached stdout: %s", buf.String())
	}
}
