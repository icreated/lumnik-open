package generator

import (
	"strings"
	"testing"
)

func findVariant(t *testing.T, cat, name string) variant {
	t.Helper()
	for _, v := range variantsByCategory[cat] {
		if v.name == name {
			return v
		}
	}
	t.Fatalf("manifest variant %s/%s not found", cat, name)
	return variant{}
}

func TestCsvVariant_archiveNoBucket_setsArchiveMode(t *testing.T) {
	v := findVariant(t, "logical", "archive-no-bucket")
	m := baseline(1)
	v.mutate(&m, "")
	if m.afterProcess != "archive" {
		t.Fatalf("afterProcess = %q, want %q", m.afterProcess, "archive")
	}
	if m.archivePath != "" {
		t.Fatalf("archivePath = %q, want empty (no bucket path)", m.archivePath)
	}
}

func TestCsvVariant_unicodeGlobPath_containsNonAscii(t *testing.T) {
	v := findVariant(t, "weird", "unicode-glob-path")
	m := baseline(1)
	v.mutate(&m, "")
	if !strings.ContainsRune(m.path, 'é') {
		t.Fatalf("path %q must contain non-ASCII rune é", m.path)
	}
}

func TestCsvVariant_zeroWidthName_containsFEFF(t *testing.T) {
	v := findVariant(t, "textual", "zero-width-name")
	m := baseline(1)
	v.mutate(&m, "")
	if !strings.ContainsRune(m.name, '\uFEFF') {
		t.Fatalf("name %q must contain U+FEFF (zero-width no-break space)", m.name)
	}
}

func TestCsvVariant_envTemplateInPath_containsDollarBrace(t *testing.T) {
	v := findVariant(t, "hack", "env-template-in-path")
	m := baseline(1)
	v.mutate(&m, "${HOME}/*.csv")
	if !strings.Contains(m.path, "${") {
		t.Fatalf("path %q must contain template marker ${", m.path)
	}
}

func TestCsvVariant_sstiInName_containsTemplateSyntax(t *testing.T) {
	v := findVariant(t, "hack", "ssti-in-name")
	m := baseline(1)
	v.mutate(&m, "{{7*7}}")
	if !strings.Contains(m.name, "{{") {
		t.Fatalf("name %q must contain template syntax {{", m.name)
	}
}
