package cli

import (
	"fmt"

	"github.com/spf13/cobra"
)

// newTokenCmd prints an access token of the current context: the `lm login` session's, renewed when it is about to
// expire, and the renewal kept (#1997). A script or a curl recipe uses it instead of the password grant:
//
//	curl -H "Authorization: Bearer $(lm token)" …
//
// A token lives 5 minutes on the bundled realm: call it again for each call or batch; --fresh renews it first, so a
// run that holds one token gets its whole lifespan.
func newTokenCmd() *cobra.Command {
	var fresh bool
	cmd := &cobra.Command{
		Use:   "token",
		Short: "Print an access token of the current context, renewed if it is about to expire",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			tok, err := c.Token(fresh)
			if err != nil {
				return err
			}
			if tok == "" {
				return fmt.Errorf("this context holds no token: run 'lm login'")
			}
			fmt.Fprintln(cmd.OutOrStdout(), tok)
			return nil
		},
	}
	cmd.Flags().BoolVar(&fresh, "fresh", false, "renew the token first, so it lives its whole lifespan (for a long run)")
	return cmd
}
