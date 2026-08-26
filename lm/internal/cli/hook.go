package cli

import (
	"fmt"
	"os"

	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/spf13/cobra"
	"gopkg.in/yaml.v3"
)

func newHookCmd() *cobra.Command {
	h := &cobra.Command{Use: "hook", Short: "Kotlin hook lifecycle"}

	h.AddCommand(&cobra.Command{
		Use:   "list",
		Short: "List installed hooks",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			hs, err := c.HookList()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"NAME", "TYPE", "ENTITY", "SCOPE", "STATUS"}, hs,
				func(hk types.Hook) []string {
					return []string{hk.ID, hk.Type, hk.Entity, hk.Scope, hk.Status}
				})
		},
	})

	h.AddCommand(&cobra.Command{
		Use:   "get NAME",
		Short: "Show a hook (YAML)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			hk, err := c.HookGet(args[0])
			if err != nil {
				return err
			}
			data, _ := yaml.Marshal(hk)
			cmd.Print(string(data))
			return nil
		},
	})

	apply := &cobra.Command{
		Use:   "apply",
		Short: "Apply a hook from YAML file",
		RunE: func(cmd *cobra.Command, args []string) error {
			file, _ := cmd.Flags().GetString("file")
			data, err := os.ReadFile(file)
			if err != nil {
				return err
			}
			var payload map[string]any
			if err := yaml.Unmarshal(data, &payload); err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.HookApply(payload); err != nil {
				return err
			}
			id, _ := payload["id"].(string)
			cmd.Println("hook applied:", id)
			return nil
		},
	}
	apply.Flags().StringP("file", "f", "", "Path to hook YAML")
	_ = apply.MarkFlagRequired("file")
	h.AddCommand(apply)

	h.AddCommand(&cobra.Command{
		Use:   "delete NAME",
		Short: "Delete a hook",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.HookDelete(args[0]); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "deleted:", args[0])
			return nil
		},
	})

	h.AddCommand(&cobra.Command{
		Use:   "reload NAME",
		Short: "Reload a hook",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.HookReload(args[0]); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "reloaded:", args[0])
			return nil
		},
	})

	h.AddCommand(&cobra.Command{
		Use:   "generate",
		Short: "(stub) Hook scaffold generation — re-implemented in v1.x",
		RunE: func(cmd *cobra.Command, args []string) error {
			return fmt.Errorf("`lm hook generate` is not yet implemented in Go — use Plan 2+ scaffold")
		},
	})

	return h
}
