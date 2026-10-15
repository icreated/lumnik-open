package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"maps"
	"slices"
	"sort"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

// lm search query — direct keyword search over a declared SearchIndex (#836): deterministic retrieval,
// no model, unlike `lm ask`. The CLI adds no rule of its own: the hub owns every bound and refusal,
// and what it says reaches the user verbatim. What the CLI does add is *explanation*, because two
// situations look like an empty answer and are not: an engine that is not there, and an entity that
// nothing indexes.

const (
	maxFacetValuesShown = 8
	maxIndexesDiagnosed = 5
	maxFieldValueWidth  = 60
)

func newSearchQueryCmd() *cobra.Command {
	var (
		entity string
		facets []string
		limit  int
		cursor string
	)
	cmd := &cobra.Command{
		Use:   `query "TEXT" [--entity E] [--facet field=value]... [--limit N] [--cursor C]`,
		Short: "Search a declared index (keywords, not a question — for questions use lm ask)",
		Long: `Search the business data a SearchIndex declares, by keyword. The query is one argument: quote it when
it has spaces — the quotes are for your shell, and there is no phrase search. Words are matched as the
index's field modes say (see the kind: SearchIndex manifest), with no operators. --facet narrows on a
declared facet field and may be repeated.

A page ends with the exact command that fetches the next one. A cursor is not a snapshot: if the indexes
being searched change between two pages, the hub refuses it and the search starts over.`,
		Args: func(cmd *cobra.Command, args []string) error {
			if len(args) != 1 {
				return fmt.Errorf(`search query takes ONE argument — quote it when it has spaces, e.g. lm search query "hydraulique lyon" (got %d arguments)`, len(args))
			}
			return nil
		},
		RunE: func(cmd *cobra.Command, args []string) error {
			params := client.SearchQueryParams{Text: args[0], Entity: entity, Cursor: cursor}
			if cmd.Flags().Changed("limit") {
				params.Limit = &limit
			}
			for _, f := range facets {
				field, value, ok := strings.Cut(f, "=")
				if !ok || field == "" || value == "" {
					return fmt.Errorf("--facet %q: expected field=value", f)
				}
				params.Facets = append(params.Facets, [2]string{field, value})
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			res, err := c.SearchQuery(params)
			if err != nil {
				return explainSearchError(err)
			}
			format := outputFormat(cmd)
			if format != "table" {
				if err := output.PrintObject(cmd.OutOrStdout(), format, res); err != nil {
					return err
				}
			} else if err := printSearchHits(cmd, res, params); err != nil {
				return err
			}
			if len(res.Hits) == 0 {
				diagnoseNoHits(cmd, c, entity)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&entity, "entity", "", "search one entity only (default: every entity your scopes can read)")
	cmd.Flags().StringArrayVar(&facets, "facet", nil, "narrow on a declared facet field, field=value (repeatable)")
	cmd.Flags().IntVar(&limit, "limit", 0, "hits per page, 1-100 (default: the hub's, 20)")
	cmd.Flags().StringVar(&cursor, "cursor", "", "continue from a previous page's cursor")
	return cmd
}

// explainSearchError turns the two answers that must never read as "no results" into messages that say
// what is missing and how to fix it. Every other refusal (400 validation, 403 scope not granted, a
// refused cursor) is the hub's own and passes through untouched.
func explainSearchError(err error) error {
	var api *client.APIError
	if !errors.As(err, &api) {
		return err
	}
	switch api.Status {
	case 501:
		return errors.New("search is not installed on this hub — no Search provider is built into it, so there is " +
			"nothing to query (this is not an empty result). The published hub image never carries one: build it " +
			"from source with deploy/selfhost/up.sh --search (PostgreSQL) or --search-cluster (OpenSearch), both closed edition")
	case 503:
		var body struct {
			Reason string `json:"reason"`
		}
		_ = json.Unmarshal([]byte(api.Body), &body)
		msg := "search is degraded"
		if body.Reason != "" {
			msg += ": " + degradedReason(body.Reason)
		}
		if api.RetryAfter != "" {
			msg += " — retry in " + api.RetryAfter + "s"
		}
		return errors.New(msg)
	}
	return err
}

// degradedReasons are the provider reasons worth a sentence. The token always stays in the message, and a
// reason this CLI does not know is shown as the hub sent it, never dropped.
var degradedReasons = map[string]string{
	"backend_unreachable":      "the search backend cannot be reached",
	"backend_auth_failed":      "the search backend refused the hub's credentials",
	"unsupported_distribution": "the search backend is a distribution the provider does not support",
	"cluster_red":              "the search cluster reports red health",
}

func degradedReason(reason string) string {
	if text, ok := degradedReasons[reason]; ok {
		return text + " (" + reason + ")"
	}
	return reason
}

// diagnoseNoHits says why an empty answer is empty when the hub's own registry can tell — and only then.
// It is best effort and stays on stderr: a failing registry or a refused status door (the build state is
// integrator-only) means the CLI cannot know, so it says nothing rather than guess.
func diagnoseNoHits(cmd *cobra.Command, c *client.Client, entity string) {
	declared, err := c.SearchIndexesDeclared()
	if err != nil {
		return
	}
	var over []client.SearchIndexDeclared
	for _, d := range declared {
		if entity == "" || strings.EqualFold(d.Entity, entity) {
			over = append(over, d)
		}
	}
	w := cmd.ErrOrStderr()
	if len(over) == 0 {
		what := "no SearchIndex is declared"
		if entity != "" {
			what += " over " + entity
		}
		fmt.Fprintf(w, "%s (that your scopes can read) — there is nothing to search. Declare one: lm search apply -f <manifest>\n", what)
		return
	}
	for _, d := range over[:min(len(over), maxIndexesDiagnosed)] {
		if d.Status == "invalid" {
			reason := ""
			if d.InvalidReason != nil {
				reason = ": " + *d.InvalidReason
			}
			fmt.Fprintf(w, "SearchIndex %q over %s is invalid%s — fix the manifest and re-apply it: lm search apply -f <manifest>\n",
				d.Name, d.Entity, reason)
			continue
		}
		st, err := c.SearchStatus(d.Name)
		if err != nil {
			continue // not an integrator, or the door is down: the build state is unknown, so unsaid
		}
		// A rebuild of a built index keeps answering from the live generation, so "being built" explains
		// an empty answer only when there is no live generation yet.
		if st.LiveGeneration != nil {
			continue
		}
		switch {
		case st.ActiveRun != nil:
			fmt.Fprintf(w, "SearchIndex %q is being built (%s) — try again when `lm search status %s` says ready\n",
				d.Name, st.ActiveRun.Phase, d.Name)
		case st.LastRebuild != nil && st.LastRebuild.Outcome == "failed":
			// The shape of "never built" — but repeating the rebuild that just failed would not help.
			why := ""
			if st.LastError != "" {
				why = ": " + st.LastError
			}
			fmt.Fprintf(w, "SearchIndex %q over %s has nothing built: its last build failed%s — "+
				"read it with `lm search status %s`, fix the cause, then lm search rebuild %s --wait\n",
				d.Name, d.Entity, why, d.Name, d.Name)
		default:
			fmt.Fprintf(w, "SearchIndex %q over %s is declared but not built yet: lm search rebuild %s --wait\n",
				d.Name, d.Entity, d.Name)
		}
	}
}

// printSearchHits writes the table, and returns the failure of writing it: a closed pipe or a full disk
// must not read as success. The lines after the table are best effort, as everywhere else in this CLI.
func printSearchHits(cmd *cobra.Command, res *client.SearchResult, p client.SearchQueryParams) error {
	w := cmd.OutOrStdout()
	if len(res.Hits) == 0 {
		fmt.Fprintln(w, "0 hits")
		return nil
	}
	if err := output.Print(w, "table", []string{"ENTITY", "KEY", "SCORE", "MATCH"}, res.Hits, func(h client.SearchHit) []string {
		return []string{h.Entity, joinPairs(h.Key, ","), fmt.Sprintf("%.2f", h.Score), matchSummary(h)}
	}); err != nil {
		return err
	}
	if len(res.Facets) > 0 {
		fmt.Fprintln(w)
		fmt.Fprintln(w, "facets:")
		pw := newPadWriter()
		for _, f := range slices.Sorted(maps.Keys(res.Facets)) {
			pw.line(f, facetValues(res.Facets[f]))
		}
		pw.flush(w)
	}
	fmt.Fprintln(w)
	footer := fmt.Sprintf("%d hits shown", len(res.Hits))
	if res.AsOf != nil {
		footer += " · as of " + output.HumanTimeStr(*res.AsOf)
	}
	fmt.Fprintln(w, footer)
	if res.NextCursor != nil {
		fmt.Fprintln(w, "more: "+nextPageCommand(p, *res.NextCursor))
	}
	return nil
}

var (
	boldMarkup = strings.NewReplacer("<b>", "*", "</b>", "*")
	lineBreaks = strings.NewReplacer("\t", " ", "\r", " ", "\n", " ")
)

// matchSummary lists the hit's non-empty fields as name=value, a matched field showing its highlighted
// fragment (<b>…</b> becomes *…*: raw markup is noise, and an ANSI bold would be escaped by textsafe).
// Matched fields come first, so what made this hit a hit is not pushed off the line by a facet field.
func matchSummary(h client.SearchHit) string {
	names := slices.Collect(maps.Keys(h.Fields))
	sort.Slice(names, func(i, j int) bool {
		hi, hj := len(h.Highlights[names[i]]) > 0, len(h.Highlights[names[j]]) > 0
		if hi != hj {
			return hi
		}
		return names[i] < names[j]
	})
	var parts []string
	for _, n := range names {
		v := h.Fields[n]
		if frags := h.Highlights[n]; len(frags) > 0 {
			v = boldMarkup.Replace(frags[0])
		}
		v = oneLine(v)
		if v == "" {
			continue
		}
		parts = append(parts, n+"="+output.Truncate(v, maxFieldValueWidth))
	}
	return strings.Join(parts, " | ")
}

// oneLine keeps a field value inside its table cell: a tab or a line break from the source data would
// split the row.
func oneLine(s string) string {
	return strings.TrimSpace(lineBreaks.Replace(s))
}

func joinPairs(m map[string]string, sep string) string {
	var parts []string
	for _, k := range slices.Sorted(maps.Keys(m)) {
		parts = append(parts, k+"="+oneLine(m[k]))
	}
	return strings.Join(parts, sep)
}

func facetValues(counts []client.SearchFacetCount) string {
	shown := counts
	if len(shown) > maxFacetValuesShown {
		shown = shown[:maxFacetValuesShown]
	}
	parts := make([]string, 0, len(shown)+1)
	for _, c := range shown {
		parts = append(parts, fmt.Sprintf("%s (%d)", oneLine(c.Value), c.Count))
	}
	if extra := len(counts) - len(shown); extra > 0 {
		parts = append(parts, fmt.Sprintf("… +%d", extra))
	}
	return strings.Join(parts, "  ")
}

// nextPageCommand is the exact command for the next page: the same query, filters and page size, the
// new cursor. --limit is repeated only when the user set it. Quoted by shellArg, which knows PowerShell is
// where lm.exe is typed.
func nextPageCommand(p client.SearchQueryParams, cursor string) string {
	var b strings.Builder
	b.WriteString("lm search query " + shellArg(p.Text))
	if p.Entity != "" {
		b.WriteString(" --entity " + shellArg(p.Entity))
	}
	for _, f := range p.Facets {
		b.WriteString(" --facet " + shellArg(f[0]+"="+f[1]))
	}
	if p.Limit != nil {
		fmt.Fprintf(&b, " --limit %d", *p.Limit)
	}
	b.WriteString(" --cursor " + shellArg(cursor))
	return b.String()
}
