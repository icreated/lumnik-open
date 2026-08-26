package cli

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCsvIngest_sftp_url_builds_transport(t *testing.T) {
	var called bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), `"kind":"sftp"`) {
			t.Errorf("expected sftp transport in body, got: %s", string(body))
		}
		if !strings.Contains(string(body), `"host":"sftp.test.io"`) {
			t.Errorf("expected host=sftp.test.io")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"connectorId":"00000000-0000-0000-0000-000000000001","endpointId":"00000000-0000-0000-0000-000000000002","tableName":"ext.test","rowsRead":2,"rowsWritten":2,"chunksIndexed":0,"promoteColumns":[],"warnings":[]}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{
		"csv", "ingest", "sftp://lumi@sftp.test.io/export/file.csv",
		"--password-secret", "SFTP_PWD", "--scope", "test",
	})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	if err := rootCmd.Execute(); err != nil {
		t.Fatalf("execute: %v (out=%s)", err, out.String())
	}
	if !called {
		t.Errorf("server not called")
	}
}

func TestCsvIngest_s3_url_builds_transport(t *testing.T) {
	var called bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), `"kind":"s3"`) {
			t.Errorf("expected s3 transport, got: %s", string(body))
		}
		if !strings.Contains(string(body), `"bucket":"my-bucket"`) {
			t.Errorf("expected bucket=my-bucket")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"connectorId":"00000000-0000-0000-0000-000000000001","endpointId":"00000000-0000-0000-0000-000000000002","tableName":"ext.test","rowsRead":0,"rowsWritten":0,"chunksIndexed":0,"promoteColumns":[],"warnings":[]}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{
		"csv", "ingest", "s3://my-bucket/data/file.csv",
		"--region", "us-east-1", "--scope", "test",
	})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	if err := rootCmd.Execute(); err != nil {
		t.Fatalf("execute: %v (out=%s)", err, out.String())
	}
	if !called {
		t.Errorf("server not called")
	}
}
