package cli

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"strings"

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
		Use:   "set <NAME> <VALUE>",
		Short: "Create or update a secret. Use '-' as VALUE to read from stdin.",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			name, raw := args[0], args[1]
			value := raw
			if isStdinSentinel(raw) {
				b, err := io.ReadAll(os.Stdin)
				if err != nil {
					return err
				}
				value = strings.TrimRight(string(b), "\n\r")
			}
			c, err := ClientFromCmd(cmd)
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
