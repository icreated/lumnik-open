package output

import (
	"bytes"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/textsafe"
)

type row struct {
	Name   string
	Status string
}

func TestPrint_Table(t *testing.T) {
	buf := new(bytes.Buffer)
	rows := []row{{"a", "Running"}, {"bb", "Failed"}}
	err := Print(buf, "table", []string{"NAME", "STATUS"}, rows, func(r row) []string {
		return []string{r.Name, r.Status}
	})
	if err != nil {
		t.Fatal(err)
	}
	s := buf.String()
	if !strings.Contains(s, "NAME") || !strings.Contains(s, "Running") {
		t.Errorf("unexpected table output: %s", s)
	}
}

func TestPrint_JSON(t *testing.T) {
	buf := new(bytes.Buffer)
	rows := []row{{"a", "ok"}}
	err := Print(buf, "json", []string{"NAME", "STATUS"}, rows, nil)
	if err != nil {
		t.Fatal(err)
	}
	s := buf.String()
	if !strings.Contains(s, `"Name"`) || !strings.Contains(s, `"a"`) {
		t.Errorf("unexpected json: %s", s)
	}
}

func TestPrint_YAML(t *testing.T) {
	buf := new(bytes.Buffer)
	rows := []row{{"a", "ok"}}
	err := Print(buf, "yaml", []string{"NAME", "STATUS"}, rows, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "Name: a") {
		t.Errorf("unexpected yaml: %s", buf.String())
	}
}

// -o yaml names a key the way -o json does, in the same order: a struct carries json tags only, and
// yaml would otherwise lower-case the Go field name (cellErrors became cellerrors).
func TestYAML_usesTheJSONKeyNamesAndOrder(t *testing.T) {
	type run struct {
		RecordsIn  int64   `json:"recordsIn"`
		CellErrors int64   `json:"cellErrors"`
		Note       string  `json:"note,omitempty"`
		Flag       string  `json:"flag"`
		Ratio      float64 `json:"ratio"`
		EndedAt    *string `json:"endedAt"`
		SQL        string  `json:"sql"`
	}
	r := run{RecordsIn: 1000000, CellErrors: 2, Flag: "yes", Ratio: 0.5, SQL: "a <> 1\nb"}
	for name, c := range map[string]struct {
		print func(*bytes.Buffer) error
		want  string
	}{
		"Print": {func(b *bytes.Buffer) error { return Print(b, "yaml", nil, []run{r}, nil) },
			"- recordsIn: 1000000\n  cellErrors: 2\n  flag: \"yes\"\n  ratio: 0.5\n  endedAt: null\n  sql: |-\n    a <> 1\n    b\n"},
		"PrintObject": {func(b *bytes.Buffer) error { return PrintObject(b, "yaml", r) },
			"recordsIn: 1000000\ncellErrors: 2\nflag: \"yes\"\nratio: 0.5\nendedAt: null\nsql: |-\n    a <> 1\n    b\n"},
	} {
		buf := new(bytes.Buffer)
		if err := c.print(buf); err != nil {
			t.Fatal(err)
		}
		if got := buf.String(); got != c.want {
			t.Errorf("%s:\n%s\nwant:\n%s", name, got, c.want)
		}
	}
}

func TestPrint_UnknownFormat(t *testing.T) {
	if err := Print(new(bytes.Buffer), "xml", nil, []row{}, nil); err == nil {
		t.Error("expected error for unknown format")
	}
}

func TestPrint_jsonKeepsBidiRaw(t *testing.T) {
	var raw bytes.Buffer
	w := textsafe.NewWriter(&raw)
	rows := []row{{Name: "a" + string(rune(0x202E)) + "b", Status: "ok"}}
	if err := Print(w, "json", nil, rows, nil); err != nil {
		t.Fatal(err)
	}
	// machine contract: the bidi rune must survive byte-exact (NOT escaped)
	if !bytes.ContainsRune(raw.Bytes(), rune(0x202E)) {
		t.Fatalf("json output must stay raw, got %q", raw.String())
	}
}

func TestPrint_tableEscapesBidi(t *testing.T) {
	var raw bytes.Buffer
	w := textsafe.NewWriter(&raw)
	rows := []row{{Name: "a" + string(rune(0x202E)) + "b", Status: "ok"}}
	err := Print(w, "table", []string{"NAME", "STATUS"}, rows, func(r row) []string {
		return []string{r.Name, r.Status}
	})
	if err != nil {
		t.Fatal(err)
	}
	if bytes.ContainsRune(raw.Bytes(), rune(0x202E)) {
		t.Fatalf("table output must escape bidi, got %q", raw.String())
	}
	if !strings.Contains(raw.String(), "202E") {
		t.Fatalf("table output should contain escaped form, got %q", raw.String())
	}
}

// lm's JSON goes to a terminal or a script, never into an HTML page: & < > are written as they are, the way the hub
// sends them, not as &-style escapes (#1105 review). A SQL comparison must read `<>` in `-o json`.
func TestPrint_jsonWritesHTMLCharactersAsTheyAre(t *testing.T) {
	type doc struct {
		SQL string `json:"sql"`
	}
	for name, print := range map[string]func(*bytes.Buffer) error{
		"Print":       func(b *bytes.Buffer) error { return Print(b, "json", nil, []doc{{"a <> 'x' AND b >= 1 & c"}}, nil) },
		"PrintObject": func(b *bytes.Buffer) error { return PrintObject(b, "json", doc{"a <> 'x' AND b >= 1 & c"}) },
	} {
		buf := new(bytes.Buffer)
		if err := print(buf); err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(buf.String(), `"sql": "a <> 'x' AND b >= 1 & c"`) {
			t.Errorf("%s: %s", name, buf.String())
		}
	}
}
