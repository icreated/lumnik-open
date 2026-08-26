package generator

import (
	"strings"
	"testing"
)

func TestGenerateSource_isDeterministic(t *testing.T) {
	a := GenerateSource(7)
	b := GenerateSource(7)
	if a.CSV != b.CSV {
		t.Fatal("GenerateSource must be deterministic for a seed")
	}
}

func TestGenerateSource_coversAllCategories(t *testing.T) {
	seen := map[string]bool{}
	for seed := int64(1); seed <= 40; seed++ {
		seen[GenerateSource(seed).Category] = true
	}
	for _, cat := range []string{"logical", "weird", "textual", "hack"} {
		if !seen[cat] {
			t.Errorf("seeds 1..40 never produced category %q", cat)
		}
	}
}

func TestGenerateSource_baselineHasNoIdColumn(t *testing.T) {
	header := strings.SplitN(baselineCSV(), "\n", 2)[0]
	for _, col := range strings.Split(header, ",") {
		if strings.EqualFold(strings.TrimSpace(col), "id") {
			t.Fatalf("baseline header must not contain an 'id' column: %q", header)
		}
	}
}

func TestSourceVariant_headerOnly_hasNoDataRows(t *testing.T) {
	v := findSourceVariant(t, "logical", "header-only")
	csv := v.build()
	lines := strings.Split(strings.TrimRight(csv, "\n"), "\n")
	if len(lines) != 1 {
		t.Fatalf("header-only must have exactly the header line, got %d lines: %q", len(lines), csv)
	}
}

func TestSourceVariant_emptyFile_isEmpty(t *testing.T) {
	v := findSourceVariant(t, "logical", "empty-file")
	if v.build() != "" {
		t.Fatalf("empty-file must be empty, got %q", v.build())
	}
}

func TestSourceVariant_bidiHeader_emitsBidiByte(t *testing.T) {
	// Proves the \u escape produced the real RTL-override byte in the data, even
	// though the Go source contains no literal bidi rune.
	v := findSourceVariant(t, "textual", "bidi-header")
	if !strings.ContainsRune(v.build(), '\u202e') {
		t.Fatal("bidi-header must contain U+202E in its output")
	}
}

func findSourceVariant(t *testing.T, cat, name string) sourceVariant {
	t.Helper()
	for _, v := range sourceVariantsByCategory[cat] {
		if v.name == name {
			return v
		}
	}
	t.Fatalf("source variant %s/%s not found", cat, name)
	return sourceVariant{}
}

func TestSourceVariant_typeConfusion_hasTextInAmountColumn(t *testing.T) {
	v := findSourceVariant(t, "logical", "type-confusion")
	if !strings.Contains(v.build(), "twenty") {
		t.Fatal("type-confusion: must put text 'twenty' in the numeric amount column")
	}
}

func TestSourceVariant_allEmptyValues_hasEmptyCells(t *testing.T) {
	v := findSourceVariant(t, "logical", "all-empty-values")
	if !strings.Contains(v.build(), ",,") {
		t.Fatal("all-empty-values: must produce consecutive commas (empty cells)")
	}
}

func TestSourceVariant_crlfInjection_hasCRLFInsideCell(t *testing.T) {
	v := findSourceVariant(t, "weird", "crlf-injection")
	csv := v.build()
	// RFC 4180 §2.6: a field containing CRLF must be enclosed in double-quotes.
	// Verify the CRLF is surrounded by a quoted field ("...\r\n..."), not a bare row terminator.
	// We look for the opening-quote prefix of the cell value right before \r\n.
	if !strings.Contains(csv, "\"al\r\n") {
		t.Fatal("crlf-injection: \\r\\n must appear inside a double-quoted field — got bare row terminator instead")
	}
}

func TestSourceVariant_deeplyNestedQuotes_hasQuadQuote(t *testing.T) {
	v := findSourceVariant(t, "weird", "deeply-nested-quotes")
	if !strings.Contains(v.build(), `""""`) {
		t.Fatal("deeply-nested-quotes: must contain four consecutive double-quote chars")
	}
}

func TestSourceVariant_zeroWidthJoiner_hasZWJ(t *testing.T) {
	v := findSourceVariant(t, "textual", "zero-width-joiner")
	if !strings.ContainsRune(v.build(), '\u200d') {
		t.Fatal("zero-width-joiner: must contain U+200D (ZERO WIDTH JOINER) in cell")
	}
}

func TestSourceVariant_rtlInDataCell_hasBidiRune(t *testing.T) {
	v := findSourceVariant(t, "textual", "rtl-in-data-cell")
	csv := v.build()
	if !strings.ContainsRune(csv, '\u202e') {
		t.Fatal("rtl-in-data-cell: must contain U+202E in data row, not header")
	}
	lines := strings.SplitN(csv, "\n", 3)
	if strings.ContainsRune(lines[0], '\u202e') {
		t.Fatal("rtl-in-data-cell: bidi rune must appear in data row, not header")
	}
}

func TestSourceVariant_log4shellInCell_hasJndi(t *testing.T) {
	v := findSourceVariant(t, "hack", "log4shell-in-cell")
	if !strings.Contains(v.build(), "${jndi:") {
		t.Fatal("log4shell-in-cell: must contain JNDI lookup expression")
	}
}

func TestSourceVariant_envExpansionInCell_hasDollarHOME(t *testing.T) {
	v := findSourceVariant(t, "hack", "env-expansion-in-cell")
	if !strings.Contains(v.build(), "${HOME}") {
		t.Fatal("env-expansion-in-cell: must contain ${HOME}")
	}
}

func TestGenerateRun_pairsManifestAndSource(t *testing.T) {
	rs := GenerateRun(7)
	if rs.SourceCSV != GenerateSource(7).CSV {
		t.Fatal("GenerateRun must carry the seed's adversarial source")
	}
	if !strings.Contains(rs.ManifestYAML, rs.SourcePath) {
		t.Fatalf("manifest must point at the per-seed source path %q", rs.SourcePath)
	}
	if !strings.Contains(rs.ManifestYAML, "connector_type: csv-file") {
		t.Fatal("manifest must be a valid csv-file connector")
	}
	if !strings.Contains(rs.Feature, "the generated manifest and adversarial source for seed 7") {
		t.Fatalf("feature missing the run Given step:\n%s", rs.Feature)
	}
	if !strings.Contains(rs.Feature, "I apply and run the connector") {
		t.Fatal("feature missing the apply-and-run When step")
	}
}
