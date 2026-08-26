package resources

import (
	"regexp"
	"strings"

	"github.com/rivo/tview"
)

// keyLine matches "  key: rest" — captures the indent, the (bare) key, and the rest.
var keyLine = regexp.MustCompile(`^(\s*)([\w.-]+)(:.*)$`)

// listKeyLine matches "  - key: rest" — a list item whose first field is a key.
var listKeyLine = regexp.MustCompile(`^(\s*)(- )([\w.-]+)(:.*)$`)

// highlightYAML returns the YAML text with tview color tags: comments dimmed,
// keys colored, values left plain. Content is tview-escaped FIRST so a literal
// '[' in a value renders as text, not markup (the describe panel uses dynamic
// colors). Structure (line count, indentation) is preserved.
func highlightYAML(text string) string {
	lines := strings.Split(text, "\n")
	for i, line := range lines {
		trimmed := strings.TrimLeft(line, " \t")
		switch {
		case strings.HasPrefix(trimmed, "#"):
			lines[i] = "[gray]" + tview.Escape(line) + "[white]"
		case listKeyLine.MatchString(line):
			m := listKeyLine.FindStringSubmatch(line)
			lines[i] = m[1] + m[2] + "[yellow]" + m[3] + "[white]" + tview.Escape(m[4])
		case keyLine.MatchString(line):
			m := keyLine.FindStringSubmatch(line)
			lines[i] = m[1] + "[yellow]" + m[2] + "[white]" + tview.Escape(m[3])
		default:
			lines[i] = tview.Escape(line)
		}
	}
	return strings.Join(lines, "\n")
}
