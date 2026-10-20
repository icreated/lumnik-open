package cli

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/spf13/cobra"
	"gopkg.in/yaml.v3"
)

func newConfigCmd() *cobra.Command {
	c := &cobra.Command{Use: "config", Short: "Manage ~/.lm/config.yaml"}
	view := &cobra.Command{
		Use:   "view",
		Short: "Print the current config as YAML, credentials redacted",
		RunE: func(cmd *cobra.Command, args []string) error {
			raw, _ := cmd.Flags().GetBool("raw")
			path, err := config.DefaultPath()
			if err != nil {
				return err
			}
			cfg, err := config.LoadFromFile(path)
			if err != nil {
				return err
			}
			if !raw {
				cfg = redacted(cfg)
			}
			data, err := yaml.Marshal(cfg)
			if err != nil {
				return err
			}
			cmd.Print(string(data))
			return nil
		},
	}
	view.Flags().Bool("raw", false, "Print the tokens in clear instead of REDACTED")
	c.AddCommand(view)
	c.AddCommand(&cobra.Command{
		Use:   "use-context NAME",
		Short: "Set the current context",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			path, err := config.DefaultPath()
			if err != nil {
				return err
			}
			cfg, err := config.LoadFromFile(path)
			if err != nil {
				return err
			}
			if _, ok := cfg.Contexts[args[0]]; !ok {
				return fmt.Errorf("context %q does not exist", args[0])
			}
			cfg.CurrentContext = args[0]
			return cfg.Save(path)
		},
	})
	setCtx := &cobra.Command{
		Use:   "set-context NAME",
		Short: "Create or update a context",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			server, _ := cmd.Flags().GetString("server")
			token, _ := cmd.Flags().GetString("token")
			oidcIssuer, _ := cmd.Flags().GetString("oidc-issuer")
			oidcClientID, _ := cmd.Flags().GetString("oidc-client-id")
			path, err := config.DefaultPath()
			if err != nil {
				return err
			}
			cfg, err := config.LoadFromFile(path)
			if err != nil {
				return err
			}
			if cfg.Contexts == nil {
				cfg.Contexts = map[string]config.Context{}
			}
			ctx := cfg.Contexts[args[0]]
			if server != "" {
				ctx.Server = server
			}
			if token != "" {
				ctx.Token = token
			}
			if oidcIssuer != "" && oidcIssuer != ctx.OidcIssuer {
				// Only when the issuer actually CHANGES: a new issuer invalidates everything
				// derived from the old one — the discovered endpoints AND the stored tokens
				// (minted by the old issuer). Clear both so `lm login` re-discovers and
				// re-authenticates. Re-setting the SAME issuer stays idempotent (no logout).
				ctx.OidcIssuer = oidcIssuer
				ctx.OidcDeviceEndpoint = ""
				ctx.OidcTokenEndpoint = ""
				ctx.Access = ""
				ctx.Refresh = ""
				ctx.ExpiresAt = 0
			}
			if oidcClientID != "" {
				ctx.OidcClientID = oidcClientID
			}
			cfg.Contexts[args[0]] = ctx
			return cfg.Save(path)
		},
	}
	setCtx.Flags().String("server", "", "Server URL")
	setCtx.Flags().String("token", "", "Auth token")
	setCtx.Flags().String("oidc-issuer", "", "OIDC issuer URL (lm login discovers endpoints from it)")
	setCtx.Flags().String("oidc-client-id", "", "OIDC client id for device flow (default: lm-cli)")
	c.AddCommand(setCtx)
	return c
}

// redacted returns a copy of cfg whose set credentials read REDACTED (kubectl's
// convention); empty ones stay empty. cfg itself is left untouched.
func redacted(cfg *config.Config) *config.Config {
	out := *cfg
	out.Contexts = make(map[string]config.Context, len(cfg.Contexts))
	for name, ctx := range cfg.Contexts {
		for _, secret := range []*string{&ctx.Token, &ctx.Access, &ctx.Refresh} {
			if *secret != "" {
				*secret = "REDACTED"
			}
		}
		out.Contexts[name] = ctx
	}
	return &out
}
