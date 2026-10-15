//go:build otk

package otk

import "testing"

func TestParseDataTables(t *testing.T) {
	in := `[{"mappingId":"m1","tableSchema":"connector","tableName":"t_order","rowCount":3},
	        {"mappingId":"m2","tableSchema":"connector","tableName":"t_line","rowCount":0}]`
	got, err := parseDataTables(in)
	if err != nil {
		t.Fatal(err)
	}
	if !got["connector.t_order"] || !got["connector.t_line"] || len(got) != 2 {
		t.Fatalf("got %v", got)
	}
}

func TestParseDataTables_empty(t *testing.T) {
	got, err := parseDataTables(`[]`)
	if err != nil || len(got) != 0 {
		t.Fatalf("empty list -> empty set, got %v err %v", got, err)
	}
}

func TestParseDataTables_garbage(t *testing.T) {
	if _, err := parseDataTables("not json"); err == nil {
		t.Fatal("expected error on unparseable output")
	}
}

func TestPsqlInt_toleratesSetTag(t *testing.T) {
	ok := []struct {
		in   string
		want int
	}{
		{"0\n", 0},      // psql <=14: only the last result is printed
		{"SET\n0\n", 0}, // psql >=15: the rlsScoped SET tag is echoed too (the CI failure)
		{"SET\n42", 42},
		{"  7  \n", 7},
	}
	for _, c := range ok {
		got, err := psqlInt([]byte(c.in))
		if err != nil || got != c.want {
			t.Fatalf("psqlInt(%q) = (%d, %v), want (%d, nil)", c.in, got, err, c.want)
		}
	}
	bad := []string{"", "SET\n", "SET\nabc", "nope"}
	for _, c := range bad {
		if _, err := psqlInt([]byte(c)); err == nil {
			t.Fatalf("psqlInt(%q) expected an error", c)
		}
	}
}

func TestValidTableIdent(t *testing.T) {
	good := []string{"connector.t_order", "core.connector", "rag_2.t_line9"}
	for _, s := range good {
		if !validTableIdent(s) {
			t.Errorf("%q should be valid", s)
		}
	}
	bad := []string{"", "core", "a.b.c", "core.connector; DROP TABLE x", "1bad.x", "core.\"x\"", "core.t order"}
	for _, s := range bad {
		if validTableIdent(s) {
			t.Errorf("%q should be rejected", s)
		}
	}
}

func TestValidTenantID(t *testing.T) {
	good := []string{"0", "1", "2", "13", "999999"}
	for _, s := range good {
		if !validTenantID(s) {
			t.Errorf("%q should be valid", s)
		}
	}
	bad := []string{"", "-1", "1.5", "1 OR 1=1", "1; DROP TABLE x", "1'", " 1", "1 "}
	for _, s := range bad {
		if validTenantID(s) {
			t.Errorf("%q should be rejected", s)
		}
	}
}

func TestCrossTenantQuery_PinsSessionAndFiltersOnWant(t *testing.T) {
	got := crossTenantQuery("2", "1")
	want := "SET app.current_tenant = '2'; SELECT count(*) FROM connector.connector WHERE tenant_id = 1"
	if got != want {
		t.Fatalf("crossTenantQuery(\"2\", \"1\") = %q, want %q", got, want)
	}
}

func TestValidRunID(t *testing.T) {
	good := []string{
		"8ab70f50-4239-448b-bfb2-361fb64d5660",
		"00000000-0000-0000-0000-000000000000",
		"ABCDEF12-ABCD-ABCD-ABCD-ABCDEF123456",
	}
	for _, s := range good {
		if !validRunID(s) {
			t.Errorf("%q should be valid", s)
		}
	}
	bad := []string{"", "not-a-uuid", "8ab70f50-4239-448b-bfb2-361fb64d566", "'; DROP TABLE x; --",
		"8ab70f50-4239-448b-bfb2-361fb64d5660'"}
	for _, s := range bad {
		if validRunID(s) {
			t.Errorf("%q should be rejected", s)
		}
	}
}

func TestRunTerminalQuery_PinsSessionAndFiltersOnRunID(t *testing.T) {
	got := runTerminalQuery("8ab70f50-4239-448b-bfb2-361fb64d5660")
	want := "SET app.current_tenant = '1'; " +
		"SELECT (ended_at IS NOT NULL) FROM connector.connector_run WHERE id = '8ab70f50-4239-448b-bfb2-361fb64d5660'"
	if got != want {
		t.Fatalf("runTerminalQuery(...) = %q, want %q", got, want)
	}
}

func TestPsqlBool_toleratesSetTag(t *testing.T) {
	ok := []struct {
		in   string
		want bool
	}{
		{"t\n", true},
		{"f\n", false},
		{"SET\nt\n", true}, // psql >=15 echoes the rlsScoped SET tag before the value
		{"SET\nf", false},
	}
	for _, c := range ok {
		got, err := psqlBool([]byte(c.in))
		if err != nil || got != c.want {
			t.Fatalf("psqlBool(%q) = (%v, %v), want (%v, nil)", c.in, got, err, c.want)
		}
	}
	bad := []string{"", "SET\n", "SET\nabc", "nope"}
	for _, c := range bad {
		if _, err := psqlBool([]byte(c)); err == nil {
			t.Fatalf("psqlBool(%q) expected an error", c)
		}
	}
}
