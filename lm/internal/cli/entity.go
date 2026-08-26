package cli

import (
	"encoding/json"
	"fmt"

	"github.com/spf13/cobra"
)

func newEntityCmd() *cobra.Command {
	e := &cobra.Command{Use: "entity", Short: "Manage entity manifests"}
	e.AddCommand(newEntityApplyCmd())
	return e
}

func newEntityApplyCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{
		Use:   "apply",
		Short: "Apply an entity manifest (YAML)",
		RunE: func(cmd *cobra.Command, _ []string) error {
			body, err := readYamlFile(file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			resp, err := c.EntityApply(body)
			if err != nil {
				return err
			}
			var result struct {
				Name string `json:"name"`
				View string `json:"view"`
			}
			if json.Unmarshal(resp, &result) == nil && result.Name != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "Applied entity %q → %s\n", result.Name, result.View)
				return nil
			}
			fmt.Fprintln(cmd.OutOrStdout(), string(resp))
			return nil
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "YAML entity manifest file (required)")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}
