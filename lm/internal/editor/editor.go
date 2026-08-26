package editor

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// EditYAML writes content to a temp file, spawns $EDITOR on it, reads it back,
// and reports whether content changed.
// Editor resolution: $LM_EDITOR -> $EDITOR -> vim -> nano -> vi
func EditYAML(filenameHint, content string) (string, bool, error) {
	ed := resolveEditor()
	if ed == "" {
		return "", false, fmt.Errorf("no editor available (set $LM_EDITOR or $EDITOR, or install vim/nano)")
	}

	ext := filepath.Ext(filenameHint)
	if ext == "" {
		ext = ".yaml"
	}
	tmp, err := os.CreateTemp("", "lm-edit-*"+ext)
	if err != nil {
		return "", false, err
	}
	path := tmp.Name()
	defer os.Remove(path)
	if _, err := tmp.WriteString(content); err != nil {
		tmp.Close()
		return "", false, err
	}
	tmp.Close()

	parts := splitCmd(ed)
	parts = append(parts, path)
	cmd := exec.Command(parts[0], parts[1:]...)
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		return "", false, fmt.Errorf("editor exited with error: %w", err)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		return "", false, err
	}
	got := string(data)
	return got, got != content, nil
}

func resolveEditor() string {
	for _, env := range []string{"LM_EDITOR", "EDITOR"} {
		if v := strings.TrimSpace(os.Getenv(env)); v != "" {
			if cmd := firstWord(v); lookPath(cmd) != "" || strings.Contains(v, " ") {
				return v
			}
		}
	}
	for _, fb := range []string{"vim", "nano", "vi"} {
		if lookPath(fb) != "" {
			return fb
		}
	}
	return ""
}

func firstWord(s string) string {
	parts := splitCmd(s)
	if len(parts) == 0 {
		return ""
	}
	return parts[0]
}

func splitCmd(s string) []string {
	var out []string
	var cur strings.Builder
	inQuote := byte(0)
	for i := 0; i < len(s); i++ {
		ch := s[i]
		switch {
		case inQuote != 0 && ch == '\\' && i+1 < len(s) && (s[i+1] == inQuote || s[i+1] == '\\'):
			// Handle escaped quote or backslash inside quotes
			cur.WriteByte(s[i+1])
			i++
		case inQuote != 0 && ch == inQuote:
			inQuote = 0
		case inQuote != 0:
			cur.WriteByte(ch)
		case ch == '"' || ch == '\'':
			inQuote = ch
		case ch == ' ' || ch == '\t':
			if cur.Len() > 0 {
				out = append(out, cur.String())
				cur.Reset()
			}
		default:
			cur.WriteByte(ch)
		}
	}
	if cur.Len() > 0 {
		out = append(out, cur.String())
	}
	return out
}

func lookPath(cmd string) string {
	p, _ := exec.LookPath(cmd)
	return p
}
