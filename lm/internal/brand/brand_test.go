package brand

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/textsafe"
)

func TestWordmarkIsRectangular(t *testing.T) {
	lines := strings.Split(strings.Trim(Wordmark, "\n"), "\n")
	if len(lines) != 6 {
		t.Fatalf("the block wordmark is 6 rows, got %d", len(lines))
	}
	want := len([]rune(lines[0]))
	for i, l := range lines {
		// Ragged rows read as a rendering bug, not as a logo.
		if got := len([]rune(l)); got != want {
			t.Errorf("row %d is %d runes, row 0 is %d — the wordmark must be a rectangle", i, got, want)
		}
	}
	if want != Width {
		t.Errorf("Width says %d, the rows are %d wide", Width, want)
	}
}

func TestWordmarkSurvivesTheSafeWriter(t *testing.T) {
	// lm escapes control and bidi runes before they reach a terminal. Box-drawing and
	// block elements must not trip that guard, or the logo would arrive as \uXXXX soup.
	for _, r := range Wordmark + Tagline {
		if textsafe.DangerousRune(r) && r != '\n' {
			t.Errorf("rune %q (%U) would be escaped by the SafeWriter", r, r)
		}
	}
}

func TestBannerCarriesTheName(t *testing.T) {
	b := Banner(100)
	if !strings.Contains(b, "lumnik 2026") {
		t.Errorf("the banner must carry the name and year, got:\n%s", b)
	}
	if !strings.Contains(b, "██") {
		t.Errorf("a wide terminal gets the block wordmark, got:\n%s", b)
	}
}

func TestBannerFallsBackRatherThanWrapping(t *testing.T) {
	// A wrapped 49-column logo in a 40-column terminal is worse than no logo: every row
	// folds and the shape is lost. Below the threshold we print the name plainly.
	// NO_COLOR because this asserts on *columns*: with the gold on, the escape bytes count
	// as runes and the measurement would be of the sequence, not of what the eye sees.
	t.Setenv("NO_COLOR", "1")
	b := Banner(40)
	if strings.Contains(b, "██") {
		t.Errorf("a narrow terminal must not get the block wordmark, got:\n%s", b)
	}
	if !strings.Contains(b, "lumnik 2026") {
		t.Errorf("the fallback still carries the name, got:\n%s", b)
	}
	for _, l := range strings.Split(b, "\n") {
		if len([]rune(l)) > 40 {
			t.Errorf("fallback line is %d runes, wider than the terminal: %q", len([]rune(l)), l)
		}
	}
}

func TestBannerWearsTheGold(t *testing.T) {
	// The wordmark is chrome, so it wears lumière — the same accent as the TUI borders.
	b := Banner(100)
	if !strings.Contains(b, goldANSI) {
		t.Errorf("the wordmark must be gold, got:\n%q", b)
	}
	// The tagline stays plain: gold is for the logo, and a second gold line under it would flatten
	// the one thing the eye should land on.
	if strings.Contains(b[strings.LastIndex(b, resetANSI):], goldANSI) {
		t.Errorf("the tagline must stay plain, got:\n%q", b)
	}
	// The narrow fallback is still the product saying its name — it is gold too.
	if !strings.Contains(Banner(40), goldANSI) {
		t.Errorf("the fallback name must be gold, got:\n%q", Banner(40))
	}
}

func TestBannerClosesEveryLineItOpens(t *testing.T) {
	// A pager or a truncating terminal can cut between rows. Colour per line, so a cut
	// line can never bleed gold over everything printed after it.
	for _, l := range strings.Split(strings.Trim(Banner(100), "\n"), "\n") {
		if strings.Count(l, goldANSI) != strings.Count(l, resetANSI) {
			t.Errorf("line opens and closes unevenly: %q", l)
		}
	}
}

func TestBannerHonorsNoColor(t *testing.T) {
	t.Setenv("NO_COLOR", "1")
	for _, b := range []string{Banner(100), Banner(40), Banner(0)} {
		if strings.ContainsRune(b, 0x1B) {
			t.Errorf("NO_COLOR=1 must yield no escape sequence, got:\n%q", b)
		}
		if !strings.Contains(b, Name) {
			t.Errorf("the name survives losing its colour, got:\n%q", b)
		}
	}
}

func TestBannerHandlesAnUnknownWidth(t *testing.T) {
	// A pipe or a redirect reports width 0; assume a normal terminal rather than
	// degrading the one case where the output is most likely to be read by a human.
	if !strings.Contains(Banner(0), "██") {
		t.Errorf("width 0 (not a terminal) should still get the wordmark")
	}
}

func TestBannerSaysWhichBinaryIsRunning(t *testing.T) {
	// The login page is where someone first meets a binary they may not have built. Saying
	// nothing about which one it is leaves them to guess, and a guess about a version is how
	// two machines end up debugging different products.
	old := Version
	t.Cleanup(func() { Version = old })
	Version = "v9.9.9"

	if b := Banner(100); !strings.Contains(b, "v9.9.9") {
		t.Errorf("the wide banner must carry the version, got:\n%s", b)
	}
	// The narrow fallback drops the wordmark, never the identity.
	t.Setenv("NO_COLOR", "1")
	if b := Banner(40); !strings.Contains(b, "v9.9.9") {
		t.Errorf("the narrow fallback must carry it too, got:\n%s", b)
	}
}

func TestReleaseStampsThisPackagesVersionSymbol(t *testing.T) {
	// goreleaser's -X takes a STRING path. Point it at a symbol that does not exist and the
	// linker says nothing: the flag is dropped, Version keeps "dev", and every release prints
	// "dev" forever with no error anywhere. That is the failure this pins — the expectation is
	// DERIVED from where this file actually sits, so moving the package fails the test instead
	// of silently un-stamping the product.
	_, self, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("cannot locate this test file")
	}
	dir := filepath.Dir(self)
	root := dir
	for {
		if _, err := os.Stat(filepath.Join(root, "go.mod")); err == nil {
			break
		}
		parent := filepath.Dir(root)
		if parent == root {
			t.Fatal("no go.mod above " + dir)
		}
		root = parent
	}
	mod, err := os.ReadFile(filepath.Join(root, "go.mod"))
	if err != nil {
		t.Fatal(err)
	}
	module := strings.Fields(strings.SplitN(string(mod), "\n", 2)[0])[1]
	rel, err := filepath.Rel(root, dir)
	if err != nil {
		t.Fatal(err)
	}
	want := "-X " + module + "/" + filepath.ToSlash(rel) + ".Version="

	spec, err := os.ReadFile(filepath.Join(root, ".goreleaser.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(spec), want) {
		t.Errorf(".goreleaser.yaml must stamp %s\nIt does not — a release would ship Version=%q.", want, Version)
	}
}
