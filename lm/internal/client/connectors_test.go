package client

import "testing"

func TestSplitScopes(t *testing.T) {
	cases := []struct {
		in   string
		want []string
	}{
		{"compta,paie", []string{"compta", "paie"}},
		{"  compta , paie ", []string{"compta", "paie"}},
		{"single", []string{"single"}},
		{",,,", []string{}},
		{"", []string{}},
	}
	for _, c := range cases {
		got := SplitScopes(c.in)
		if len(got) != len(c.want) {
			t.Errorf("SplitScopes(%q) len = %d, want %d", c.in, len(got), len(c.want))
			continue
		}
		for i := range got {
			if got[i] != c.want[i] {
				t.Errorf("SplitScopes(%q)[%d] = %q, want %q", c.in, i, got[i], c.want[i])
			}
		}
	}
}
