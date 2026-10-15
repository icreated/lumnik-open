package proposal

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gopkg.in/yaml.v3"
)

func TestOneLine_flattensEveryLineBreakYAMLKnows(t *testing.T) {
	for name, in := range map[string]string{
		"LF": "a\nb", "CR": "a\rb", "CRLF": "a\r\nb", "NEL": "a\u0085b", "LS": "a\u2028b", "PS": "a\u2029b",
		"VT": "a\vb", "FF": "a\fb",
	} {
		t.Run(name, func(t *testing.T) {
			out := oneLine(in)
			assert.NotContains(t, out, "\n")
			assert.NotContains(t, out, "\r")
			assert.NotContains(t, out, "\u0085")
			assert.NotContains(t, out, "\u2028")
			assert.NotContains(t, out, "\u2029")
			if name == "CRLF" {
				assert.Equal(t, "a  b", out)
			} else {
				assert.Equal(t, "a b", out)
			}
		})
	}
}

func TestOneLine_escapesControlAndBidiRunes(t *testing.T) {
	assert.Equal(t, `a\u001bb`, oneLine("a\x1bb"))
	assert.Equal(t, `a\u202eb`, oneLine("a\u202eb"))
	assert.Equal(t, `a\u0000b`, oneLine("a\x00b"))
	assert.Equal(t, "Nos commandes restent bloquées…", oneLine("Nos commandes restent bloquées…"))
}

// The invisible format runes (category Cf) do not end a comment, but a right-to-left mark reorders the
// punctuation around it on a terminal: they are escaped like the bidi controls, not shown raw.
func TestOneLine_escapesInvisibleFormatRunes(t *testing.T) {
	for name, r := range map[string]string{
		"LRM": "\u200e", "RLM": "\u200f", "ALM": "\u061c", "ZWSP": "\u200b", "WJ": "\u2060", "BOM": "\ufeff",
	} {
		t.Run(name, func(t *testing.T) {
			out := oneLine("a" + r + "b")
			assert.NotContains(t, out, r)
			assert.Contains(t, out, `\u`)
		})
	}
	assert.Error(t, Target{Scope: "a\u200fb"}.Validate(), "a flag value is held to the same rule")
}

func TestOneLine_aLineBreakCannotOpenAYAMLKey(t *testing.T) {
	line := "# " + oneLine("x\nspec:\n  scope: evil")
	var out map[string]any
	require.NoError(t, yaml.Unmarshal([]byte(line+"\nkind: Workflow\n"), &out))
	assert.Equal(t, map[string]any{"kind": "Workflow"}, out)
}

func TestYAMLString_isOneDoubleQuotedScalar(t *testing.T) {
	for _, in := range []string{"plain", "N° Commande", `has "quote"`, "colon: here", `back\slash`, "- dash", "tail #hash", "é<>&"} {
		var out map[string]string
		require.NoError(t, yaml.Unmarshal([]byte("k: "+yamlString(in)+"\n"), &out), in)
		assert.Equal(t, in, out["k"])
	}
	assert.Equal(t, `"é<>&"`, yamlString("é<>&"), "no HTML escaping: a column may hold any of these")
}

func TestTargetValidate(t *testing.T) {
	assert.NoError(t, Target{}.Validate(), "every flag is optional")
	assert.NoError(t, Target{Table: "connector.t_x", Column: "N° Commande", Key: "n", After: "14d", Name: "wf", Scope: "sales"}.Validate())
	assert.NoError(t, Target{After: "5m"}.Validate(), "--after without --key is accepted")
	assert.NoError(t, Target{After: "36h"}.Validate())

	for name, bad := range map[string]Target{
		"after: no unit":    {After: "14"},
		"after: wrong unit": {After: "14w"},
		"after: sign":       {After: "-1d"},
		"after: words":      {After: "soon"},
		"table: no schema":  {Table: "t_commandes"},
		"newline in column": {Column: "a\nb"},
		"escape in key":     {Key: "a\x1bb"},
		"line separator":    {Name: "a\u2028b"},
		"bidi override":     {Scope: "a\u202eb"},
		"NUL in table":      {Table: "connector.a\x00b"},
	} {
		t.Run(name, func(t *testing.T) { assert.Error(t, bad.Validate()) })
	}
}
