package cli

import (
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/spf13/cobra"
)

// ClientFromCmd builds an HTTP client by resolving the context referenced by
// the --context flag (or current-context from the config file). Flag overrides
// for --server / --token win over file values.
func ClientFromCmd(cmd *cobra.Command) (*client.Client, error) {
	path, err := config.DefaultPath()
	if err != nil {
		return nil, err
	}
	cfg, err := config.LoadFromFile(path)
	if err != nil {
		return nil, err
	}
	ctxName, _ := cmd.Flags().GetString("context")
	ctx, err := cfg.Resolve(ctxName)
	if err != nil {
		return nil, err
	}
	if v, _ := cmd.Flags().GetString("server"); v != "" {
		ctx.Server = v
	}
	tokenOverride, _ := cmd.Flags().GetString("token")
	if tokenOverride != "" {
		ctx.Token = tokenOverride
	}
	// The effective context name (explicit --context, else current-context) is what
	// refreshed tokens must be persisted to — never blindly current-context.
	effectiveName := ctxName
	if effectiveName == "" {
		effectiveName = cfg.CurrentContext
	}
	c := client.NewForContext(effectiveName, ctx)
	if tokenOverride != "" {
		// Typed on the command line: it IS the token, not a fallback queued behind
		// the stored session. See client.PinStaticToken for why this is not done by
		// clearing ctx.Access.
		c.PinStaticToken()
	}
	return c, nil
}
