package output

import (
	"maps"
	"slices"
	"sort"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

// One rendering of a search hit for `lm search query` and lm ui's :search, so the two never drift.

var (
	boldMarkup = strings.NewReplacer("<b>", "*", "</b>", "*")
	lineBreaks = strings.NewReplacer("\t", " ", "\r", " ", "\n", " ")
)

// SearchMatch lists the hit's non-empty fields as name=value, a matched field showing its highlighted
// fragment (<b>…</b> becomes *…*: raw markup is noise, and an ANSI bold would be escaped by textsafe).
// Matched fields come first, so what made this hit a hit is not pushed off the line by a facet field.
// Each value is cut at width.
func SearchMatch(h client.SearchHit, width int) string {
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
		v = OneLine(v)
		if v == "" {
			continue
		}
		parts = append(parts, n+"="+Truncate(v, width))
	}
	return strings.Join(parts, " | ")
}

// OneLine keeps a field value inside its table cell: a tab or a line break from the source data would
// split the row.
func OneLine(s string) string {
	return strings.TrimSpace(lineBreaks.Replace(s))
}

// JoinPairs renders a map as sorted key=value pairs, each value on one line.
func JoinPairs(m map[string]string, sep string) string {
	var parts []string
	for _, k := range slices.Sorted(maps.Keys(m)) {
		parts = append(parts, k+"="+OneLine(m[k]))
	}
	return strings.Join(parts, sep)
}
