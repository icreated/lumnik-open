package cli

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestHumanizeAge(t *testing.T) {
	now := time.Date(2026, 7, 5, 12, 0, 0, 0, time.UTC)
	assert.Equal(t, "à l'instant", humanizeAge(now.Add(-30*time.Second), now))
	assert.Equal(t, "5min", humanizeAge(now.Add(-5*time.Minute), now))
	assert.Equal(t, "2h", humanizeAge(now.Add(-2*time.Hour), now))
	assert.Equal(t, "hier", humanizeAge(now.Add(-30*time.Hour), now))
	assert.Equal(t, "3j", humanizeAge(now.Add(-72*time.Hour), now))
}

func TestQuandOf(t *testing.T) {
	now := time.Date(2026, 7, 5, 12, 0, 0, 0, time.UTC)
	// fractional seconds (Java Instant.toString with sub-second precision) must still humanize
	// (elapsed is 1h59m59.876544s; humanizeAge truncates, so the bucket is "1h", not "2h")
	assert.Equal(t, "1h", quandOf("2026-07-05T10:00:00.123456Z", now))
	// plain RFC3339 (no fraction) also humanizes
	assert.Equal(t, "5min", quandOf("2026-07-05T11:55:00Z", now))
	// unparseable → raw string fallback (never a crash)
	assert.Equal(t, "not-a-time", quandOf("not-a-time", now))
}

func TestInboxRow_ackedMarker(t *testing.T) {
	now := time.Date(2026, 7, 5, 12, 0, 0, 0, time.UTC)
	e := client.InboxEntry{ID: 4212, OccurredAt: "2026-07-05T10:00:00Z",
		Process: "lyon-client-arrive", Message: "Nouveau client à Lyon", EntityKey: "e717", Acked: true}
	row := inboxRow(e, now)
	// columns: ID(0) WHEN(1) PROCESS(2) MESSAGE(3) ENTITY_KEY(4)
	assert.Equal(t, "4212", row[0]) // the ID column
	assert.Contains(t, row[3], "[traité]")
	e.Acked = false
	assert.NotContains(t, inboxRow(e, now)[3], "traité")
}

// An English header in an English CLI: WHEN, as on :outputs (#1175).
func TestInboxCmd_header(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`[{"id":1,"occurredAt":"2026-07-05T10:00:00Z","process":"p","message":"m","entityKey":"k"}]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)
	out := runRoot(t, "inbox")
	assert.Equal(t, []string{"ID", "WHEN", "PROCESS", "MESSAGE", "ENTITY_KEY"}, strings.Fields(strings.SplitN(out, "\n", 2)[0]))
}

func TestInboxAckCmd_requiresOneIntArg(t *testing.T) {
	cmd := newInboxCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"ack"}) // missing the ID
	err := cmd.Execute()
	require.Error(t, err)
	assert.Contains(t, err.Error(), "arg")
}

func TestInboxCmd_limitFlagIsWired(t *testing.T) {
	// An empty home: "no context configured" must not depend on the developer's own ~/.lm/config.yaml,
	// or the command reaches their real hub and succeeds.
	t.Setenv("HOME", t.TempDir())
	cmd := newInboxCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"--limit", "10"})
	err := cmd.Execute()

	// no context configured → fails at client resolution, NOT flag parsing
	require.Error(t, err)
	assert.NotContains(t, err.Error(), "unknown flag")
}
