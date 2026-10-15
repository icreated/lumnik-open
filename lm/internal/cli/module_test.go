package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestModuleList(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"modules": []map[string]any{
				{"id": "lumnik-repair", "version": "0.1.0", "displayName": "Repair", "active": true},
			},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"module", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "lumnik-repair") {
		t.Errorf("unexpected: %s", buf.String())
	}
}

// A module the hub carries but has switched off is listed INACTIVE, and the REASON says why — an
// operator who sees INACTIVE with no reason does not know what to fix.
func TestModuleList_showsWhyAModuleIsInactive(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"modules": []map[string]any{
				{"id": "core", "version": "0.1.0", "displayName": "Platform", "active": true},
				{"id": "rag", "version": "0.1.0", "displayName": "Platform RAG", "active": false,
					"inactiveReason": "missing_dependency:llm"},
			},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"module", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	if !strings.Contains(out, "REASON") {
		t.Errorf("no REASON column: %s", out)
	}
	sawRag := false
	for _, line := range strings.Split(out, "\n") {
		if strings.HasPrefix(strings.TrimSpace(line), "rag") {
			sawRag = true
		}
		if strings.HasPrefix(strings.TrimSpace(line), "rag") &&
			(!strings.Contains(line, "INACTIVE") || !strings.Contains(line, "missing_dependency:llm")) {
			t.Errorf("rag row lacks its status or reason: %q", line)
		}
		if strings.HasPrefix(strings.TrimSpace(line), "core") && strings.Contains(line, "missing_dependency") {
			t.Errorf("an active module must carry no reason: %q", line)
		}
	}
	if !sawRag {
		t.Errorf("the inactive module is not listed at all: %s", out)
	}
}
