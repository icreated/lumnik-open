package cli

import (
	"encoding/json"
	"fmt"
	"io"
	"strings"

	"github.com/spf13/cobra"
)

func newDescribeCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "describe",
		Short: "Describe schemas and resources",
	}
	cmd.AddCommand(newDescribeConnectorTypeCmd())
	return cmd
}

type configDefField struct {
	Name       string          `json:"name"`
	Type       string          `json:"type"`
	Importance string          `json:"importance"`
	Required   bool            `json:"required"`
	Doc        string          `json:"doc"`
	Nested     *configDefBlock `json:"nested,omitempty"`
}

type configDefBlock struct {
	Fields []configDefField `json:"fields"`
}

func newDescribeConnectorTypeCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "connector-type <kind>",
		Short: "Show the configuration schema of a connector type",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			raw, err := c.DescribeConnectorType(args[0])
			if err != nil {
				return err
			}
			var def configDefBlock
			if err := json.Unmarshal(raw, &def); err != nil {
				return err
			}
			return renderConfigDef(cmd.OutOrStdout(), args[0], def, 0)
		},
	}
}

func renderConfigDef(out io.Writer, kind string, def configDefBlock, depth int) error {
	indent := strings.Repeat("  ", depth)
	if depth == 0 {
		fmt.Fprintf(out, "Connector type: %s\n\n", kind)
	}
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
			_ = renderConfigDef(out, "", *f.Nested, depth+1)
		}
	}
	return nil
}
