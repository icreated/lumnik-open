package cli

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const entityCSV = "id,name\n1,Acme\n2,\"Bé, Inc\"\n"

// csvHub serves entityCSV from the snapshot door and counts the requests it got.
func csvHub(t *testing.T, hits *atomic.Int32) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		if r.URL.Path != "/api/exports/entities/Customer/download" {
			w.WriteHeader(404)
			return
		}
		w.Header().Set("Content-Type", "text/csv;charset=UTF-8")
		_, _ = io.WriteString(w, entityCSV)
	}))
	t.Cleanup(srv.Close)
	return srv
}

// dirNames lists dir, so a test can prove no temporary .part file was left behind.
func dirNames(t *testing.T, dir string) []string {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	return names
}

func TestEntityExport_writesTheExactBytesAndLeavesNoTemporary(t *testing.T) {
	var hits atomic.Int32
	dir := t.TempDir()
	target := filepath.Join(dir, "customers.csv")
	out, _, err := runLm(t, csvHub(t, &hits), "entity", "export", "Customer", "-o", target)
	if err != nil {
		t.Fatalf("export: %v", err)
	}
	got, err := os.ReadFile(target)
	if err != nil || string(got) != entityCSV {
		t.Fatalf("file = %q, %v", got, err)
	}
	sum := sha256.Sum256([]byte(entityCSV))
	if !strings.Contains(out, "bytes written to "+target) || !strings.Contains(out, hex.EncodeToString(sum[:])) {
		t.Errorf("stdout = %q", out)
	}
	if names := dirNames(t, dir); len(names) != 1 {
		t.Errorf("only the published file should remain, got %v", names)
	}
	if fi, err := os.Stat(target); runtime.GOOS != "windows" && (err != nil || fi.Mode().Perm() != 0o600) {
		t.Errorf("the snapshot holds business data and should be owner-only: %v %v", fi.Mode(), err)
	}
}

func TestEntityExport_refusesAnExistingFileBeforeAsking(t *testing.T) {
	var hits atomic.Int32
	target := filepath.Join(t.TempDir(), "customers.csv")
	if err := os.WriteFile(target, []byte("keep me"), 0o600); err != nil {
		t.Fatal(err)
	}
	_, _, err := runLm(t, csvHub(t, &hits), "entity", "export", "Customer", "-o", target)
	if err == nil || !strings.Contains(err.Error(), "--force") {
		t.Fatalf("err = %v, want a refusal naming --force", err)
	}
	if got, _ := os.ReadFile(target); string(got) != "keep me" {
		t.Errorf("existing file overwritten: %q", got)
	}
	if hits.Load() != 0 {
		t.Error("a collision known up front must not cost the hub a snapshot")
	}
}

func TestEntityExport_forceReplaces(t *testing.T) {
	var hits atomic.Int32
	target := filepath.Join(t.TempDir(), "customers.csv")
	if err := os.WriteFile(target, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := runLm(t, csvHub(t, &hits), "entity", "export", "Customer", "-o", target, "--force"); err != nil {
		t.Fatalf("export --force: %v", err)
	}
	if got, _ := os.ReadFile(target); string(got) != entityCSV {
		t.Errorf("file = %q", got)
	}
}

func TestEntityExport_fileAppearingMidDownloadIsNotOverwritten(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "customers.csv")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		// Someone else claims the name while the body is on its way.
		_ = os.WriteFile(target, []byte("theirs"), 0o600)
		w.Header().Set("Content-Type", "text/csv")
		_, _ = io.WriteString(w, entityCSV)
	}))
	t.Cleanup(srv.Close)
	_, _, err := runLm(t, srv, "entity", "export", "Customer", "-o", target)
	if err == nil || !strings.Contains(err.Error(), "refusing to overwrite") {
		t.Fatalf("err = %v", err)
	}
	if got, _ := os.ReadFile(target); string(got) != "theirs" {
		t.Errorf("the other file was overwritten: %q", got)
	}
	if names := dirNames(t, dir); len(names) != 1 {
		t.Errorf("the temporary download should be removed, got %v", names)
	}
}

func TestEntityExport_scopeRefusalPassesThroughAndWritesNothing(t *testing.T) {
	dir := t.TempDir()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(403)
		_, _ = io.WriteString(w, `{"error":"scope not granted"}`)
	}))
	t.Cleanup(srv.Close)
	_, _, err := runLm(t, srv, "entity", "export", "Customer", "-o", filepath.Join(dir, "c.csv"))
	if err == nil || !strings.Contains(err.Error(), "scope not granted") {
		t.Fatalf("err = %v, want the hub's own refusal", err)
	}
	if names := dirNames(t, dir); len(names) != 0 {
		t.Errorf("a refusal must leave no artifact, got %v", names)
	}
}

func TestEntityExport_partialResponseLeavesNoFile(t *testing.T) {
	dir := t.TempDir()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		conn, rw, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Error(err)
			return
		}
		defer conn.Close()
		_, _ = rw.WriteString("HTTP/1.1 200 OK\r\nContent-Type: text/csv\r\nTransfer-Encoding: chunked\r\n\r\n" +
			"8\r\nid,name\n\r\n") // the hub failed after committing the headers: no final chunk
		_ = rw.Flush()
	}))
	t.Cleanup(srv.Close)
	_, _, err := runLm(t, srv, "entity", "export", "Customer", "-o", filepath.Join(dir, "c.csv"))
	if err == nil {
		t.Fatal("a truncated body must fail the command")
	}
	if names := dirNames(t, dir); len(names) != 0 {
		t.Errorf("a partial download must leave nothing, got %v", names)
	}
}

func TestEntityExport_interruptLeavesNoFile(t *testing.T) {
	dir := t.TempDir()
	release := make(chan struct{})
	flowing := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/csv")
		_, _ = io.WriteString(w, "id,name\n")
		w.(http.Flusher).Flush()
		close(flowing)
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	t.Cleanup(func() { close(release); srv.Close() })

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		<-flowing
		time.Sleep(50 * time.Millisecond) // let the first bytes land in the temporary file
		cancel()
	}()
	_, _, err := runLmCtx(t, ctx, srv, "entity", "export", "Customer", "-o", filepath.Join(dir, "c.csv"))
	if err == nil || !strings.Contains(err.Error(), "was not written") {
		t.Fatalf("err = %v", err)
	}
	if names := dirNames(t, dir); len(names) != 0 {
		t.Errorf("an interrupted download must leave nothing, got %v", names)
	}
}

func TestEntityExport_dashStreamsToStdout(t *testing.T) {
	var hits atomic.Int32
	out, _, err := runLm(t, csvHub(t, &hits), "entity", "export", "Customer", "-o", "-")
	if err != nil || out != entityCSV {
		t.Errorf("stdout = %q, err = %v", out, err)
	}
}

func TestEntityExport_requiresATarget(t *testing.T) {
	var hits atomic.Int32
	_, _, err := runLm(t, csvHub(t, &hits), "entity", "export", "Customer")
	if err == nil || !strings.Contains(err.Error(), "-o FILE is required") {
		t.Errorf("err = %v", err)
	}
}

// Without hard links there is no portable no-replace publish: the command must fail closed,
// never fall back to a check-then-rename that could clobber a file created in between.
func TestEntityExport_noHardLinksFailsClosed(t *testing.T) {
	linkFile = func(_, _ string) error { return &os.LinkError{Op: "link", Err: errors.ErrUnsupported} }
	t.Cleanup(func() { linkFile = os.Link })
	var hits atomic.Int32
	dir := t.TempDir()
	target := filepath.Join(dir, "customers.csv")
	_, _, err := runLm(t, csvHub(t, &hits), "entity", "export", "Customer", "-o", target)
	if err == nil || !strings.Contains(err.Error(), "--force") {
		t.Fatalf("err = %v, want a fail-closed refusal naming --force", err)
	}
	if names := dirNames(t, dir); len(names) != 0 {
		t.Errorf("nothing may be published or left behind, got %v", names)
	}

	// --force is the explicit consent to replace, and needs no hard link.
	if _, _, err := runLm(t, csvHub(t, &hits), "entity", "export", "Customer", "-o", target, "--force"); err != nil {
		t.Fatalf("--force without hard links: %v", err)
	}
	if got, _ := os.ReadFile(target); string(got) != entityCSV {
		t.Errorf("file = %q", got)
	}
}

// A temporary copy that cannot be unlinked after publishing is a second copy of the data under
// a hidden name: the command must say so and fail, not announce a clean success.
func TestEntityExport_unremovableTemporaryIsReported(t *testing.T) {
	calls := 0
	removeFile = func(string) error { calls++; return &os.PathError{Op: "remove", Err: fs.ErrPermission} }
	t.Cleanup(func() { removeFile = os.Remove })
	var hits atomic.Int32
	dir := t.TempDir()
	target := filepath.Join(dir, "customers.csv")
	out, _, err := runLm(t, csvHub(t, &hits), "entity", "export", "Customer", "-o", target)
	if err == nil || !strings.Contains(err.Error(), "could not be removed") || !strings.Contains(err.Error(), ".part") {
		t.Fatalf("err = %v, want the leftover temporary named", err)
	}
	if strings.Contains(out, "bytes written") {
		t.Errorf("success announced despite the leftover: %q", out)
	}
	if calls != 2 {
		t.Errorf("remove attempts = %d, want one retry", calls)
	}
	if got, _ := os.ReadFile(target); string(got) != entityCSV {
		t.Errorf("the published file itself must stay complete, got %q", got)
	}
}

// On the failure path the partial download is removed too; if that fails, stderr names it.
func TestEntityExport_unremovablePartialIsReported(t *testing.T) {
	removeFile = func(string) error { return &os.PathError{Op: "remove", Err: fs.ErrPermission} }
	t.Cleanup(func() { removeFile = os.Remove })
	dir := t.TempDir()
	target := filepath.Join(dir, "customers.csv")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_ = os.WriteFile(target, []byte("theirs"), 0o600) // publishing will be refused
		w.Header().Set("Content-Type", "text/csv")
		_, _ = io.WriteString(w, entityCSV)
	}))
	t.Cleanup(srv.Close)
	_, errOut, err := runLm(t, srv, "entity", "export", "Customer", "-o", target)
	if err == nil {
		t.Fatal("publishing over an appeared file must fail")
	}
	if !strings.Contains(errOut, "could not remove the partial download") || !strings.Contains(errOut, ".part") {
		t.Errorf("stderr = %q, want the leftover named", errOut)
	}
}

// A target that could never be published is refused before the hub spends a snapshot on it.
func TestEntityExport_unwritableTargetIsRefusedBeforeAsking(t *testing.T) {
	dir := t.TempDir()
	notADir := filepath.Join(dir, "file")
	if err := os.WriteFile(notADir, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	for name, target := range map[string]string{
		"parent is a file": filepath.Join(notADir, "c.csv"),
		"parent missing":   filepath.Join(dir, "nope", "c.csv"),
	} {
		t.Run(name, func(t *testing.T) {
			var hits atomic.Int32
			_, _, err := runLm(t, csvHub(t, &hits), "entity", "export", "Customer", "-o", target)
			if err == nil || !strings.Contains(err.Error(), "cannot write") {
				t.Fatalf("err = %v", err)
			}
			if hits.Load() != 0 {
				t.Error("a target known to be unwritable must not cost the hub a snapshot")
			}
		})
	}
}
