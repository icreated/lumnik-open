package output

import (
	"fmt"
	"io"
	"strings"
)

// ConfigDef is a connector type's configuration schema as GET /api/platform/validator/describe/{kind}
// answers it (the hub's ConfigDefSerializer). `lm describe connector-type` and the describe panel of
// lm ui's :new read and lay it out the same way.
type ConfigDef struct {
	Fields []ConfigDefField `json:"fields"`
}

type ConfigDefField struct {
	Name       string     `json:"name"`
	Type       string     `json:"type"`
	Importance string     `json:"importance"`
	Required   bool       `json:"required"`
	Doc        string     `json:"doc"`
	Nested     *ConfigDef `json:"nested,omitempty"`
}

// RenderConfigDef writes one line per field, its doc under it, nested blocks indented.
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
		if f.Nested != nil {
			RenderConfigDef(out, *f.Nested, depth+1)
		}
	}
}
