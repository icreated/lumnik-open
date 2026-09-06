package cli

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCsvIngestCmd_uploads_multipart_and_prints_summary(t *testing.T) {
	var called bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		if !strings.HasPrefix(r.Header.Get("Content-Type"), "multipart/form-data") {
			t.Errorf("expected multipart, got %s", r.Header.Get("Content-Type"))
		}
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), "id,email") {
			t.Errorf("expected CSV body in multipart, got: %s", string(body))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"connectorId":"00000000-0000-0000-0000-000000000001","endpointId":"00000000-0000-0000-0000-000000000002","tableName":"ext.test","rowsRead":2,"rowsWritten":2,"chunksIndexed":0,"promoteColumns":[],"warnings":[]}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	dir := t.TempDir()
	csv := filepath.Join(dir, "test.csv")
	if err := os.WriteFile(csv, []byte("id,email\n1,a@x\n2,b@x\n"), 0644); err != nil {
		t.Fatal(err)
	}

	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{"csv", "ingest", csv, "--scope", "demo"})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	if err := rootCmd.Execute(); err != nil {
		t.Fatalf("execute: %v (out=%s)", err, out.String())
	}

	if !called {
		t.Errorf("server was not called")
	}
	if !strings.Contains(out.String(), "rows written") {
		t.Errorf("summary missing: %s", out.String())
	}
}

// A11: the hub refuses `name` over a multi-file source (one dormant connector per name, so file 2
// violates the unique constraint). Two things have to hold on this side: the flag must stop
// promising what the hub refuses, and the hub's teaching must reach the human intact.

func TestCsvIngestCmd_nameFlagHelp_states_the_single_file_limit(t *testing.T) {
	cmd, _, err := NewRootCmd().Find([]string{"csv", "ingest"})
	if err != nil {
		t.Fatal(err)
	}
	usage := cmd.Flags().Lookup("name").Usage
	if !strings.Contains(usage, "single file") {
		t.Errorf("--name help must state the single-file limit for globbed sources, got: %q", usage)
	}
}

func TestCsvIngestCmd_surfaces_the_hub_refusal_message(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		_, _ = io.WriteString(w, `{"error":"name 'clients' would ingest 3 files into one dataset, which the one-shot path cannot register: each file creates its own dormant connector under that name. Point at a single file, or drop the name to get one dataset per file."}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{"csv", "ingest", "s3://bucket/data/", "--region", "us-east-1", "--name", "clients"})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)

	err := rootCmd.Execute()
	if err == nil {
		t.Fatal("expected the refusal to fail the command")
	}
	if !strings.Contains(err.Error(), "single file") {
		t.Errorf("the hub's teaching must reach the human, got: %v", err)
	}
}

// The one-shot writes no RAG chunks: its MappingTarget carries `rag = null`
// (CsvIngestService), and MultiTargetWriter indexes only when `target.rag() != null`.
// The summary used to print a chunk count anyway — the row count echoed back — so a reader
// was told a corpus existed that nothing had written. Whatever a server sends under that
// key, the summary must not claim it.
func TestCsvIngestSummary_neverClaimsRagChunks(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"connectorId":"00000000-0000-0000-0000-000000000001","endpointId":"00000000-0000-0000-0000-000000000002","tableName":"ext.test","rowsRead":2,"rowsWritten":2,"chunksIndexed":42,"promoteColumns":[],"warnings":[]}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	dir := t.TempDir()
	csv := filepath.Join(dir, "test.csv")
	if err := os.WriteFile(csv, []byte("id,email\n1,a@x\n2,b@x\n"), 0644); err != nil {
		t.Fatal(err)
	}

	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{"csv", "ingest", csv, "--scope", "demo"})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	if err := rootCmd.Execute(); err != nil {
		t.Fatalf("execute: %v (out=%s)", err, out.String())
	}

	if strings.Contains(out.String(), "RAG chunks") {
		t.Errorf("summary claims RAG chunks the one-shot never wrote:\n%s", out.String())
	}
}

// A one-shot leaves its scope with NO schema card: it creates no connector run, and its ext.*
// table never enters connector.dynamic_table, so DiscoveryService.rediscover never fires.
// `lm ask` hardcodes mode "analytic", and AskToSqlAgent's first act is
// store.load(tenant, scope).orElseThrow(NoSchemaException) — before any model is touched.
// Proven by execution in CsvIngestServiceTest. So the summary must not send the reader there.
func TestCsvIngestSummary_doesNotPromiseAnAskThatCannotAnswer(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"connectorId":"00000000-0000-0000-0000-000000000001","endpointId":"00000000-0000-0000-0000-000000000002","tableName":"ext.test","rowsRead":2,"rowsWritten":2,"promoteColumns":[],"warnings":[]}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	dir := t.TempDir()
	csv := filepath.Join(dir, "test.csv")
	if err := os.WriteFile(csv, []byte("id,email\n1,a@x\n2,b@x\n"), 0644); err != nil {
		t.Fatal(err)
	}

	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{"csv", "ingest", csv, "--scope", "demo"})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	if err := rootCmd.Execute(); err != nil {
		t.Fatalf("execute: %v (out=%s)", err, out.String())
	}

	if strings.Contains(out.String(), "lm ask") {
		t.Errorf("summary sends the reader to `lm ask`, which refuses after a one-shot:\n%s", out.String())
	}
	// and it says what DID happen, so the closing line is not merely a deletion
	for _, want := range []string{"no run recorded", "ext.test", "lm connector list"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("summary does not mention %q:\n%s", want, out.String())
		}
	}
	t.Logf("the summary a reader now sees:\n%s", out.String())
}

// The new hub reports askable scopes (spec 2026-09-02): with the door present the
// summary hands the reader a copy-pasteable lm ask command; without it, the plain
// pre-askable summary — never a tease.
func TestCsvIngestSummary_promisesAskWhenTheHubMadeItTrue(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"connectorId":"00000000-0000-0000-0000-000000000001","endpointId":"00000000-0000-0000-0000-000000000002","tableName":"ext.test","rowsRead":2,"rowsWritten":2,"promoteColumns":[],"warnings":[],"askableScopes":["sales"],"chatAvailable":true}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	dir := t.TempDir()
	csv := filepath.Join(dir, "test.csv")
	if err := os.WriteFile(csv, []byte("id\n1\n"), 0644); err != nil {
		t.Fatal(err)
	}
	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{"csv", "ingest", csv, "--scope", "sales"})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	if err := rootCmd.Execute(); err != nil {
		t.Fatalf("execute: %v (out=%s)", err, out.String())
	}
	for _, want := range []string{"askable", "lm ask", "how many rows", "--scope sales", "ext.test"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("summary does not mention %q:\n%s", want, out.String())
		}
	}
	if strings.Contains(out.String(), "nothing indexed") {
		t.Errorf("summary keeps the pre-askable line despite askable scopes:\n%s", out.String())
	}
}

func TestCsvIngestSummary_staysTruthfulWithoutTheChatDoor(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"connectorId":"00000000-0000-0000-0000-000000000001","endpointId":"00000000-0000-0000-0000-000000000002","tableName":"ext.test","rowsRead":2,"rowsWritten":2,"promoteColumns":[],"warnings":[],"askableScopes":["sales"],"chatAvailable":false}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	dir := t.TempDir()
	csv := filepath.Join(dir, "test.csv")
	if err := os.WriteFile(csv, []byte("id\n1\n"), 0644); err != nil {
		t.Fatal(err)
	}
	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{"csv", "ingest", csv, "--scope", "sales"})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	if err := rootCmd.Execute(); err != nil {
		t.Fatalf("execute: %v (out=%s)", err, out.String())
	}
	// The doorless build keeps the plain summary entirely: the silently-built schema card
	// is internal accounting, and a paid feature's absence is not announced (GraphQL rule).
	for _, forbidden := range []string{"lm ask", "lm chat", "schema card", "pro edition", "askable"} {
		if strings.Contains(out.String(), forbidden) {
			t.Errorf("doorless summary mentions %q:\n%s", forbidden, out.String())
		}
	}
	for _, want := range []string{"ext.test", "lm connector list"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("doorless summary lost the plain line %q:\n%s", want, out.String())
		}
	}
}

// The breath: a refreshed re-ingest says so, instead of repeating the first-time line.
func TestCsvIngestSummary_saysBreathOnRefresh(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"connectorId":"00000000-0000-0000-0000-000000000001","endpointId":"00000000-0000-0000-0000-000000000002","tableName":"ext.test","rowsRead":3,"rowsWritten":3,"promoteColumns":[],"warnings":[],"askableScopes":["sales"],"chatAvailable":true,"refreshed":true}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	dir := t.TempDir()
	csv := filepath.Join(dir, "test.csv")
	if err := os.WriteFile(csv, []byte("id\n1\n"), 0644); err != nil {
		t.Fatal(err)
	}
	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{"csv", "ingest", csv, "--scope", "sales"})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	if err := rootCmd.Execute(); err != nil {
		t.Fatalf("execute: %v (out=%s)", err, out.String())
	}
	if !strings.Contains(out.String(), "Re-ingested") || !strings.Contains(out.String(), "one more breath") {
		t.Errorf("a refreshed ingest must say so:\n%s", out.String())
	}
}
