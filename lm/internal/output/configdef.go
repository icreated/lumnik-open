package output

import (
	"fmt"
	"io"
	"strings"
)

// ConfigDef is a connector type's configuration schema as GET /api/platform/validator/describe/{kind}
// answers it (the hub's ConfigDefSerializer). `lm describe connector-type` and the describe panel of
// lm ui's :new read and lay it out the same way.
//
// Every property after Fields is a rule the hub publishes only when a declaration carries it, so each is omitted
// when empty: a hub that publishes none is rendered, and printed by -o json, exactly as it always was. A property
// this lm does not know yet is ignored by the decoder.
type ConfigDef struct {
	Fields []ConfigDefField `json:"fields"`
	// AtLeastOneOf: at least one of these keys is given, whenever the condition holds.
	AtLeastOneOf []AtLeastOneOf `json:"at_least_one_of,omitempty"`
	// Refused: keys the runtime never reads. The hub lists them apart from Fields so nobody takes one for a setting.
	Refused []RefusedKey `json:"refused,omitempty"`
}

type ConfigDefField struct {
	Name       string `json:"name"`
	Type       string `json:"type"`
	Importance string `json:"importance"`
	Required   bool   `json:"required"`
	Doc        string `json:"doc"`
	// Default: what the runtime uses when the key is omitted. A pointer-free any, so a published false or 0 survives -o json.
	Default any `json:"default,omitempty"`
	// RequiredWhen: the key is required only when its condition holds (Required is false then).
	RequiredWhen *RequiredWhen `json:"required_when,omitempty"`
	// IgnoredUnless: the runtime reads the key only when its condition holds; given elsewhere it is ignored.
	IgnoredUnless *IgnoredUnless `json:"ignored_unless,omitempty"`
	// Allowed: the values the key accepts. Recommendations: the sentences a refusal of the key teaches. The hub
	// publishes them apart; a hub that does not publish Allowed has its values in Recommendations, and lm shows
	// them as the hints they are listed as rather than guess from how the text looks.
	Allowed         []string   `json:"allowed,omitempty"`
	Recommendations []string   `json:"recommendations,omitempty"`
	Nested          *ConfigDef `json:"nested,omitempty"`
	// Items: on a LIST, the shape of ONE element. Not Nested: a list of objects is not a block of the field.
	Items *ConfigDef `json:"items,omitempty"`
}

type RequiredWhen struct {
	Field  string `json:"field,omitempty"`
	Equals string `json:"equals,omitempty"`
	When   string `json:"when"`
}

type IgnoredUnless struct {
	When string `json:"when"`
}

type AtLeastOneOf struct {
	Fields []string `json:"fields"`
	When   string   `json:"when,omitempty"`
	Hint   string   `json:"hint,omitempty"`
}

type RefusedKey struct {
	Name            string   `json:"name"`
	Message         string   `json:"message"`
	Recommendations []string `json:"recommendations,omitempty"`
}

// RenderConfigDef writes one line per field, its doc and its rules under it, nested blocks indented, a list's
// element shape under `name[]`, then the rules of the block itself.
func RenderConfigDef(out io.Writer, def ConfigDef, depth int) {
	indent := strings.Repeat("  ", depth)
	for _, f := range def.Fields {
		req := "optional"
		if f.Required {
			req = "REQUIRED"
		}
		fmt.Fprintf(out, "%s%-20s %s  [%s, %s]\n", indent, f.Name, req, f.Type, f.Importance)
		if f.Doc != "" {
			fmt.Fprintf(out, "%s  %s\n", indent, f.Doc)
		}
		if f.Default != nil && f.Default != "" {
			fmt.Fprintf(out, "%s  default: %s\n", indent, defaultText(f.Default))
		}
		if f.RequiredWhen != nil {
			fmt.Fprintf(out, "%s  required when %s\n", indent, f.RequiredWhen.When)
		}
		if f.IgnoredUnless != nil {
			fmt.Fprintf(out, "%s  read only when %s\n", indent, f.IgnoredUnless.When)
		}
		if len(f.Allowed) > 0 {
			fmt.Fprintf(out, "%s  one of: %s\n", indent, strings.Join(f.Allowed, ", "))
		}
		for _, hint := range f.Recommendations {
			fmt.Fprintf(out, "%s  → %s\n", indent, hint)
		}
		if f.Nested != nil {
			RenderConfigDef(out, *f.Nested, depth+1)
		}
		if f.Items != nil {
			fmt.Fprintf(out, "%s  %s[]\n", indent, f.Name)
			RenderConfigDef(out, *f.Items, depth+2)
		}
	}
	for _, r := range def.AtLeastOneOf {
		line := "at least one of " + strings.Join(r.Fields, ", ")
		if r.When != "" {
			line += " when " + r.When
		}
		fmt.Fprintf(out, "%s%s\n", indent, line)
		if r.Hint != "" {
			fmt.Fprintf(out, "%s  → %s\n", indent, r.Hint)
		}
	}
	if len(def.Refused) > 0 {
		fmt.Fprintf(out, "%srefused when given — the runtime never reads these:\n", indent)
		for _, r := range def.Refused {
			fmt.Fprintf(out, "%s  %-20s %s\n", indent, r.Name, r.Message)
			for _, hint := range r.Recommendations {
				fmt.Fprintf(out, "%s    → %s\n", indent, hint)
			}
		}
	}
}

// defaultText is a default as one line: a list is its elements apart (an empty one stays visible, quoted).
func defaultText(v any) string {
	list, ok := v.([]any)
	if !ok {
		return fmt.Sprint(v)
	}
	parts := make([]string, len(list))
	for i, e := range list {
		if s, isText := e.(string); isText {
			parts[i] = fmt.Sprintf("%q", s)
		} else {
			parts[i] = fmt.Sprint(e)
		}
	}
	return strings.Join(parts, ", ")
}
