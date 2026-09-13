package cli

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// secretEcho records what the CLI sent, and whether it sent anything at all.
func secretEcho(t *testing.T) (body map[string]string, calls *int) {
	t.Helper()
	got := map[string]string{}
	n := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n++
		_ = json.NewDecoder(r.Body).Decode(&got)
		w.WriteHeader(201)
	}))
	t.Cleanup(srv.Close)
	setupCfg(t, srv.URL)
	return got, &n // the map is a reference already; only the counter needs its pointer
}

// runCLI drives a real command through the root, with stdin on the command's own input — the
// shared driver for every test in this package that needs to answer a prompt or feed a pipe.
func runCLI(t *testing.T, stdin string, args ...string) (stdout, stderr string, err error) {
	t.Helper()
	root := NewRootCmd()
	out, errOut := &strings.Builder{}, &strings.Builder{}
	root.SetOut(out)
	root.SetErr(errOut)
	root.SetIn(strings.NewReader(stdin))
	root.SetArgs(args)
	err = root.Execute()
	return out.String(), errOut.String(), err
}

// A value passed positionally is in the shell's history file and in `ps` before the command
// returns. It keeps working — scripts depend on it — but it must not stay silent about it.
func TestSecretSet_aValueOnTheCommandLineSaysItIsExposed(t *testing.T) {
	body, _ := secretEcho(t)
	stdout, stderr, err := runCLI(t, "", "secret", "set", "my-key", "my-value")
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if body["value"] != "my-value" {
		t.Errorf("the value no longer reaches the hub: %v", body)
	}
	if !strings.Contains(stderr, "history") {
		t.Errorf("a secret went into the shell history in silence; stderr = %q", stderr)
	}
	if strings.Contains(stdout, "history") {
		t.Errorf("the warning landed on stdout: %q", stdout)
	}
}

// The '-' form reads until EOF, and must keep doing so: a PEM key is many lines, and a reader
// that stopped at the first one would store a truncated secret and say "Created".
func TestSecretSet_theStdinFormStillTakesEveryLine(t *testing.T) {
	body, _ := secretEcho(t)
	pem := "-----BEGIN KEY-----\nline1\nline2\n-----END KEY-----\n"
	if _, _, err := runCLI(t, pem, "secret", "set", "my-key", "-"); err != nil {
		t.Fatalf("execute: %v", err)
	}
	if body["value"] != strings.TrimRight(pem, "\n") {
		t.Errorf("multi-line secret was cut: %q", body["value"])
	}
}

// Reading the sentinel from the command's own input, not the process's os.Stdin — the same
// binding that kept the jdbc password reader untestable.
func TestSecretSet_theStdinFormReadsTheCommandsInput(t *testing.T) {
	body, _ := secretEcho(t)
	if _, _, err := runCLI(t, "from-the-command\n", "secret", "set", "k", "-"); err != nil {
		t.Fatalf("execute: %v", err)
	}
	if body["value"] != "from-the-command" {
		t.Errorf("value = %q, want the command's input", body["value"])
	}
}

// With no VALUE and no keyboard there is nothing to prompt and nothing declared to read, so the
// command must refuse and name the piped form rather than guess. Nothing may reach the hub.
func TestSecretSet_withNoValueAndNoKeyboardRefusesAndNamesTheStdinForm(t *testing.T) {
	_, calls := secretEcho(t)
	_, _, err := runCLI(t, "whatever\n", "secret", "set", "my-key")
	if err == nil {
		t.Fatal("a valueless set outside a terminal was accepted")
	}
	if !strings.Contains(err.Error(), "-") {
		t.Errorf("the refusal does not name the piped form: %v", err)
	}
	if *calls != 0 {
		t.Errorf("the hub was called %d times on a refused set", *calls)
	}
}

// `set NAME` is now a valid shape, so the argument validator must accept one argument — while
// still refusing three.
func TestSecretSet_acceptsNameAloneAndStillRefusesThree(t *testing.T) {
	set := newSecretSetCmd()
	if err := set.Args(set, []string{"name"}); err != nil {
		t.Errorf("`secret set NAME` rejected: %v", err)
	}
	if err := set.Args(set, []string{"name", "value", "extra"}); err == nil {
		t.Error("`secret set NAME VALUE EXTRA` accepted")
	}
}
