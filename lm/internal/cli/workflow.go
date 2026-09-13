package cli

import (
	"encoding/json"
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

// workflowInfo is one declared kind:Workflow, as listed by the hub.
type workflowInfo struct {
	Name    string `json:"name"`
	Table   string `json:"table"`
	Column  string `json:"column"`
	Scope   string `json:"scope"`
	Enabled bool   `json:"enabled"`
}

func newWorkflowCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "workflow",
		Short: "Manage kind:Workflow manifests (declared document lifecycles)",
	}

	var file string
	apply := &cobra.Command{
		Use:   "apply",
		Short: "Apply a kind:Workflow manifest (YAML)",
		RunE: func(cmd *cobra.Command, _ []string) error {
			body, err := readManifest(cmd, file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			resp, err := c.ApplyWorkflow(body)
			if err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), string(resp))
			return nil
		},
	}
	apply.Flags().StringVarP(&file, "file", "f", "", "kind:Workflow YAML manifest, or - for stdin (required)")
	_ = apply.MarkFlagRequired("file")

	list := &cobra.Command{
		Use:   "list",
		Short: "List the tenant's declared workflows",
		RunE: func(cmd *cobra.Command, _ []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			data, err := c.ListWorkflows()
			if err != nil {
				return err
			}
			var ws []workflowInfo
			if err := json.Unmarshal(data, &ws); err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"NAME", "TABLE", "COLUMN", "SCOPE", "ENABLED"}, ws,
				func(w workflowInfo) []string {
					return []string{w.Name, w.Table, w.Column, w.Scope, fmt.Sprintf("%t", w.Enabled)}
				})
		},
	}

	get := &cobra.Command{
		Use:   "get NAME",
		Short: "Fetch a declared workflow's manifest YAML",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			yaml, err := c.GetWorkflowManifest(args[0])
			if err != nil {
				return err
			}
			fmt.Fprint(cmd.OutOrStdout(), string(yaml))
			return nil
		},
	}

	cmd.AddCommand(apply, list, get)
	return cmd
}
