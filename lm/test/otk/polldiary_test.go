package otk

import (
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/types"
)

// A run that never reaches a terminal status has failed the gold gate on four nights, and every
// time the harness said only THAT it happened — never what it saw. These pin the diary that turns
// the next occurrence into evidence.

func TestPollDiary_reportsWhatTheLastPollSaw(t *testing.T) {
	var d pollDiary
	for i := 0; i < 89; i++ {
		d.observed(types.Run{Status: "Running", RecordsIn: 3, RecordsOut: 0})
	}
	d.observed(types.Run{Status: "Running", RecordsIn: 3, RecordsOut: 0})

	got := d.String()
	for _, want := range []string{"90 poll", "Running", "endedAt=null", "recordsIn=3", "recordsOut=0"} {
		if !strings.Contains(got, want) {
			t.Errorf("diary must report %q, got: %s", want, got)
		}
	}
}

func TestPollDiary_countsPollsTheHubRefused(t *testing.T) {
	var d pollDiary
	d.observed(types.Run{Status: "Running"})
	for i := 0; i < 12; i++ {
		d.refused(7, "Error: API error 429: rate limited")
	}

	got := d.String()
	if !strings.Contains(got, "12 refused") {
		t.Errorf("a rate-limited poll storm must be visible, got: %s", got)
	}
	if !strings.Contains(got, "429") {
		t.Errorf("the last refusal must be quoted so a 429 names itself, got: %s", got)
	}
}

func TestPollDiary_saysSoWhenNoPollEverParsed(t *testing.T) {
	var d pollDiary
	d.refused(1, "connection refused")

	got := d.String()
	if !strings.Contains(got, "never returned a readable run") {
		t.Errorf("a diary with no parsed run must say so plainly, got: %s", got)
	}
}

func TestPollDiary_reportsATerminalStatusItSomehowSaw(t *testing.T) {
	ended := time.Date(2026, 7, 26, 4, 18, 35, 0, time.UTC)
	var d pollDiary
	d.observed(types.Run{Status: "Completed", EndedAt: &ended})

	got := d.String()
	if !strings.Contains(got, "Completed") || !strings.Contains(got, "2026-07-26T04:18:35Z") {
		t.Errorf("an endedAt that IS set must be shown — that contradiction is the finding: %s", got)
	}
}
