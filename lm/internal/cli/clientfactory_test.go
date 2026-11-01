package cli

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/spf13/cobra"
)

func TestClientFromCmd_UsesContext(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("HOME", dir)
	_ = os.MkdirAll(filepath.Join(dir, ".lm"), 0o700)
	_ = os.WriteFile(filepath.Join(dir, ".lm", "config.yaml"),
		[]byte("current-context: local\ncontexts:\n  local:\n    server: http://h\n    token: t\n"),
		0o600)

	root := NewRootCmd()
	cmd := &cobra.Command{}
	root.AddCommand(cmd)
	root.SetArgs([]string{cmd.Name()})

	c, err := ClientFromCmd(root)
	if err != nil {
		t.Fatal(err)
	}
	if c == nil {
		t.Fatal("client is nil")
	}
}

func TestClientFromCmd_NoConfig_Fails(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("HOME", dir)
	root := NewRootCmd()
	if _, err := ClientFromCmd(root); err == nil {
		t.Error("expected error when no current-context set")
	}
}

// #1610 (review of #1614): the remote-https guard holds on the EFFECTIVE server — a local dev-bypass context sent to
// a remote https hub by --server or LUMNIK_URL is refused, not only one written by set-context.
func TestClientFromCmd_RefusesADevBypassIdentityOnARemoteHttpsOverride(t *testing.T) {
	for name, setup := range map[string]func(t *testing.T) []string{
		"--server":   func(t *testing.T) []string { return []string{"--server", "https://hub.example"} },
		"LUMNIK_URL": func(t *testing.T) []string { t.Setenv("LUMNIK_URL", "https://hub.example"); return nil },
	} {
		t.Run(name, func(t *testing.T) {
			dir := t.TempDir()
			t.Setenv("HOME", dir)
			_ = os.MkdirAll(filepath.Join(dir, ".lm"), 0o700)
			_ = os.WriteFile(filepath.Join(dir, ".lm", "config.yaml"),
				[]byte("current-context: kind\ncontexts:\n  kind:\n    server: http://localhost:18090\n    tenant: \"1\"\n    user: \"1\"\n"), 0o600)
			root := NewRootCmd()
			cmd := &cobra.Command{Use: "probe", RunE: func(c *cobra.Command, _ []string) error { _, err := ClientFromCmd(c); return err }}
			root.AddCommand(cmd)
			root.SetArgs(append([]string{"probe"}, setup(t)...))
			err := root.Execute()
			if err == nil || !strings.Contains(err.Error(), "dev-bypass identity") || !strings.Contains(err.Error(), "hub.example") {
				t.Fatalf("want a refusal naming the host, got %v", err)
			}
		})
	}
}
