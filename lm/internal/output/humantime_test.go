package output

import (
	"testing"
	"time"
)

func TestHumanTime(t *testing.T) {
	if got := HumanTime(nil); got != "" {
		t.Errorf("nil renders empty, got %q", got)
	}
	ts := time.Date(2026, 9, 2, 12, 38, 47, 22217000, time.UTC)
	want := ts.In(time.Local).Format("2006-01-02 15:04")
	if got := HumanTime(&ts); got != want {
		t.Errorf("got %q want %q", got, want)
	}
}

func TestHumanTimeStr(t *testing.T) {
	// The exact shape lm scopes used to print raw.
	got := HumanTimeStr("2026-09-02T12:38:47.022217Z")
	ts, err := time.Parse(time.RFC3339Nano, "2026-09-02T12:38:47.022217Z")
	if err != nil {
		t.Fatalf("the sample timestamp must parse: %v", err)
	}
	if want := ts.In(time.Local).Format("2006-01-02 15:04"); got != want {
		t.Errorf("got %q want %q", got, want)
	}
	if got := HumanTimeStr("not-a-date"); got != "not-a-date" {
		t.Errorf("unparseable must pass through verbatim, got %q", got)
	}
	if got := HumanTimeStr(""); got != "" {
		t.Errorf("empty stays empty, got %q", got)
	}
}

func TestHumanTimeStrOr(t *testing.T) {
	if got := HumanTimeStrOr("", "—"); got != "—" {
		t.Errorf("absent must read the stand-in, got %q", got)
	}
	if got, want := HumanTimeStrOr("2026-09-02T12:38:47Z", "—"), HumanTimeStr("2026-09-02T12:38:47Z"); got != want {
		t.Errorf("present must render as HumanTimeStr: got %q, want %q", got, want)
	}
}
