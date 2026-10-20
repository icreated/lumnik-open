package client

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestCsvIngestTooLargeSaysWhereLargerFilesGo(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusRequestEntityTooLarge)
	}))
	defer srv.Close()
	path := filepath.Join(t.TempDir(), "big.csv")
	if err := os.WriteFile(path, []byte("id\n1\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	f, _ := os.Open(path)
	defer f.Close()

	_, err := New(config.Context{Server: srv.URL}).CsvIngest(f, path, CsvIngestOpts{})
	want := "API error 413 (request too large): the hub accepts a request body up to 10 MB — put a larger CSV on sftp:// or s3://"
	if err == nil || err.Error() != want {
		t.Fatalf("got %v, want %q", err, want)
	}
}
