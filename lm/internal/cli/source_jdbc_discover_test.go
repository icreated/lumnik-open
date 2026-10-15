package cli

import (
	"strings"
	"testing"
)

const discoverBody = `{"tables":[
 {"schema":"ERP","name":"ORDERS","primaryKey":["ID"],"columns":[{"name":"ID","jdbcType":"NUMBER","size":10,"nullable":false}],
  "suggestedWatermark":null,"rowEstimate":2,"unsupportedColumns":[]},
 {"schema":"ERP","name":"DOCS","primaryKey":["ID"],"columns":[{"name":"BODY","jdbcType":"BLOB","size":0,"nullable":true}],
  "suggestedWatermark":null,"rowEstimate":1,"unsupportedColumns":["BODY BLOB"]}]}`

// discoverHub answers every request with the canned discover document and records what the CLI sent.
func discoverHub(t *testing.T) map[string]any { return jdbcEchoBody(t, discoverBody) }

func TestJdbcDiscover_namesTheUnsupportedColumnsInTheTableOutput(t *testing.T) {
	discoverHub(t)
	stdout, _, err := runCLI(t, "pw\n", "source", "jdbc", "discover",
		"--url", "jdbc:oracle:thin:@//h/x", "--user", "u", "--password-stdin", "--schema", "ERP")
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if !strings.Contains(stdout, "UNSUPPORTED") {
		t.Errorf("no UNSUPPORTED column in the discover table: %q", stdout)
	}
	for _, line := range strings.Split(stdout, "\n") {
		switch {
		case strings.Contains(line, "DOCS") && !strings.Contains(line, "BODY BLOB"):
			t.Errorf("the incompatible table does not name its column and type: %q", line)
		case strings.Contains(line, "ORDERS") && strings.Contains(line, "BLOB"):
			t.Errorf("a clean table is flagged: %q", line)
		}
	}
}

func TestJdbcDiscover_keepsTheFieldInJsonOutput(t *testing.T) {
	discoverHub(t)
	stdout, _, err := runCLI(t, "pw\n", "source", "jdbc", "discover",
		"--url", "jdbc:oracle:thin:@//h/x", "--user", "u", "--password-stdin", "--output", "json")
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if !strings.Contains(stdout, `"unsupportedColumns":["BODY BLOB"]`) {
		t.Errorf("--output json lost the unsupported columns: %q", stdout)
	}
}

// The dialect owns the default exclusions. A CLI that always sent PostgreSQL's three would hide an
// Oracle table named T_… and keep Oracle's own recycle-bin pattern from ever applying.
func TestJdbcDiscover_withoutTheFlagSendsNoSkipPatternsSoTheDialectDecides(t *testing.T) {
	got := discoverHub(t)
	_, _, err := runCLI(t, "pw\n", "source", "jdbc", "discover",
		"--url", "jdbc:oracle:thin:@//h/x", "--user", "u", "--password-stdin")
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	v, present := got["skipPatterns"]
	if !present || v != nil {
		t.Errorf("skipPatterns = %v (present=%v), want an explicit null so the hub uses the dialect's defaults", v, present)
	}
}

func TestJdbcDiscover_anExplicitSkipPatternStillReplacesTheDefaults(t *testing.T) {
	got := discoverHub(t)
	_, _, err := runCLI(t, "pw\n", "source", "jdbc", "discover",
		"--url", "jdbc:oracle:thin:@//h/x", "--user", "u", "--password-stdin", "--skip-pattern", "a_*, b_*")
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	list, _ := got["skipPatterns"].([]any)
	if len(list) != 2 || list[0] != "a_*" || list[1] != "b_*" {
		t.Errorf("skipPatterns = %v, want [a_* b_*]", got["skipPatterns"])
	}
}

// "Skip nothing" has to be sayable: a flag GIVEN but empty is an empty list, a flag ABSENT is null.
func TestJdbcDiscover_aGivenButEmptySkipPatternMeansSkipNothing(t *testing.T) {
	got := discoverHub(t)
	_, _, err := runCLI(t, "pw\n", "source", "jdbc", "discover",
		"--url", "jdbc:oracle:thin:@//h/x", "--user", "u", "--password-stdin", "--skip-pattern", "")
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	list, isList := got["skipPatterns"].([]any)
	if !isList || len(list) != 0 {
		t.Errorf("skipPatterns = %#v, want an explicit empty list so the hub excludes nothing", got["skipPatterns"])
	}
}

func TestJdbcSkipPatternFlagsDefaultToTheDialectsOwn(t *testing.T) {
	for _, name := range []string{"discover", "add"} {
		var f = map[string]string{}
		for _, sub := range newSourceJdbcCmd().Commands() {
			if sub.Name() == name {
				if fl := sub.Flags().Lookup("skip-pattern"); fl != nil {
					f[name] = fl.DefValue
				}
			}
		}
		if v, ok := f[name]; !ok || v != "" {
			t.Errorf("`source jdbc %s --skip-pattern` default = %q (present=%v), want empty (the dialect's own)", name, v, ok)
		}
	}
}
