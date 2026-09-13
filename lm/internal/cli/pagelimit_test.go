package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/spf13/cobra"
)

// Every command that pages must say when the page came back full — a reader handed exactly as
// many rows as they asked for cannot tell a complete answer from a truncated one, and these
// tables are read to decide whether something ran, failed or is still waiting.
//
// The guard is an INVENTORY, anchored on the command tree itself rather than on a list of names
// kept by hand: whatever declares a --limit is driven here, so a seventh paging command is
// covered the day it is written, and a note deleted from any of the six goes red.
//
// What it keys on is the flag NAME, which is the guard's own edge: a page offered under another
// spelling (`lm rag search --top-k`) is invisible here. printPage is the belt for that — a page
// printed through it announces itself whatever its flag is called.
func TestEveryPagingCommandAnnouncesAFullPage(t *testing.T) {
	const page = 3
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Exactly `page` items, shapeless: every one of these doors decodes a plain JSON array,
		// and none of them reads a field to decide whether the page is full.
		_ = json.NewEncoder(w).Encode(make([]map[string]any, page))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	var paging, rowBudget [][]string
	var walk func(c *cobra.Command, path []string)
	walk = func(c *cobra.Command, path []string) {
		if c.Runnable() && c.LocalFlags().Lookup("limit") != nil {
			if _, isPage := c.Annotations[pageLimitCapAnnotation]; isPage {
				paging = append(paging, path)
			} else {
				rowBudget = append(rowBudget, path)
			}
		}
		for _, sub := range c.Commands() {
			walk(sub, append(append([]string{}, path...), sub.Name()))
		}
	}
	walk(NewRootCmd(), nil)

	// A walk that finds nothing would pass every assertion below without running anything.
	if len(paging) < 6 {
		t.Fatalf("expected at least the six known paging commands, found %d: %v", len(paging), paging)
	}

	// The other axis: --limit also names a row budget handed to the engine, which has no page
	// boundary to announce. Those are frozen by NAME rather than counted — a count survives one
	// being swapped for a new paging command that forgot addPageLimitFlag. A new --limit landing
	// here means: page → register it with addPageLimitFlag; budget → add it below with a word.
	frozen := map[string]string{
		"csv ingest":        "--limit is the number of ROWS to ingest (-1 = all), not a page",
		"source csv ingest": "the same command, reachable under its source-first spelling",
		"source rest run":   "--limit is how many sample items a dry-run displays",
	}
	for _, path := range rowBudget {
		if _, known := frozen[strings.Join(path, " ")]; !known {
			t.Errorf("%q declares --limit without addPageLimitFlag: is it a page (register it) "+
				"or a row budget (name it in this test, with why)?", strings.Join(path, " "))
		}
	}
	if len(rowBudget) != len(frozen) {
		t.Errorf("row-budget commands = %v, want the %d frozen ones", rowBudget, len(frozen))
	}

	for _, args := range paging {
		t.Run(strings.Join(args, " "), func(t *testing.T) {
			cmd := NewRootCmd()
			out, errBuf := new(bytes.Buffer), new(bytes.Buffer)
			cmd.SetOut(out)
			cmd.SetErr(errBuf)
			cmd.SetArgs(append(append([]string{}, args...), "--limit", "3"))
			if err := cmd.Execute(); err != nil {
				t.Fatalf("command failed: %v (stderr: %s)", err, errBuf.String())
			}
			if !strings.Contains(errBuf.String(), "there may be more") {
				t.Errorf("a full page must be announced on stderr, got: %q", errBuf.String())
			}
			if strings.Contains(out.String(), "there may be more") {
				t.Errorf("the note must stay off stdout: %q", out.String())
			}
		})
	}
}
