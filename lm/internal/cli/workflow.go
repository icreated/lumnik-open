package cli

import (
	"fmt"
	"io"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

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
			return printApplied(cmd, resp, printWorkflowApplied)
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
			ws, err := c.ListWorkflows()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"NAME", "TABLE", "COLUMN", "SCOPE", "ENABLED"}, ws,
				func(w client.WorkflowInfo) []string {
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

	rm := &cobra.Command{
		Use:   "rm <name>",
		Short: "Delete a declared workflow for good",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.DeleteWorkflow(args[0]); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "workflow %s deleted\n", args[0])
			return nil
		},
	}

	cmd.AddCommand(apply, list, get, rm,
		toggleCmd("workflow", "enable", true, "Turn a workflow back on", workflowSetEnabled),
		toggleCmd("workflow", "disable", false, "Stop a workflow being served, keeping its manifest", workflowSetEnabled))
	return cmd
}

// workflowApplied is the hub's answer to a kind:Workflow apply: what was stored, and what the
// declaration and the data disagree on.
type workflowApplied struct {
	Name      string             `json:"name"`
	Scope     string             `json:"scope"`
	Table     string             `json:"table"`
	Column    string             `json:"column"`
	DataTruth *workflowDataTruth `json:"dataTruth"`
}

type workflowDataTruth struct {
	Undeclared []struct {
		Value string `json:"value"`
		Rows  int    `json:"rows"`
	} `json:"undeclared"`
	Unobserved        []string `json:"unobserved"`
	NullOrBlank       int      `json:"nullOrBlank"`
	TableMissing      bool     `json:"tableMissing"`
	KeyColumnMissing  bool     `json:"keyColumnMissing"`
	KeyNullOrBlank    *int     `json:"keyNullOrBlank"` // null: not measured
	CheckUnavailable  bool     `json:"checkUnavailable"`
	UnavailableReason *string  `json:"unavailableReason"`
	DueProblem        *string  `json:"dueProblem"`
}

// printWorkflowApplied writes the applied line, then one line per finding of the data-truth report.
func printWorkflowApplied(w io.Writer, a workflowApplied) {
	fmt.Fprintf(w, "Applied workflow \"%s\" → %s.%s, scope %s\n", a.Name, a.Table, a.Column, a.Scope)
	t := a.DataTruth
	switch {
	case t == nil:
		return
	case t.TableMissing:
		fmt.Fprintln(w, "  the table is not there yet: the data was not checked")
		return
	case t.CheckUnavailable:
		reason := ""
		if t.UnavailableReason != nil {
			reason = ": " + *t.UnavailableReason
		}
		fmt.Fprintf(w, "  the data could not be checked%s\n", reason)
		return
	}
	var findings []string
	if len(t.Undeclared) > 0 {
		values := make([]string, len(t.Undeclared))
		for i, u := range t.Undeclared {
			values[i] = fmt.Sprintf("%s (%s)", u.Value, output.RowCount(u.Rows))
		}
		findings = append(findings, "in the data, not declared: "+strings.Join(values, ", "))
	}
	if len(t.Unobserved) > 0 {
		findings = append(findings, "declared, not in the data: "+strings.Join(t.Unobserved, ", "))
	}
	if t.NullOrBlank > 0 {
		findings = append(findings, a.Column+" null or blank: "+output.RowCount(t.NullOrBlank))
	}
	if t.KeyColumnMissing {
		findings = append(findings, "spec.on.key: the table has no such column")
	}
	if t.KeyNullOrBlank != nil && *t.KeyNullOrBlank > 0 {
		findings = append(findings, "key null or blank: "+output.RowCount(*t.KeyNullOrBlank)+", never watched")
	}
	if t.DueProblem != nil {
		findings = append(findings, *t.DueProblem)
	}
	if len(findings) == 0 {
		findings = append(findings, "the data matches the declaration")
	}
	for _, f := range findings {
		fmt.Fprintln(w, "  "+f)
	}
}

func workflowSetEnabled(c *client.Client, name string, enabled bool) (string, bool, error) {
	out, err := c.SetWorkflowEnabled(name, enabled)
	if err != nil {
		return "", false, err
	}
	return out.Name, out.Enabled, nil
}
