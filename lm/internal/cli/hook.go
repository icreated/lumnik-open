package cli

import (
	"fmt"
	"strconv"

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
		Use:   "scripts",
		Short: "List the Kotlin scripts a manifest can name as `script: hooks:<name>.kts`",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			hs, err := c.HookScripts()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			if format != "table" {
				return output.PrintObject(cmd.OutOrStdout(), format, hs)
			}
			switch {
			case !hs.Directory:
				cmd.PrintErrln("This hub has no hooks directory: its operator sets LUMNIK_HOOKS_DIR to offer hooks: scripts.")
				return nil
			case len(hs.Scripts) == 0:
				cmd.PrintErrln("The hooks directory holds no scripts.")
				return nil
			}
			return output.Print(cmd.OutOrStdout(), format, []string{"NAME", "SIZE", "SHA256"}, hs.Scripts,
				func(s types.HookScript) []string {
					return []string{s.Name, strconv.FormatInt(s.Size, 10), s.SHA256}
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

	// apply, delete, reload and generate do nothing: hidden from --help, still callable so a script
	// that calls one reads why instead of "unknown command".
	h.AddCommand(&cobra.Command{
		Use:    "apply",
		Short:  "Not available — hooks come from deployment manifests",
		Hidden: true,
		RunE: func(cmd *cobra.Command, args []string) error {
			return hookMutationNotImplemented("apply")
		},
	})

	h.AddCommand(&cobra.Command{
		Use:    "delete NAME",
		Short:  "Not available — hooks come from deployment manifests",
		Hidden: true,
		Args:   cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return hookMutationNotImplemented("delete")
		},
	})

	h.AddCommand(&cobra.Command{
		Use:    "reload NAME",
		Short:  "Not available — hooks come from deployment manifests",
		Hidden: true,
		Args:   cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return hookMutationNotImplemented("reload")
		},
	})

	h.AddCommand(&cobra.Command{
		Use:    "generate",
		Short:  "Not available — write the hook script yourself",
		Hidden: true,
		RunE: func(cmd *cobra.Command, args []string) error {
			return fmt.Errorf("`lm hook generate` is not available: write the .kts script yourself; `lm hook scripts` lists the ones this hub offers")
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
