package cli

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"gopkg.in/yaml.v3"
)

// decodeManifest reads a YAML manifest of the given kind ("connector", "hook") and refuses, in
// the reader's own terms, anything that is not one.
//
// The parse is the detector, deliberately: yaml.Unmarshal into a map fails exactly when the file
// is not a mapping, and it is the same step that would have consumed the manifest. A peek at the
// first line would be a second judge able to disagree with it — refusing a manifest that opens on
// a comment, or waving through a file whose first line is fine and whose body is broken.
//
// But only a SHAPE failure means "wrong file". yaml.v3 separates the two exactly, so this does
// too (measured, not assumed):
//
//	CSV, or a top-level list  -> *yaml.TypeError  ("cannot unmarshal !!str … into map")
//	missing colon, bad indent -> plain error      ("yaml: line 2: could not find expected ':'")
//
// The first is someone pointing -f at the wrong file and deserves the friendly refusal. The
// second is a REAL manifest with a typo, and the reader wants the line number, not advice about
// CSVs — so that error is handed back untouched. Swallowing it would have traded one unhelpful
// message for another.
//
// The kind is a parameter because it must be: one shared decoder saying "connector" at
// `lm hook apply` would be a message that lies at one site, which is worse than the two honest
// duplicates it replaced.
func decodeManifest(path, kind string) (map[string]any, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err // a missing or unreadable file is not a malformed manifest
	}

	var payload map[string]any
	if err := yaml.Unmarshal(data, &payload); err != nil {
		var shape *yaml.TypeError
		if !errors.As(err, &shape) {
			return nil, err // a syntax error in a real manifest: its line number is the message
		}
		return nil, notAManifest(path, data, kind, "YAML expects `key: value` lines")
	}
	if len(payload) == 0 {
		return nil, notAManifest(path, data, kind, "it is empty")
	}
	if err := refuseManifestEnvelope(path, payload, kind); err != nil {
		return nil, err
	}
	return payload, nil
}

// refuseManifestEnvelope catches the opposite mistake to the CSV one: a perfectly good manifest
// handed to a command that wants the FLAT admin payload instead.
//
// `lm connector apply` posts its YAML as JSON to POST /api/platform/connectors, whose request is
// (tenantId, name, typeId, schedule, enabled, config). A kind: Connector manifest shares none of
// those names, so every field arrives null — tenantId included, it being a nullable Long — and
// the insert carries a NULL tenant_id, which the row-level security policy never matches. The
// refusal surfaces as an anonymous 500 with an error id. The reader wrote a valid file; nothing
// told them it was the wrong door.
//
// An envelope (apiVersion or kind) is what tells the two apart: the flat payload has neither.
// Only the connector side has somewhere to send them — `lm apply` posts the YAML untouched to the
// endpoint that parses manifests — so only it offers the redirect. Elsewhere the diagnosis stands
// alone rather than inventing a door.
func refuseManifestEnvelope(path string, payload map[string]any, kind string) error {
	envelope, _ := payload["kind"].(string)
	if envelope == "" && payload["apiVersion"] == nil {
		return nil
	}
	msg := fmt.Sprintf("%s is a manifest", filepath.Base(path))
	if envelope != "" {
		msg += fmt.Sprintf(" (kind: %s)", envelope)
	}
	msg += fmt.Sprintf(", not the flat %s payload this command takes.", kind)
	if kind == "connector" {
		msg += fmt.Sprintf("\nTo apply a manifest: lm apply -f %s", shellArg(path))
	}
	return errors.New(msg)
}

// notAManifest says what happened, then — only if it can guess — what would have worked.
// The guess never gates anything: when it is wrong or absent the refusal still stands, the reader
// simply gets no tip.
func notAManifest(path string, data []byte, kind, why string) error {
	msg := fmt.Sprintf("%s is not a %s manifest: %s", filepath.Base(path), kind, why)
	if looksLikeCsv(path, data) {
		msg += fmt.Sprintf("\nIt looks like a CSV — to ingest it: lm csv ingest %s --scope <scope>",
			shellArg(path))
	}
	return errors.New(msg)
}

// shellArg makes the suggested command pasteable in the shell the reader is actually typing in.
func shellArg(path string) string { return quoteFor(runtime.GOOS, path) }

// quoteFor is shellArg with the platform passed in, so both styles can be stated as a table
// rather than inferred from whatever host runs the test.
//
// Windows is not an afterthought: docs/deploy-windows.md has the reader run `.\lm.exe` in
// PowerShell — WSL is where the stack lives, PowerShell is where the CLI is typed. Single quotes
// are literal in both shells, so the common case is shared; two things differ.
//
//	an embedded quote  POSIX: '\''   PowerShell: ''  (doubled)
//	\ and : in a path  POSIX: quote   Windows: ordinary, leave bare
//
// Quoting only what needs it, on each side, keeps the noise off the one line the reader copies.
func quoteFor(goos, path string) string {
	windows := goos == "windows"
	if strings.IndexFunc(path, func(r rune) bool { return !isPlainPathRune(r, windows) }) < 0 {
		return path
	}
	if windows {
		return "'" + strings.ReplaceAll(path, "'", "''") + "'"
	}
	return "'" + strings.ReplaceAll(path, "'", `'\''`) + "'"
}

func isPlainPathRune(r rune, windows bool) bool {
	switch {
	case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9':
		return true
	case r == '.' || r == '_' || r == '-' || r == '/' || r == '~':
		return true
	case windows && (r == '\\' || r == ':'):
		return true
	default:
		return false
	}
}

// looksLikeCsv guesses from the first non-blank line: comma-separated fields, or failing that a
// .csv name. The extension IS allowed to say yes on its own — a single-column CSV carries no
// comma at all, and then the name is the only signal there is. What it may never do is say no:
// a CSV named .txt is still a CSV, and a manifest misnamed .csv still parses, because the parse
// decided long before this was asked.
func looksLikeCsv(path string, data []byte) bool {
	// Scanned line by line over the bytes, never split into strings: the file this runs on is by
	// definition the one someone pointed -f at by mistake, and that is typically a data export.
	// Copying a 500 MB CSV into strings to read its first line would spend the most memory on
	// exactly the case this hint exists for.
	for len(data) > 0 {
		line := data
		if i := bytes.IndexByte(data, '\n'); i >= 0 {
			line, data = data[:i], data[i+1:]
		} else {
			data = nil
		}
		line = bytes.TrimSpace(line)
		if len(line) == 0 || line[0] == '#' {
			continue
		}
		if bytes.Contains(line, []byte(": ")) || line[len(line)-1] == ':' {
			return false // a key: value line — whatever is broken here, it is not a CSV
		}
		return bytes.IndexByte(line, ',') >= 0 || hasCsvExt(path)
	}
	return hasCsvExt(path)
}

func hasCsvExt(path string) bool { return strings.EqualFold(filepath.Ext(path), ".csv") }
