package cli

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// jdbcEcho stands up a hub that records the credential the CLI sent, so a test can assert on
// what actually left the machine rather than on what the reader returned.
func jdbcEcho(t *testing.T) map[string]any {
	t.Helper()
	got := map[string]any{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&got)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true,"dialect":"postgres","tables":[]}`))
	}))
	t.Cleanup(srv.Close)
	setupCfg(t, srv.URL)
	return got // a map is already a reference; json.Decode fills it in place
}

// The password must come from the command's input, not from the process's os.Stdin: a reader
// bound to os.Stdin cannot be driven by a test, which is why this path went unproven until now.
func TestJdbcPassword_isReadFromTheCommandsOwnInput(t *testing.T) {
	got := jdbcEcho(t)
	_, _, err := runCLI(t, "s3cret\n", "source", "jdbc", "test",
		"--url", "jdbc:postgresql://h/d", "--user", "u", "--password-stdin")
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if got["password"] != "s3cret" {
		t.Errorf("hub received password %q, want %q", got["password"], "s3cret")
	}
}

// Trailing CR/LF belongs to the transport, never to the secret — a password ending in \r reaches
// the source as a different password, and the refusal that follows names the credential, not us.
func TestJdbcPassword_dropsTheLineEndingNotTheSecret(t *testing.T) {
	got := jdbcEcho(t)
	if _, _, err := runCLI(t, "s3 cret \r\n", "source", "jdbc", "test",
		"--url", "jdbc:postgresql://h/d", "--user", "u", "--password-stdin"); err != nil {
		t.Fatalf("execute: %v", err)
	}
	if got["password"] != "s3 cret " {
		t.Errorf("hub received %q — inner spaces are part of a password, the line ending is not", got["password"])
	}
}

// docker and helm make the pipe explicit with --password-stdin. A pipe nobody declared still
// works here, but it must say so: `lm source jdbc add … < list.txt` otherwise sends that file's
// first line to the hub as a password, in silence.
func TestJdbcPassword_anUndeclaredPipeIsAnnouncedAndNamesTheFlag(t *testing.T) {
	jdbcEcho(t)
	_, stderr, err := runCLI(t, "s3cret\n", "source", "jdbc", "test",
		"--url", "jdbc:postgresql://h/d", "--user", "u")
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if !strings.Contains(stderr, "--password-stdin") {
		t.Errorf("an undeclared pipe was read in silence; stderr = %q", stderr)
	}
}

func TestJdbcPassword_aDeclaredPipeIsNotNagged(t *testing.T) {
	jdbcEcho(t)
	_, stderr, err := runCLI(t, "s3cret\n", "source", "jdbc", "test",
		"--url", "jdbc:postgresql://h/d", "--user", "u", "--password-stdin")
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if stderr != "" {
		t.Errorf("the flag was given and the CLI still complained: %q", stderr)
	}
}

// Neither the warning nor the prompt may reach stdout: `discover --output json` writes a document
// there, and a machine consumer parses it.
func TestJdbcPassword_saysNothingOnStdout(t *testing.T) {
	jdbcEcho(t)
	stdout, _, err := runCLI(t, "s3cret\n", "source", "jdbc", "discover",
		"--url", "jdbc:postgresql://h/d", "--user", "u", "--output", "json")
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if !json.Valid([]byte(stdout)) {
		t.Errorf("stdout is no longer a JSON document: %q", stdout)
	}
}

// The inventory guard, anchored on the production tree rather than on a list I maintain: a jdbc
// command taking --url and --user opens a database with a credential of its own, so it must offer
// the declared way to hand one over. `list-tables` takes neither — it addresses a source the hub
// already holds — so its own shape excludes it, and a fourth command would be caught by shape too.
func TestJdbcPassword_everyCommandThatOpensASourceDeclaresTheFlag(t *testing.T) {
	var checked int
	for _, sub := range newSourceJdbcCmd().Commands() {
		if sub.Flags().Lookup("url") == nil || sub.Flags().Lookup("user") == nil {
			continue
		}
		checked++
		if sub.Flags().Lookup("password-stdin") == nil {
			t.Errorf("`source jdbc %s` reaches a database with a credential but has no --password-stdin",
				sub.Name())
		}
	}
	if checked != 3 {
		t.Fatalf("guard covered %d commands, expected test/discover/add — the shape it keys on moved", checked)
	}
}
