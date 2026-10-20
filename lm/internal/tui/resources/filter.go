package resources

import "strings"

// textFilter is a page's / filter (#1120): the rows shown are the ones whose values hold it,
// case-insensitively. Pages keep the full list beside the shown one, so a live refresh re-applies
// the filter instead of dropping it, and items[row-1] — what every action resolves — is always
// the row on screen.
type textFilter struct{ q string }

// TextFilter is the filter in force, "" for none.
func (f *textFilter) TextFilter() string { return f.q }

// filterRows keeps the items whose shown text holds q. shown is the page's columns, not the whole
// item: %v over the item also searched fields no column shows (a dead letter's payload, which can
// be large, on every live refresh) and pointer addresses (#1124 review).
func filterRows[T any](all []T, q string, shown func(T) []string) []T {
	if q == "" {
		return all
	}
	q = strings.ToLower(q)
	var out []T
	for _, it := range all {
		if strings.Contains(strings.ToLower(strings.Join(shown(it), "\x00")), q) {
			out = append(out, it)
		}
	}
	return out
}
