package client

import (
	"encoding/json"
	"testing"

	"gopkg.in/yaml.v3"
)

func TestDetailSourceKeepsNullDistinctFromEmpty(t *testing.T) {
	var got []DetailSource
	if err := json.Unmarshal([]byte(`[{"src":"t","value":null},{"src":"t","value":""},{"src":"t","value":"A"}]`), &got); err != nil {
		t.Fatal(err)
	}
	if got[0].Value != nil || got[1].Value == nil || *got[1].Value != "" || *got[2].Value != "A" {
		t.Fatalf("null/empty/value not preserved: %+v", got)
	}
	if got[0].Display() != "(empty)" || got[1].Display() != "" || got[2].Display() != "A" {
		t.Fatalf("Display: %q %q %q", got[0].Display(), got[1].Display(), got[2].Display())
	}
	out, _ := json.Marshal(got[0])
	if string(out) != `{"src":"t","value":null}` {
		t.Fatalf("json re-emits %s", out)
	}
	y, _ := yaml.Marshal(got[0])
	if string(y) != "src: t\nvalue: null\n" {
		t.Fatalf("yaml re-emits %q", y)
	}
	if StrPtr("x") == nil || *StrPtr("x") != "x" {
		t.Fatal("StrPtr")
	}
}
