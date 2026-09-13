package cli

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newSecretCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "secret",
		Short: "Manage platform-stored secrets (encrypted at rest)",
	}
	cmd.AddCommand(
		newSecretSetCmd(),
		newSecretListCmd(),
		newSecretDescribeCmd(),
		newSecretRmCmd(),
	)
	return cmd
}

func newSecretSetCmd() *cobra.Command {
	var description string
	cmd := &cobra.Command{
		Use:   "set <NAME> [VALUE]",
		Short: "Create or update a secret. Omit VALUE to be prompted; '-' reads stdin until EOF.",
		Args:  cobra.RangeArgs(1, 2),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := args[0]
			// Same order as the guided jdbc flow: a context that is missing must say so before
			// the caller types a secret, not after.
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			value, err := secretValue(cmd, args)
			if err != nil {
				return err
			}
			if err := c.SecretCreate(name, value, description); err != nil {
				// Fall back to update on conflict (409)
				if strings.Contains(err.Error(), "409") || strings.Contains(err.Error(), "already exists") {
					if err := c.SecretUpdate(name, value, description); err != nil {
						return err
					}
					fmt.Fprintf(cmd.OutOrStdout(), "Updated secret %q\n", name)
					return nil
				}
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "Created secret %q\n", name)
			return nil
		},
	}
	cmd.Flags().StringVar(&description, "description", "", "Human-readable label")
	return cmd
}

func newSecretListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List all secrets (metadata only; values never exposed)",
		RunE: func(cmd *cobra.Command, _ []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			secrets, err := c.SecretList()
			if err != nil {
				return err
			}
			// -o is a persistent root flag: asked for JSON here, a pipeline used to get this
			// page of columns. The metadata is safe to emit — SecretMeta has no value field,
			// and the values are write-only at the hub.
			if format, _ := cmd.Flags().GetString("output"); format != "table" {
				return output.PrintObject(cmd.OutOrStdout(), format, secrets)
			}
			if len(secrets) == 0 {
				fmt.Fprintln(cmd.OutOrStdout(), "(no secrets)")
				return nil
			}
			fmt.Fprintf(cmd.OutOrStdout(), "%-30s %-30s %s\n", "NAME", "ROTATED_AT", "DESCRIPTION")
			for _, s := range secrets {
				fmt.Fprintf(cmd.OutOrStdout(), "%-30s %-30s %s\n", s.Name, orDash(s.RotatedAt), s.Description)
			}
			return nil
		},
	}
}

func newSecretDescribeCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "describe <NAME>",
		Short: "Show metadata for one secret (value never exposed)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			s, err := c.SecretDescribe(args[0])
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "name:        %s\n", s.Name)
			fmt.Fprintf(cmd.OutOrStdout(), "description: %s\n", s.Description)
			fmt.Fprintf(cmd.OutOrStdout(), "created_at:  %s   by %s\n", orDash(s.CreatedAt), orDashInt(s.CreatedBy))
			fmt.Fprintf(cmd.OutOrStdout(), "updated_at:  %s   by %s\n", orDash(s.UpdatedAt), orDashInt(s.UpdatedBy))
			fmt.Fprintf(cmd.OutOrStdout(), "rotated_at:  %s\n", orDash(s.RotatedAt))
			return nil
		},
	}
}

func newSecretRmCmd() *cobra.Command {
	var force bool
	cmd := &cobra.Command{
		Use:   "rm <NAME>",
		Short: "Soft-delete a secret",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !force {
				fmt.Fprintf(cmd.OutOrStdout(), "Type 'delete' to confirm removal of %q: ", args[0])
				r := bufio.NewReader(os.Stdin)
				s, _ := r.ReadString('\n')
				if strings.TrimSpace(s) != "delete" {
					return fmt.Errorf("aborted")
				}
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.SecretDelete(args[0]); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "Deleted secret %q\n", args[0])
			return nil
		},
	}
	cmd.Flags().BoolVar(&force, "force", false, "Skip confirmation prompt")
	return cmd
}

// secretValue resolves the value from the three ways it can arrive, and says what each costs.
//
//   - no VALUE, at a keyboard: prompted, not echoed. This is the form the docs teach.
//   - VALUE is '-': read until EOF, unchanged — a PEM key is many lines, and a reader that
//     stopped at the first would store a truncated secret and still print "Created".
//   - VALUE given: kept working because scripts pass it, but it was in `ps` while the command
//     ran and it is in the shell history afterwards, so it does not stay silent.
//
// With no VALUE and no keyboard there is nothing to prompt and nothing declared to read: guessing
// stdin there would swallow whatever a surrounding loop was reading, so it refuses instead.
func secretValue(cmd *cobra.Command, args []string) (string, error) {
	name := args[0]
	if len(args) == 1 {
		tty, ok := terminalIn(cmd)
		if !ok {
			return "", fmt.Errorf("no VALUE given and stdin is not a terminal — use `lm secret set %s -` to read it from stdin", name)
		}
		return promptSecret(cmd, tty, "Value for "+name)
	}
	if !isStdinSentinel(args[1]) {
		fmt.Fprintf(cmd.ErrOrStderr(), "warning: the value was visible in `ps` and is now in your shell history"+
			" — `lm secret set %s` prompts for it instead, `lm secret set %s -` reads it from stdin\n", name, name)
		return args[1], nil
	}
	if _, ok := terminalIn(cmd); ok {
		fmt.Fprintf(cmd.ErrOrStderr(), "warning: reading until EOF ("+eofGesture()+") from the terminal — what you type stays"+
			" on screen; `lm secret set %s` prompts without echo\n", name)
	}
	b, err := io.ReadAll(cmd.InOrStdin())
	if err != nil {
		return "", err
	}
	return strings.TrimRight(string(b), "\n\r"), nil
}

func isStdinSentinel(s string) bool { return s == "-" }

func orDash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}

func orDashInt(i *int64) string {
	if i == nil {
		return "-"
	}
	return fmt.Sprintf("%d", *i)
}
