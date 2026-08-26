package cli

import (
	"fmt"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newScopesCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "scopes",
		Short: "List all RAG scopes (tags) available in this tenant",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			scopes, err := c.RagScopes()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"SCOPE", "CONNECTORS", "CHUNKS", "LAST INDEXED"}, scopes,
				func(s client.RagScope) []string {
					return []string{s.Scope, fmt.Sprintf("%d", s.Connectors),
						fmt.Sprintf("%d", s.Chunks), s.LastIndexed}
				})
		},
	}
}

func newScopeCmd() *cobra.Command {
	sc := &cobra.Command{
		Use:   "scope",
		Short: "Show or set the user's default RAG scope",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			current, err := c.GetMyScope()
			if err != nil {
				return err
			}
			if current == "" {
				fmt.Fprintln(cmd.OutOrStdout(), "(no default scope — fallback to 'system')")
			} else {
				fmt.Fprintln(cmd.OutOrStdout(), current)
			}
			return nil
		},
	}
	sc.AddCommand(&cobra.Command{
		Use:   "set TAG",
		Short: "Set the user's default RAG scope (use empty string to clear)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			scope := strings.TrimSpace(args[0])
			if err := c.SetMyScope(scope); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "default scope set:", scope)
			return nil
		},
	})
	return sc
}
