package style

import "testing"

func TestColumnSortCycle(t *testing.T) {
	s := NewColumnSort([]string{"NAME", "TYPE"})

	// Initial state: no sort.
	if _, _, sorted := s.Active(); sorted {
		t.Fatalf("new ColumnSort should start unsorted")
	}

	// col0 asc
	s.Next()
	idx, desc, sorted := s.Active()
	if !sorted || idx != 0 || desc {
		t.Fatalf("after 1st Next: want (0, asc, sorted); got (%d, desc=%v, sorted=%v)", idx, desc, sorted)
	}

	// col0 desc
	s.Next()
	idx, desc, sorted = s.Active()
	if !sorted || idx != 0 || !desc {
		t.Fatalf("after 2nd Next: want (0, desc, sorted); got (%d, desc=%v, sorted=%v)", idx, desc, sorted)
	}

	// col1 asc
	s.Next()
	idx, desc, _ = s.Active()
	if idx != 1 || desc {
		t.Fatalf("after 3rd Next: want (1, asc); got (%d, desc=%v)", idx, desc)
	}

	// col1 desc
	s.Next()
	idx, desc, _ = s.Active()
	if idx != 1 || !desc {
		t.Fatalf("after 4th Next: want (1, desc); got (%d, desc=%v)", idx, desc)
	}

	// back to off
	s.Next()
	if _, _, sorted := s.Active(); sorted {
		t.Fatalf("after full cycle, Active().sorted should be false")
	}
}

func TestColumnSortHeaderLabel(t *testing.T) {
	s := NewColumnSort([]string{"NAME", "TYPE"})
	if got := s.HeaderLabel(0); got != "NAME" {
		t.Errorf("unsorted col0: want NAME, got %q", got)
	}
	s.Next() // col0 asc
	if got := s.HeaderLabel(0); got != "NAME ↑" {
		t.Errorf("col0 asc: want NAME ↑, got %q", got)
	}
	if got := s.HeaderLabel(1); got != "TYPE" {
		t.Errorf("inactive col stays plain: want TYPE, got %q", got)
	}
	s.Next() // col0 desc
	if got := s.HeaderLabel(0); got != "NAME ↓" {
		t.Errorf("col0 desc: want NAME ↓, got %q", got)
	}
}
