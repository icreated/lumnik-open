package output

import (
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

var update = flag.Bool("update", false, "rewrite the golden renderings under testdata/describe")

func decode(t *testing.T, raw string) ConfigDef {
	t.Helper()
	var def ConfigDef
	if err := json.Unmarshal([]byte(raw), &def); err != nil {
		t.Fatalf("the hub's answer must decode: %v", err)
	}
	return def
}

func render(def ConfigDef) string {
	var b strings.Builder
	RenderConfigDef(&b, def, 0)
	return b.String()
}

// A hub that publishes none of the rules is rendered exactly as it was before lm read them.
func TestRender_aHubThatPublishesNoRules_isRenderedAsBefore(t *testing.T) {
	def := decode(t, `{"fields":[
	 {"name":"base_url","type":"STRING","importance":"HIGH","required":true,"doc":"Base URL"},
	 {"name":"auth","type":"NESTED","importance":"MEDIUM","required":false,"doc":"Auth","nested":{"fields":[
	   {"name":"kind","type":"STRING","importance":"HIGH","required":true,"doc":"Method"}]}},
	 {"name":"port","type":"INT","importance":"LOW","required":false,"doc":""}]}`)

	want := "base_url             REQUIRED  [STRING, HIGH]\n  Base URL\n" +
		"auth                 optional  [NESTED, MEDIUM]\n  Auth\n" +
		"  kind                 REQUIRED  [STRING, HIGH]\n    Method\n" +
		"port                 optional  [INT, LOW]\n"
	if got := render(def); got != want {
		t.Errorf("an old hub's schema must render as it always did\n got: %q\nwant: %q", got, want)
	}
}

func TestDecode_aPropertyThisLmDoesNotKnow_isIgnored(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"a","type":"STRING","importance":"LOW","required":false,"doc":"d",
	   "something_newer":{"x":1}}],"also_newer":[1,2]}`)
	if got, want := render(def), "a                    optional  [STRING, LOW]\n  d\n"; got != want {
		t.Errorf("an unknown property must not change the rendering\n got: %q\nwant: %q", got, want)
	}
}

// -o json prints the decoded struct: a hub that publishes no rules must not gain empty ones.
func TestEncode_aHubThatPublishesNoRules_gainsNoKey(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"a","type":"STRING","importance":"LOW","required":false,"doc":"d"}]}`)
	raw, err := json.Marshal(def)
	if err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"required_when", "ignored_unless", "recommendations", "allowed", "items", "at_least_one_of", "refused", "default"} {
		if strings.Contains(string(raw), key) {
			t.Errorf("-o json gained %q for a schema that carries none: %s", key, raw)
		}
	}
}

func TestEncode_theRulesAHubPublishes_surviveDashOJson(t *testing.T) {
	src := `{"fields":[{"name":"a","type":"STRING","importance":"LOW","required":false,"doc":"d",
	  "required_when":{"field":"k","equals":"v","when":"k = v"},"ignored_unless":{"when":"k = v"},"recommendations":["x"]}],
	  "at_least_one_of":[{"fields":["a","b"],"when":"k = v","hint":"h"}],
	  "refused":[{"name":"r","message":"m","recommendations":["h"]}]}`
	raw, err := json.Marshal(decode(t, src))
	if err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{`"required_when"`, `"ignored_unless"`, `"recommendations"`, `"at_least_one_of"`, `"refused"`} {
		if !strings.Contains(string(raw), key) {
			t.Errorf("-o json dropped %s: %s", key, raw)
		}
	}
}

// #1797: the hub publishes the value a key takes when it is omitted; lm used to drop it on decoding.
func TestRender_aDefault_isSaidBesideTheField(t *testing.T) {
	def := decode(t, `{"fields":[
	  {"name":"port","type":"INT","importance":"LOW","required":false,"doc":"SFTP port","default":22},
	  {"name":"strict","type":"BOOLEAN","importance":"LOW","required":false,"doc":"Verify","default":false},
	  {"name":"region","type":"STRING","importance":"LOW","required":false,"doc":"Region","default":"eu-west-3"}]}`)
	want := "port                 optional  [INT, LOW]\n  SFTP port\n  default: 22\n" +
		"strict               optional  [BOOLEAN, LOW]\n  Verify\n  default: false\n" +
		"region               optional  [STRING, LOW]\n  Region\n  default: eu-west-3\n"
	if got := render(def); got != want {
		t.Errorf("\n got: %q\nwant: %q", got, want)
	}
}

// A list default is a []any, which Go cannot compare: it must be said, not panic, with an empty element kept visible.
func TestRender_aListDefault_isSaidElementByElement(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"null_values","type":"LIST","importance":"LOW","required":false,"doc":"d","default":["","NULL",3]}]}`)
	want := "null_values          optional  [LIST, LOW]\n  d\n  default: \"\", \"NULL\", 3\n"
	if got := render(def); got != want {
		t.Errorf("\n got: %q\nwant: %q", got, want)
	}
}

// An empty default says "nothing": it is not worth a line.
func TestRender_anEmptyDefault_isNotSaid(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"a","type":"STRING","importance":"LOW","required":false,"doc":"d","default":""}]}`)
	if got := render(def); strings.Contains(got, "default") {
		t.Errorf("an empty default was said: %q", got)
	}
}

func TestEncode_aDefault_survivesDashOJson(t *testing.T) {
	raw, err := json.Marshal(decode(t, `{"fields":[{"name":"p","type":"INT","importance":"LOW","required":false,"doc":"d","default":22},
	  {"name":"b","type":"BOOLEAN","importance":"LOW","required":false,"doc":"d","default":false}]}`))
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{`"default":22`, `"default":false`} {
		if !strings.Contains(string(raw), want) {
			t.Errorf("-o json dropped %s: %s", want, raw)
		}
	}
}

func TestRender_aKeyRequiredWhen_saysWhenAndTeaches(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"archive_path","type":"STRING","importance":"LOW","required":false,
	  "doc":"Where moved files go","required_when":{"field":"after_process","equals":"move","when":"after_process = move"},
	  "recommendations":["Set archive_path: /path/to/archive/"]}]}`)
	want := "archive_path         optional  [STRING, LOW]\n  Where moved files go\n" +
		"  required when after_process = move\n  → Set archive_path: /path/to/archive/\n"
	if got := render(def); got != want {
		t.Errorf("\n got: %q\nwant: %q", got, want)
	}
}

func TestRender_aRequirementOnlyAPredicateCanSay_isRenderedFromItsWords(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"host_key_fingerprint","type":"STRING","importance":"MEDIUM","required":false,"doc":"d",
	  "required_when":{"when":"kind is sftp and strict_host_key is not off"}}]}`)
	if got := render(def); !strings.Contains(got, "  required when kind is sftp and strict_host_key is not off\n") {
		t.Errorf("got: %q", got)
	}
}

func TestRender_aKeyReadUnderOneKindOnly_saysSo(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"scheme","type":"STRING","importance":"LOW","required":false,"doc":"Word before the token",
	  "ignored_unless":{"when":"kind = bearer"}}]}`)
	want := "scheme               optional  [STRING, LOW]\n  Word before the token\n  read only when kind = bearer\n"
	if got := render(def); got != want {
		t.Errorf("\n got: %q\nwant: %q", got, want)
	}
}

// The hub publishes the values a key accepts (`allowed`) apart from the sentences a refusal teaches (`recommendations`):
// lm says each under its own label and never guesses one from the other by how the text looks.
func TestRender_allowedValues_andHints_areTwoThingsTheHubSaysApart(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"client_auth","type":"STRING","importance":"LOW","required":false,"doc":"d",
	  "allowed":["basic","post"],"recommendations":["basic (the default) sends the secret as HTTP Basic"]}]}`)
	want := "client_auth          optional  [STRING, LOW]\n  d\n  one of: basic, post\n" +
		"  → basic (the default) sends the secret as HTTP Basic\n"
	if got := render(def); got != want {
		t.Errorf("\n got: %q\nwant: %q", got, want)
	}
}

func TestRender_aHintOfOneWord_isStillAHint(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"x","type":"STRING","importance":"LOW","required":false,"doc":"d","recommendations":["Remove"]}]}`)
	got := render(def)
	if !strings.Contains(got, "  → Remove\n") || strings.Contains(got, "one of") {
		t.Errorf("a one-word hint is not an allowed value: %q", got)
	}
}

func TestRender_anAllowedValueWithASpace_isStillAValue(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"x","type":"STRING","importance":"LOW","required":false,"doc":"d","allowed":["full refresh","watermark"]}]}`)
	got := render(def)
	if !strings.Contains(got, "  one of: full refresh, watermark\n") || strings.Contains(got, "→") {
		t.Errorf("an allowed value is not a hint: %q", got)
	}
}

// A hub that published the values among its recommendations (before it published `allowed`) gets them shown as what lm
// can honestly call them — hints — rather than guessed at.
func TestRender_anOlderHubsEnumValues_areShownAsHints(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"kind","type":"STRING","importance":"HIGH","required":true,"doc":"d","recommendations":["basic","post"]}]}`)
	if got := render(def); !strings.Contains(got, "  → basic\n  → post\n") || strings.Contains(got, "one of") {
		t.Errorf("got: %q", got)
	}
}

func TestRender_aListOfObjects_showsItsElementAsItems_notAsABlock(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"indexes","type":"LIST","importance":"LOW","required":false,"doc":"Indexes",
	  "items":{"fields":[
	    {"name":"columns","type":"LIST","importance":"HIGH","required":true,"doc":"Columns"},
	    {"name":"unique","type":"BOOLEAN","importance":"LOW","required":false,"doc":"Unique"}]}}]}`)
	want := "indexes              optional  [LIST, LOW]\n  Indexes\n  indexes[]\n" +
		"    columns              REQUIRED  [LIST, HIGH]\n      Columns\n" +
		"    unique               optional  [BOOLEAN, LOW]\n      Unique\n"
	if got := render(def); got != want {
		t.Errorf("\n got: %q\nwant: %q", got, want)
	}
}

func TestRender_atLeastOneOf_isSaidOnceForTheBlock(t *testing.T) {
	def := decode(t, `{"fields":[
	  {"name":"key_env","type":"STRING","importance":"MEDIUM","required":false,"doc":"Key"},
	  {"name":"password_env","type":"STRING","importance":"MEDIUM","required":false,"doc":"Password"}],
	 "at_least_one_of":[{"fields":["key_env","password_env"],"when":"kind is sftp","hint":"Use lm secret set"}]}`)
	got := render(def)
	if !strings.HasSuffix(got, "at least one of key_env, password_env when kind is sftp\n  → Use lm secret set\n") {
		t.Errorf("the rule must come after the fields, once: %q", got)
	}
}

func TestRender_refusedKeys_areListedApartFromTheSettings(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"kind","type":"STRING","importance":"HIGH","required":true,"doc":"Kind"}],
	 "refused":[{"name":"pre_process","message":"pre_process is not read by connector runs",
	            "recommendations":["Drop it from the connector manifest"]}]}`)
	want := "kind                 REQUIRED  [STRING, HIGH]\n  Kind\n" +
		"refused when given — the runtime never reads these:\n" +
		"  pre_process          pre_process is not read by connector runs\n" +
		"    → Drop it from the connector manifest\n"
	if got := render(def); got != want {
		t.Errorf("\n got: %q\nwant: %q", got, want)
	}
}

func TestRender_theRulesOfANestedBlock_sitAtTheBlocksIndent(t *testing.T) {
	def := decode(t, `{"fields":[{"name":"transport","type":"NESTED","importance":"HIGH","required":true,"doc":"Transport",
	  "nested":{"fields":[{"name":"key_env","type":"STRING","importance":"MEDIUM","required":false,"doc":"Key"}],
	            "at_least_one_of":[{"fields":["key_env"],"when":"kind is sftp","hint":"h"}],
	            "refused":[{"name":"pre_process","message":"m","recommendations":[]}]}}]}`)
	got := render(def)
	for _, want := range []string{"  at least one of key_env when kind is sftp\n    → h\n", "  refused when given — the runtime never reads these:\n    pre_process          m\n"} {
		if !strings.Contains(got, want) {
			t.Errorf("missing %q in %q", want, got)
		}
	}
}

// Real hub output, kept by the hub's ConfigDefPublishedForLmTest: what an integrator sees for three connector types.
func TestRender_whatTheHubPublishes_goldens(t *testing.T) {
	for _, kind := range []string{"csv-file", "jdbc-generic", "rest-generic"} {
		t.Run(kind, func(t *testing.T) {
			raw, err := os.ReadFile(filepath.Join("testdata", "describe", kind+".json"))
			if err != nil {
				t.Fatalf("lm's input is the hub's output — regenerate it with the hub's ConfigDefPublishedForLmTest: %v", err)
			}
			got := render(decode(t, string(raw)))
			golden := filepath.Join("testdata", "describe", kind+".golden")
			if *update {
				if err := os.WriteFile(golden, []byte(got), 0o644); err != nil {
					t.Fatal(err)
				}
				return
			}
			want, err := os.ReadFile(golden)
			if err != nil {
				t.Fatalf("no golden rendering for %s — run: go test ./internal/output -run goldens -update", kind)
			}
			if got != string(want) {
				t.Errorf("the rendering of %s changed — if it is meant, run: go test ./internal/output -run goldens -update, and read the diff\n got:\n%s", kind, got)
			}
			// Never the hub's JSON: a rule is said in words.
			for _, raw := range []string{`"required_when"`, `"ignored_unless"`, `"at_least_one_of"`, `"items"`} {
				if strings.Contains(got, raw) {
					t.Errorf("the rendering carries raw JSON %s", raw)
				}
			}
		})
	}
}
