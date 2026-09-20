package cli

import (
	"fmt"

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
			// -o is a persistent root flag, so it arrives here whether or not this command looks
			// at it. table and yaml keep today's rendering (the default already IS the YAML
			// below); only json was silently ignored (#532).
			if format, _ := cmd.Flags().GetString("output"); format == "json" {
				return output.PrintObject(cmd.OutOrStdout(), "json", hk)
			}
			data, _ := yaml.Marshal(hk)
			cmd.Print(string(data))
			return nil
		},
	})

	h.AddCommand(&cobra.Command{
		Use:   "apply",
		Short: "(stub) Apply is not implemented — hooks come from deployment manifests",
		RunE: func(cmd *cobra.Command, args []string) error {
			return hookMutationNotImplemented("apply")
		},
	})

	h.AddCommand(&cobra.Command{
		Use:   "delete NAME",
		Short: "(stub) Delete is not implemented — hooks come from deployment manifests",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return hookMutationNotImplemented("delete")
		},
	})

	h.AddCommand(&cobra.Command{
		Use:   "reload NAME",
		Short: "(stub) Reload is not implemented — hooks come from deployment manifests",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return hookMutationNotImplemented("reload")
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

// hookMutationNotImplemented refuses apply/delete/reload before any file is read or client
// built: none of the endpoints they used to call exist on the hub — HookResource declares only
// GET (list) and GET/{id}, and HookRegistry has no update/delete method at all. Hooks are
// registered once, in-memory, from classpath manifests (HookManifestScanner's
// META-INF/hooks.yaml) at startup — there is nothing here to apply, delete or reload
// individually (#610).
func hookMutationNotImplemented(verb string) error {
	return fmt.Errorf("`lm hook %s` is not implemented: hooks are loaded from deployment manifests at startup", verb)
}
