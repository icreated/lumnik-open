package cli

import (
	"strings"
	"testing"
)

// Help texts that once described a narrower command than the one shipped (#1242).
func TestHelpTexts_describeWhatTheCommandAccepts(t *testing.T) {
	root := NewRootCmd()
	for _, c := range []struct {
		path []string
		flag string // "" = the command's Short
		want string
	}{
		{[]string{"apply"}, "", "kind: Connector"},
		{[]string{"dlq", "list"}, "connector", "name"},
		{[]string{"search", "rebuild"}, "wait", "ready"},
	} {
		cmd, _, err := root.Find(c.path)
		if err != nil {
			t.Fatalf("%v: %v", c.path, err)
		}
		text := cmd.Short
		if c.flag != "" {
			text = cmd.Flag(c.flag).Usage
		}
		if !strings.Contains(text, c.want) {
			t.Errorf("%v %s: %q does not mention %q", c.path, c.flag, text, c.want)
		}
	}
}
