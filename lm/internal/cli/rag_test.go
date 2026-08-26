package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func setupCfg(t *testing.T, server string) {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("HOME", dir)
	_ = os.MkdirAll(filepath.Join(dir, ".lm"), 0o700)
	_ = os.WriteFile(filepath.Join(dir, ".lm", "config.yaml"),
		[]byte("current-context: t\ncontexts:\n  t:\n    server: "+server+"\n    token: x\n"),
		0o600)
}

func TestRagSearch(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"results": []map[string]any{{"score": 0.9, "snippet": "hello world"}},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"rag", "search", "test", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "hello world") {
		t.Errorf("unexpected output: %s", buf.String())
	}
}
