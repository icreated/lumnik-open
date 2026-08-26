package otk

import (
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/types"
)

// pollDiary records what the run-status poll loop actually saw, so a timeout can report WHY it
// gave up rather than only THAT it did.
//
// Four otk-gold nights (2026-07-15 → 07-26) failed on a single run never reaching a terminal
// status, and the message carried nothing but the run id and the timeout. Reading the hub log for
// 2026-07-26 afterwards showed the hub had ALREADY fired ConnectorRunCompleted for that very run —
// and finalizeRun commits status and endedAt before that event — so the run was finished while
// `lm run get` kept reporting it unfinished. Which of the two lied is exactly what the harness was
// not saying.
type pollDiary struct {
	polls    int
	refusals int
	last     *types.Run
	lastErr  string
	lastExit int
}

// observed records a poll that returned a readable run record.
func (d *pollDiary) observed(r types.Run) {
	d.polls++
	run := r
	d.last = &run
}

// refused records a poll the hub would not answer — a 429 storm looks like nothing else.
func (d *pollDiary) refused(exit int, stderr string) {
	d.polls++
	d.refusals++
	d.lastExit = exit
	d.lastErr = strings.TrimSpace(stderr)
}

func (d pollDiary) String() string {
	var b strings.Builder
	fmt.Fprintf(&b, "%d poll(s)", d.polls)
	if d.refusals > 0 {
		fmt.Fprintf(&b, ", %d refused (last exit %d: %s)", d.refusals, d.lastExit, quoteShort(d.lastErr))
	}
	if d.last == nil {
		b.WriteString("; the hub never returned a readable run")
		return b.String()
	}
	ended := "endedAt=null"
	if d.last.EndedAt != nil {
		ended = "endedAt=" + d.last.EndedAt.UTC().Format(time.RFC3339)
	}
	fmt.Fprintf(&b, "; last seen: status=%s, %s, recordsIn=%d, recordsOut=%d",
		d.last.Status, ended, d.last.RecordsIn, d.last.RecordsOut)
	if d.last.ErrorMessage != "" {
		fmt.Fprintf(&b, ", error=%s", quoteShort(d.last.ErrorMessage))
	}
	return b.String()
}

// quoteShort keeps a diagnostic quotable on one line without losing its head.
func quoteShort(s string) string {
	s = strings.TrimSpace(strings.ReplaceAll(s, "\n", " "))
	if len(s) > 160 {
		s = s[:160] + "…"
	}
	return strconv.Quote(s)
}
