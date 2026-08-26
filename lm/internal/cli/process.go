package cli

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newProcessCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "process",
		Short: "Manage kind:Process manifests (notify rules on the event stream)",
	}

	var file string
	apply := &cobra.Command{
		Use:   "apply",
		Short: "Apply a kind:Process manifest (YAML)",
		RunE: func(cmd *cobra.Command, _ []string) error {
			body, err := readYamlFile(file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			resp, err := c.ProcessApply(body)
			if err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), string(resp))
			return nil
		},
	}
	apply.Flags().StringVarP(&file, "file", "f", "", "kind:Process YAML manifest (required)")
	_ = apply.MarkFlagRequired("file")

	list := &cobra.Command{
		Use:   "list",
		Short: "List the tenant's processes",
		RunE: func(cmd *cobra.Command, _ []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			ps, err := c.ProcessList()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"NAME", "TABLE", "EMIT", "ENABLED"}, ps,
				func(p client.ProcessInfo) []string {
					return []string{p.Name, p.Table, p.Emit, fmt.Sprintf("%t", p.Enabled)}
				})
		},
	}

	cmd.AddCommand(apply, list)
	return cmd
}
