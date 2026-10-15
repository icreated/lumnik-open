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

// runRagSearch serves one hit from a stub hub and returns what `lm rag search` printed.
func runRagSearch(t *testing.T, hit map[string]any) string {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"results": []map[string]any{hit}})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"rag", "search", "test"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	return buf.String()
}

func TestRagSearch(t *testing.T) {
	// The hub's own payload — RagSearchResource.SearchHit: rowHash, ref, content, similarity,
	// metadata. The previous stub answered with `score`/`snippet`, fields the hub has never
	// sent, so the CLI decoded zeros into every column and this test stayed green (#484).
	out := runRagSearch(t, map[string]any{
		"rowHash":    "9f2c1b",
		"ref":        "9f2c1b",
		"content":    "hello world",
		"similarity": 0.873,
		"metadata":   map[string]any{"scope": "desk"},
	})
	for _, want := range []string{"0.873", "9f2c1b", "hello world"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in output:\n%s", want, out)
		}
	}
}

// A chunk that is not an ingested row — a kind:Workflow lifecycle — has no row hash. Its ref
// still names it, so the SOURCE column says where the answer came from instead of nothing.
func TestRagSearchNamesARowlessChunkByItsRef(t *testing.T) {
	out := runRagSearch(t, map[string]any{
		"rowHash":    nil,
		"ref":        "workflow/acme-order-lifecycle",
		"content":    "Workflow acme-order-lifecycle — cycle de vie",
		"similarity": 0.61,
	})
	if !strings.Contains(out, "workflow/acme-order-lifecycle") {
		t.Errorf("unexpected output: %s", out)
	}
}

// The hub answers {indexed, chunks, indexedAt, provider, embeddingModel} (RagStatusDto). The client
// used to decode two fields the hub never sends, so `lm rag status` printed zeros against any hub —
// including one whose AI is switched off, where provider "none" and indexed false are the answer.
func runRoot(t *testing.T, args ...string) string {
	t.Helper()
	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs(args)
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	return buf.String()
}

func ragStatusHub(t *testing.T, body map[string]any) {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(body)
	}))
	t.Cleanup(srv.Close)
	setupCfg(t, srv.URL)
}

func TestRagStatusPrintsWhatTheHubSends(t *testing.T) {
	ragStatusHub(t, map[string]any{"indexed": true, "chunks": 42, "indexedAt": "2026-09-01T00:00:00Z",
		"provider": "ollama", "embeddingModel": "nomic-embed-text"})
	out := runRoot(t, "rag", "status")
	for _, want := range []string{"indexed: true", "chunks: 42", "indexed_at: 2026-09-01T00:00:00Z",
		"provider: ollama", "embedding_model: nomic-embed-text"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in %q", want, out)
		}
	}
}

func TestRagStatusSaysNothingIsIndexedWhenTheAiIsOff(t *testing.T) {
	ragStatusHub(t, map[string]any{"indexed": false, "chunks": 0, "indexedAt": nil,
		"provider": "none", "embeddingModel": "none"})
	out := runRoot(t, "rag", "status")
	for _, want := range []string{"indexed: false", "provider: none", "indexed_at: -"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in %q", want, out)
		}
	}
}

// -o is a persistent root flag, so cobra accepts it on `rag status` whether or not the command
// reads it. Before #532 it silently ignored the flag; the JSON must carry the hub's fields.
func TestRagStatusHonoursJson(t *testing.T) {
	ragStatusHub(t, map[string]any{"indexed": true, "chunks": 42, "indexedAt": "2026-09-01T00:00:00Z",
		"provider": "ollama", "embeddingModel": "nomic-embed-text"})
	out := runRoot(t, "rag", "status", "-o", "json")

	var st struct {
		Indexed  bool   `json:"indexed"`
		Chunks   int    `json:"chunks"`
		Provider string `json:"provider"`
	}
	if err := json.Unmarshal([]byte(out), &st); err != nil {
		t.Fatalf("-o json must emit parseable JSON, got %q: %v", out, err)
	}
	if !st.Indexed || st.Chunks != 42 || st.Provider != "ollama" {
		t.Errorf("status lost its shape: %+v", st)
	}
}

// truncate cuts a column to a width in CHARACTERS — see output.Truncate for why bytes were wrong.
// The cases here are the shapes that reached it: a cut inside an em dash, and a string shorter in
// runes than in bytes.
func TestTruncate_cutsRunesNotBytes(t *testing.T) {
	cases := []struct {
		axis string
		in   string
		n    int
		want string
	}{
		{"nothing to cut", "abc", 5, "abc"},
		{"exactly the width", "abcde", 5, "abcde"},
		{"plain ascii", "abcdef", 5, "abcde…"},
		{"the cut lands inside an em dash", "ab — cd", 4, "ab —…"},
		{"a width counted in runes, not bytes", "é€ü-abc", 3, "é€ü…"},
		{"an accented string shorter in runes than in bytes", "éàü", 4, "éàü"},
	}
	for _, c := range cases {
		if got := truncate(c.in, c.n); got != c.want {
			t.Errorf("%s: truncate(%q, %d) = %q, want %q", c.axis, c.in, c.n, got, c.want)
		}
	}
}
