package cli

import (
	"bufio"
	"fmt"
	"io"
	"strconv"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

var integrationHeaders = []string{"ID", "NAME", "URL", "EVENTS", "ENABLED"}

// readLineFrom reads one line from the command's input — cmd.InOrStdin(), so tests can drive it
// with SetIn. Its other caller is readPassword in source_jdbc.go, which uses it for the piped
// half of the guided flow's password.
func readLineFrom(r io.Reader) (string, error) {
	line, err := bufio.NewReader(r).ReadString('\n')
	if err != nil && line == "" {
		return "", err
	}
	return strings.TrimRight(line, "\r\n"), nil
}

func integrationRow(i client.Integration) []string {
	return []string{strconv.FormatInt(i.ID, 10), i.Name, i.TargetURL,
		strings.Join(i.EventTypes, ","), strconv.FormatBool(i.Enabled)}
}

// newIntegrationCmd is the registration side of the webhook road; `lm outputs` stays the
// delivery journal. The secret is read from stdin (--secret-stdin), never from a flag.
func newIntegrationCmd() *cobra.Command {
	root := &cobra.Command{Use: "integration", Short: "Register webhook targets for the event spine"}

	add := &cobra.Command{
		Use:   "add NAME",
		Args:  cobra.ExactArgs(1),
		Short: "Register a webhook target (forwards events from now on, not retroactively)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			url, _ := cmd.Flags().GetString("url")
			events, _ := cmd.Flags().GetStringSlice("events")
			platform, _ := cmd.Flags().GetString("platform")
			secretStdin, _ := cmd.Flags().GetBool("secret-stdin")
			in := client.IntegrationCreate{Name: args[0], TargetURL: url, EventTypes: events, Platform: platform}
			if secretStdin {
				secret, err := readLineFrom(cmd.InOrStdin())
				if err != nil {
					return fmt.Errorf("reading secret from stdin: %w", err)
				}
				in.Secret = secret
			}
			created, err := c.IntegrationCreate(in)
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "registered: %s (id %d) — forwards subscribed events from now on\n",
				created.Name, created.ID)
			return nil
		},
	}
	add.Flags().String("url", "", "target URL (required)")
	add.Flags().StringSlice("events", []string{"decision.*"}, "event types or prefix patterns")
	add.Flags().String("platform", "", "target platform hint (generic when omitted)")
	add.Flags().Bool("secret-stdin", false, "read the signing secret from stdin")
	_ = add.MarkFlagRequired("url")

	list := &cobra.Command{
		Use:   "list",
		Short: "List registered webhook targets",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			items, err := c.IntegrationList()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format, integrationHeaders, items, integrationRow)
		},
	}

	describe := &cobra.Command{
		Use:   "describe ID",
		Args:  cobra.ExactArgs(1),
		Short: "One target's registration",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := strconv.ParseInt(args[0], 10, 64)
			if err != nil {
				return fmt.Errorf("ID must be numeric (see lm integration list): %w", err)
			}
			item, err := c.IntegrationGet(id)
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format, integrationHeaders,
				[]client.Integration{*item}, integrationRow)
		},
	}

	test := &cobra.Command{
		Use:   "test ID",
		Args:  cobra.ExactArgs(1),
		Short: "Fire a catalog event at the target and report the delivery",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := strconv.ParseInt(args[0], 10, 64)
			if err != nil {
				return fmt.Errorf("ID must be numeric (see lm integration list): %w", err)
			}
			eventType, _ := cmd.Flags().GetString("event")
			res, err := c.IntegrationTest(id, eventType)
			if err != nil {
				return err
			}
			if res.OK {
				fmt.Fprintf(cmd.OutOrStdout(), "delivered in %dms (HTTP %d)\n", res.DurationMs, *res.HTTPStatus)
				return nil
			}
			return fmt.Errorf("delivery failed: %s", res.Error)
		},
	}
	test.Flags().String("event", "org.created", "catalog event type to fire (decision.* types are dynamic and not testable this way)")

	rm := &cobra.Command{
		Use:   "rm ID",
		Args:  cobra.ExactArgs(1),
		Short: "Remove a target (delivery history is kept; in-flight retries give up)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := strconv.ParseInt(args[0], 10, 64)
			if err != nil {
				return fmt.Errorf("ID must be numeric (see lm integration list): %w", err)
			}
			if err := c.IntegrationDelete(id); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "removed: %s\n", args[0])
			return nil
		},
	}

	root.AddCommand(add, list, describe, test, rm)
	return root
}
