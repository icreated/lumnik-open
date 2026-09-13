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
			body, err := readManifest(cmd, file)
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
	apply.Flags().StringVarP(&file, "file", "f", "", "kind:Process YAML manifest, or - for stdin (required)")
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

	rm := &cobra.Command{
		Use:   "rm <name>",
		Short: "Delete a declared process for good",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.ProcessDelete(args[0]); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "process %s deleted\n", args[0])
			return nil
		},
	}

	cmd.AddCommand(apply, list, rm,
		toggleCmd("enable", true, "Turn a process back on"),
		toggleCmd("disable", false, "Stop a process firing, keeping its manifest"))
	return cmd
}

// toggleCmd builds `lm process enable|disable <name>` — one shape, so the two verbs cannot drift
// apart in flags, output or error handling.
func toggleCmd(verb string, enabled bool, short string) *cobra.Command {
	return &cobra.Command{
		Use:   verb + " <name>",
		Short: short,
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			out, err := c.ProcessSetEnabled(args[0], enabled)
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "process %s enabled=%t\n", out.Name, out.Enabled)
			return nil
		},
	}
}
