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
		_, _ = w.Write([]byte(`[{"id":"s1","name":"ERP_PASSWORD","description":"prod","rotatedAt":"2026-09-01T10:00:00Z","scopes":[],"value":"hunter2"}]`))
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
	// #2015: a secret with no scope says so, so a script can find the ones still to scope after the upgrade.
	if s, ok := got[0]["scopes"].([]any); !ok || len(s) != 0 {
		t.Errorf(`a scopeless secret must print "scopes": [], got %v`, got[0]["scopes"])
	}
	if strings.Contains(buf.String(), "hunter2") {
		t.Errorf("a secret value reached stdout: %s", buf.String())
	}
}

// #2015: a connector or an export reads a secret only when it carries every one of the secret's
// scopes. `lm secret scope` replaces them apart from the value, so scoping a secret on upgrade
// never means typing it again; an empty list makes it scopeless again.
func TestSecretScope_replacesTheScopesWithoutTheValue(t *testing.T) {
	var gotMethod, gotPath string
	var gotBody []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotPath = r.Method, r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		_ = json.NewEncoder(w).Encode(gotBody)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	for _, tc := range []struct {
		arg  string
		want []string
		out  string
	}{
		{"ventes, achats", []string{"ventes", "achats"}, "scopes: ventes,achats\n"},
		{"", []string{}, "scopes: (none)\n"},
	} {
		cmd := NewRootCmd()
		buf := new(bytes.Buffer)
		cmd.SetOut(buf)
		cmd.SetErr(buf)
		cmd.SetArgs([]string{"secret", "scope", "ERP_PASSWORD", tc.arg})
		if err := cmd.Execute(); err != nil {
			t.Fatal(err)
		}
		if gotMethod != "PUT" || gotPath != "/api/platform/secrets/ERP_PASSWORD/scopes" {
			t.Errorf("%q: got %s %s", tc.arg, gotMethod, gotPath)
		}
		if strings.Join(gotBody, ",") != strings.Join(tc.want, ",") || gotBody == nil {
			t.Errorf("%q: body %v, want %v", tc.arg, gotBody, tc.want)
		}
		if buf.String() != tc.out {
			t.Errorf("%q: output %q, want %q", tc.arg, buf.String(), tc.out)
		}
	}
}

func TestSecretListAndDescribeShowTheScopes(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		one := map[string]any{"id": "1", "name": "ERP_PASSWORD", "scopes": []string{"ventes", "achats"}}
		if r.URL.Path == "/api/platform/secrets" {
			_ = json.NewEncoder(w).Encode([]map[string]any{one, {"id": "2", "name": "OPEN_KEY"}})
			return
		}
		_ = json.NewEncoder(w).Encode(one)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	for args, want := range map[string][]string{
		"secret list":                  {"SCOPES", "ventes,achats", "OPEN_KEY"},
		"secret describe ERP_PASSWORD": {"scopes:      ventes,achats"},
	} {
		cmd := NewRootCmd()
		buf := new(bytes.Buffer)
		cmd.SetOut(buf)
		cmd.SetErr(buf)
		cmd.SetArgs(strings.Fields(args))
		if err := cmd.Execute(); err != nil {
			t.Fatal(err)
		}
		for _, w := range want {
			if !strings.Contains(buf.String(), w) {
				t.Errorf("%s: %q missing from\n%s", args, w, buf.String())
			}
		}
	}
}
