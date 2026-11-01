package cli

import (
	"fmt"
	"strings"

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
			if err := setIDFlag(cmd, "tenant", &ctx.Tenant); err != nil {
				return err
			}
			if err := setIDFlag(cmd, "user", &ctx.User); err != nil {
				return err
			}
			if err := config.CheckIdentity(ctx); err != nil {
				return err
			}
			cfg.Contexts[args[0]] = ctx
			return cfg.Save(path)
		},
	}
	setCtx.Flags().String("server", "", "Server URL")
	setCtx.Flags().String("token", "", "Auth token")
	setCtx.Flags().String("oidc-issuer", "", "OIDC issuer URL (lm login discovers endpoints from it)")
	setCtx.Flags().String("oidc-client-id", "", "OIDC client id for device flow (default: lm-cli)")
	setCtx.Flags().String("tenant", "", "Dev-bypass hub only (make k8s-up): tenant id sent as X-Tenant-ID; empty clears it")
	setCtx.Flags().String("user", "", "Dev-bypass hub only (make k8s-up): user id sent as X-User-ID; empty clears it")
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

// setIDFlag copies a given --tenant / --user into dst — the dev-bypass identity sent as X-Tenant-ID / X-User-ID,
// which an OIDC hub ignores (#1610). Empty clears it; anything but a positive integer is refused by the flag.
func setIDFlag(cmd *cobra.Command, flag string, dst *string) error {
	if !cmd.Flags().Changed(flag) {
		return nil
	}
	v, _ := cmd.Flags().GetString(flag)
	v = strings.TrimSpace(v) // the hub strips the header before parsing it; store what it will read
	if !config.ValidID(v) {
		return fmt.Errorf("--%s must be a positive integer (the hub's %s id)", flag, flag)
	}
	*dst = v
	return nil
}
