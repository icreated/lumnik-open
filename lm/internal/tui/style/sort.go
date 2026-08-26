package style

// ColumnSort tracks sort state for a table page. Cycles col0↑ → col0↓ → col1↑ → ...
// → off → col0↑. Pages call Next() on `s`, then re-render headers via HeaderLabel()
// and re-sort their data when Active() returns sorted=true.
type ColumnSort struct {
	columns    []string
	activeIdx  int // -1 = no sort
	descending bool
}

// NewColumnSort returns a sort state over the given column labels, starting unsorted.
func NewColumnSort(columns []string) *ColumnSort {
	return &ColumnSort{columns: columns, activeIdx: -1}
}

// Next advances the cycle: off → col0↑ → col0↓ → col1↑ → col1↓ → ... → off.
func (s *ColumnSort) Next() {
	if s.activeIdx == -1 {
		s.activeIdx = 0
		s.descending = false
		return
	}
	if !s.descending {
		s.descending = true
		return
	}
	// We were on col[activeIdx] descending → move to next column ascending.
	s.activeIdx++
	s.descending = false
	if s.activeIdx >= len(s.columns) {
		s.activeIdx = -1
	}
}

// Active returns (columnIndex, descending, sorted). sorted=false → render as-fetched.
func (s *ColumnSort) Active() (idx int, desc bool, sorted bool) {
	if s.activeIdx == -1 {
		return 0, false, false
	}
	return s.activeIdx, s.descending, true
}

// HeaderLabel returns the column label with arrow if it is the active sort column.
func (s *ColumnSort) HeaderLabel(idx int) string {
	if idx < 0 || idx >= len(s.columns) {
		return ""
	}
	base := s.columns[idx]
	if s.activeIdx != idx {
		return base
	}
	if s.descending {
		return base + " ↓"
	}
	return base + " ↑"
}
