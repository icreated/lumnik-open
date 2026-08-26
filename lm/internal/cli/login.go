package cli

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/login"
	"github.com/spf13/cobra"
)

func newLoginCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "login",
		Short: "Authenticate via OIDC device flow and store tokens in the current context",
		RunE: func(cmd *cobra.Command, args []string) error {
			path, err := config.DefaultPath()
			if err != nil {
				return err
			}
			cfg, err := config.LoadFromFile(path)
			if err != nil {
				return err
			}
			// Honor the root --context override (like ClientFromCmd), else current-context.
			name, _ := cmd.Flags().GetString("context")
			if name == "" {
				name = cfg.CurrentContext
			}
			if name == "" {
				return fmt.Errorf("no context: pass --context or set a current-context (configure one with 'lm config')")
			}
			ctx, ok := cfg.Contexts[name]
			if !ok {
				return fmt.Errorf("context %q not found in config", name)
			}
			return login.Run(cmd.Context(), login.HTTPClient(ctx), cfg, path, name, cmd.OutOrStdout())
		},
	}
}
