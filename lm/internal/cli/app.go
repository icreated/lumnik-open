package cli

import (
	"encoding/json"
	"fmt"

	"github.com/spf13/cobra"
)

func newAppCmd() *cobra.Command {
	a := &cobra.Command{Use: "app", Short: "Manage app manifests"}
	a.AddCommand(newAppApplyCmd(), newAppRmCmd())
	return a
}

func newAppRmCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "rm <name>",
		Short: "Delete a declared app for good",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.AppDelete(args[0]); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "app %s deleted\n", args[0])
			return nil
		},
	}
}

func newAppApplyCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{
		Use:   "apply",
		Short: "Apply an app manifest (YAML) — the métier PWA's title, scope, icon and theme",
		RunE: func(cmd *cobra.Command, _ []string) error {
			body, err := readManifest(cmd, file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			resp, err := c.AppApply(body)
			if err != nil {
				return err
			}
			var result struct {
				Name           string `json:"name"`
				Scope          string `json:"scope"`
				Default        bool   `json:"default"`
				DemotedDefault string `json:"demotedDefault"`
			}
			if json.Unmarshal(resp, &result) == nil && result.Name != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "Applied app %q → scope %s\n", result.Name, result.Scope)
				if result.Default {
					// An apply that promotes a default silently demotes another app. Saying so is
					// the difference between a registry change and a surprise.
					if result.DemotedDefault != "" {
						fmt.Fprintf(cmd.OutOrStdout(),
							"  default app — %q is no longer the default\n", result.DemotedDefault)
					} else {
						fmt.Fprintln(cmd.OutOrStdout(), "  default app")
					}
				}
				return nil
			}
			fmt.Fprintln(cmd.OutOrStdout(), string(resp))
			return nil
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "YAML app manifest file, or - for stdin (required)")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}
