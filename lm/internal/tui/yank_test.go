package tui

import (
	"encoding/base64"
	"strings"
	"testing"
)

func TestBuildOSC52(t *testing.T) {
	got := buildOSC52("hello")
	want := "\x1b]52;c;" + base64.StdEncoding.EncodeToString([]byte("hello")) + "\x07"
	if got != want {
		t.Errorf("buildOSC52 mismatch:\n got=%q\nwant=%q", got, want)
	}
}

func TestBuildOSC52Empty(t *testing.T) {
	got := buildOSC52("")
	if !strings.HasPrefix(got, "\x1b]52;c;") || !strings.HasSuffix(got, "\x07") {
		t.Errorf("buildOSC52(\"\") must still emit a well-formed sequence, got %q", got)
	}
}
