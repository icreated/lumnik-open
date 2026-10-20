package cli

import (
	"encoding/json"
	"fmt"
	"io"

	"github.com/icreated/lumnik-open/lm/internal/output"
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
			var def output.ConfigDef
			if err := json.Unmarshal(raw, &def); err != nil {
				return err
			}
			// -o is a persistent root flag, so it arrives here whether or not this command looks
			// at it — see source_schema.go.
			if format, _ := cmd.Flags().GetString("output"); format != "table" {
				return output.PrintObject(cmd.OutOrStdout(), format, def)
			}
			renderConfigDef(cmd.OutOrStdout(), args[0], def)
			return nil
		},
	}
}

func renderConfigDef(out io.Writer, kind string, def output.ConfigDef) {
	fmt.Fprintf(out, "Connector type: %s\n\n", kind)
	output.RenderConfigDef(out, def, 0)
}
