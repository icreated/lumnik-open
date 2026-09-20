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
		toggleCmd("process", "enable", true, "Turn a process back on", processSetEnabled),
		toggleCmd("process", "disable", false, "Stop a process firing, keeping its manifest", processSetEnabled))
	return cmd
}

func processSetEnabled(c *client.Client, name string, enabled bool) (string, bool, error) {
	out, err := c.ProcessSetEnabled(name, enabled)
	if err != nil {
		return "", false, err
	}
	return out.Name, out.Enabled, nil
}

// toggleCmd builds `lm <noun> enable|disable <name>` — one shape shared by every kind that
// carries an `enabled` column, so the two verbs cannot drift apart in flags, output or error
// handling, and a second kind (kind:Workflow, #507) does not fork its own copy.
func toggleCmd(noun, verb string, enabled bool, short string,
	setEnabled func(c *client.Client, name string, enabled bool) (string, bool, error)) *cobra.Command {
	return &cobra.Command{
		Use:   verb + " <name>",
		Short: short,
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			name, gotEnabled, err := setEnabled(c, args[0], enabled)
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "%s %s enabled=%t\n", noun, name, gotEnabled)
			return nil
		},
	}
}
