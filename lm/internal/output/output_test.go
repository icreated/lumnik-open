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
	if !strings.Contains(buf.String(), "name: a") {
		t.Errorf("unexpected yaml: %s", buf.String())
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
