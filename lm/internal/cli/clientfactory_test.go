package cli

import (
	"os"
	"path/filepath"
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
