//go:build otk

package otk

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/runner"
)

// The export lane's hub-free half (#684): the real lm binary against a fake hub, so the parts of
// the contract that live entirely in lm — argument parsing, the bytes written, what a failure
// leaves on disk, what reaches the terminal — are held without LUMNIK_URL, in make otk-unit.
// The live lane (steps_export.go) holds the hub's side of the same contracts.

// exportLocalCSV carries a BOM, a CRLF, a non-ASCII name and a bidi override: lm must write the
// bytes it received, not a terminal-safe rewrite of them.
const exportLocalCSV = "\ufeffid,name\r\nC-1,Zoë\r\nC-2,\u202eowT KTO\r\n"

// fakeExportHub serves the doors lm's export commands call. firstChunk is signalled once the slow
// download has put its first bytes on the wire.
func fakeExportHub(t *testing.T, hits *atomic.Int32, firstChunk chan<- struct{}) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		switch r.URL.Path {
		case "/api/exports/entities/otk_customer/download":
			w.Header().Set("Content-Type", "text/csv;charset=UTF-8")
			_, _ = w.Write([]byte(exportLocalCSV))
		case "/api/exports/entities/scoped/download":
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusForbidden)
			_, _ = w.Write([]byte(`{"error":"scope not granted"}`))
		case "/api/exports/entities/truncated/download":
			// A chunked body cut off mid-stream: one chunk, then the connection closes without the
			// terminating zero-length chunk.
			conn, buf, err := w.(http.Hijacker).Hijack()
			if err != nil {
				t.Errorf("hijack: %v", err)
				return
			}
			_, _ = buf.WriteString("HTTP/1.1 200 OK\r\nContent-Type: text/csv\r\nTransfer-Encoding: chunked\r\n\r\n" +
				"10\r\nid,name\r\nC-1,Zo\r\n")
			_ = buf.Flush()
			_ = conn.Close()
		case "/api/exports/entities/slow/download":
			w.Header().Set("Content-Type", "text/csv")
			_, _ = w.Write([]byte("id,name\r\n"))
			w.(http.Flusher).Flush()
			firstChunk <- struct{}{}
			select {
			case <-r.Context().Done():
			case <-time.After(20 * time.Second):
			}
		case "/api/exports/otk-leaky":
			w.Header().Set("Content-Type", "application/yaml")
			_, _ = w.Write([]byte("kind: Export\nspec:\n  destination:\n    kind: s3\n" +
				"    endpoint: https://otk:" + generator.ExportSentinel + "@s3.example\n"))
		case "/api/exports/apply":
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":"'password' is not accepted — store the credential via ` +
				"`lm secret set`" + ` and reference it as ` + "`password_env`" + `"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

// buildLmForTest compiles lm from this checkout: the unit lane must not depend on `make build`
// having run, nor on a stale binary.
func buildLmForTest(t *testing.T) string {
	t.Helper()
	bin := filepath.Join(t.TempDir(), "lm")
	if runtime.GOOS == "windows" {
		bin += ".exe"
	}
	cmd := exec.Command("go", "build", "-o", bin, "./cmd/lm")
	cmd.Dir = filepath.Join("..", "..")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("go build lm: %v\n%s", err, out)
	}
	return bin
}

func dirNamesIn(t *testing.T, dir string) []string {
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

func assertNoSentinel(t *testing.T, r runner.Result) {
	t.Helper()
	if strings.Contains(r.Stdout+r.Stderr, generator.ExportSentinel) {
		t.Errorf("the export sentinel reached lm's output:\nstdout=%q\nstderr=%q", r.Stdout, r.Stderr)
	}
}

func TestExportLocal(t *testing.T) {
	bin := buildLmForTest(t)
	var hits atomic.Int32
	firstChunk := make(chan struct{}, 1)
	srv := fakeExportHub(t, &hits, firstChunk)
	t.Setenv("LUMNIK_URL", srv.URL)
	writeHermeticConfig(t)
	lm := func(args ...string) runner.Result {
		r := runner.Run(lmEnv(), bin, args...)
		if r.Panicked || r.TimedOut || !r.Started {
			t.Fatalf("lm %v: panicked=%v timedOut=%v started=%v\n%s", args, r.Panicked, r.TimedOut, r.Started, r.Stderr)
		}
		return r
	}

	t.Run("parsing refuses before asking the hub", func(t *testing.T) {
		cases := []struct {
			args []string
			want string
		}{
			{[]string{"entity", "export", "otk_customer"}, "-o FILE is required"},
			{[]string{"entity", "export", "-o", filepath.Join(t.TempDir(), "x.csv")}, "accepts 1 arg"},
			{[]string{"export", "apply"}, `"file" not set`},
			{[]string{"export", "run"}, "accepts 1 arg"},
			{[]string{"export", "retry", "only-a-name"}, "accepts 2 arg"},
		}
		for _, c := range cases {
			before := hits.Load()
			r := lm(c.args...)
			if r.Exit == 0 || !strings.Contains(r.Stderr, c.want) {
				t.Errorf("lm %v: exit %d, stderr %q — want a refusal naming %q", c.args, r.Exit, r.Stderr, c.want)
			}
			if hits.Load() != before {
				t.Errorf("lm %v reached the hub before refusing its arguments", c.args)
			}
		}
	})

	t.Run("a file receives the exact bytes and nothing else", func(t *testing.T) {
		dir := t.TempDir()
		target := filepath.Join(dir, "customers.csv")
		r := lm("entity", "export", "otk_customer", "-o", target)
		if r.Exit != 0 {
			t.Fatalf("exit %d: %s", r.Exit, r.Stderr)
		}
		got, err := os.ReadFile(target)
		if err != nil || string(got) != exportLocalCSV {
			t.Fatalf("file = %q (err %v), want the hub's exact bytes", got, err)
		}
		sum := sha256.Sum256([]byte(exportLocalCSV))
		if !strings.Contains(r.Stdout, hex.EncodeToString(sum[:])) {
			t.Errorf("stdout %q does not report the sha256", r.Stdout)
		}
		if names := dirNamesIn(t, dir); len(names) != 1 {
			t.Errorf("directory holds %v, want the target alone", names)
		}
	})

	t.Run("-o - writes the exact bytes to stdout", func(t *testing.T) {
		r := lm("entity", "export", "otk_customer", "-o", "-")
		if r.Exit != 0 || r.Stdout != exportLocalCSV {
			t.Errorf("exit %d, stdout %q — want the hub's exact bytes", r.Exit, r.Stdout)
		}
	})

	t.Run("an existing file is refused before asking the hub", func(t *testing.T) {
		dir := t.TempDir()
		target := filepath.Join(dir, "customers.csv")
		if err := os.WriteFile(target, []byte("precious"), 0o600); err != nil {
			t.Fatal(err)
		}
		before := hits.Load()
		r := lm("entity", "export", "otk_customer", "-o", target)
		if r.Exit == 0 {
			t.Error("exit 0 over an existing file")
		}
		if got, _ := os.ReadFile(target); string(got) != "precious" {
			t.Errorf("the existing file now holds %q", got)
		}
		if hits.Load() != before {
			t.Error("lm asked the hub for a snapshot it could never publish")
		}
		if names := dirNamesIn(t, dir); len(names) != 1 {
			t.Errorf("directory holds %v, want the existing file alone", names)
		}
	})

	t.Run("a truncated body leaves no file and no partial file", func(t *testing.T) {
		dir := t.TempDir()
		r := lm("entity", "export", "truncated", "-o", filepath.Join(dir, "customers.csv"))
		if r.Exit == 0 {
			t.Error("exit 0 on a truncated body")
		}
		if names := dirNamesIn(t, dir); len(names) != 0 {
			t.Errorf("a truncated download left %v", names)
		}
	})

	t.Run("a scope refusal passes through and leaves no artifact", func(t *testing.T) {
		dir := t.TempDir()
		r := lm("entity", "export", "scoped", "-o", filepath.Join(dir, "customers.csv"))
		if r.Exit == 0 || !strings.Contains(r.Stderr, "scope not granted") {
			t.Errorf("exit %d, stderr %q — want the hub's refusal", r.Exit, r.Stderr)
		}
		if names := dirNamesIn(t, dir); len(names) != 0 {
			t.Errorf("a refused download left %v", names)
		}
	})

	t.Run("an interrupt mid-stream leaves no file and no partial file", func(t *testing.T) {
		if runtime.GOOS == "windows" {
			t.Skip("SIGINT cannot be sent to a child process on Windows")
		}
		dir := t.TempDir()
		cmd := exec.Command(bin, "entity", "export", "slow", "-o", filepath.Join(dir, "customers.csv"))
		cmd.Env = append(os.Environ(), lmEnv()...)
		stderr, _ := cmd.StderrPipe()
		if err := cmd.Start(); err != nil {
			t.Fatal(err)
		}
		select {
		case <-firstChunk:
		case <-time.After(15 * time.Second):
			_ = cmd.Process.Kill()
			t.Fatal("the slow download never started")
		}
		// The first chunk is on the wire, not necessarily on disk: give lm a moment to have
		// created its temporary file, which is the state an interrupt must clean up.
		time.Sleep(200 * time.Millisecond)
		if err := cmd.Process.Signal(syscall.SIGINT); err != nil {
			t.Fatal(err)
		}
		msg, _ := bufio.NewReader(stderr).ReadString(0)
		err := cmd.Wait()
		var exit *exec.ExitError
		if !errors.As(err, &exit) {
			t.Fatalf("lm was not refused by the interrupt: %v (stderr %q)", err, msg)
		}
		if !strings.Contains(msg, "was not written") {
			t.Errorf("stderr %q does not say the file was not written", msg)
		}
		if names := dirNamesIn(t, dir); len(names) != 0 {
			t.Errorf("an interrupted download left %v", names)
		}
	})

	t.Run("export get masks credentials in a destination URL", func(t *testing.T) {
		r := lm("export", "get", "otk-leaky")
		if r.Exit != 0 || !strings.Contains(r.Stdout, "://***@") {
			t.Errorf("exit %d, stdout %q — want the manifest with its userinfo masked", r.Exit, r.Stdout)
		}
		assertNoSentinel(t, r)
	})

	t.Run("a refused inline credential is never echoed", func(t *testing.T) {
		manifest := filepath.Join(t.TempDir(), "export.yaml")
		body := "kind: Export\nspec:\n  destination:\n    kind: sftp\n    password: " + generator.ExportSentinel + "\n"
		if err := os.WriteFile(manifest, []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
		r := lm("export", "apply", "-f", manifest)
		if r.Exit == 0 || !strings.Contains(r.Stderr, "'password' is not accepted") {
			t.Errorf("exit %d, stderr %q — want the hub's refusal naming the field", r.Exit, r.Stderr)
		}
		assertNoSentinel(t, r)
	})
}
