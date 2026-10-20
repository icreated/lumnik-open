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

// integrationTargetNote is list's and describe's help: what the table shows of a target, and why
// -o json|yaml shows more.
const integrationTargetNote = `The table shows each target's scheme and host only: a webhook URL's user,
path and query are often its credential. -o json and -o yaml print the hub's
body as it is, the full target URL included: that is the machine contract,
and asking for it is your explicit choice.`

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

// confirmTyped asks "Type '<word>' to <what>: " and goes on only when the integrator types word.
// With nothing to read — a script, stdin at /dev/null — it names --force rather than a bare
// "aborted", so the script's author learns what to pass.
func confirmTyped(cmd *cobra.Command, word, what string) error {
	fmt.Fprintf(cmd.OutOrStdout(), "Type '%s' to %s: ", word, what)
	s, err := readLineFrom(cmd.InOrStdin())
	if err != nil {
		return fmt.Errorf("aborted: no confirmation read (%v) — pass --force in a script", err)
	}
	if strings.TrimSpace(s) != word {
		return fmt.Errorf("aborted")
	}
	return nil
}

// integrationRow shows the target's host only; integrationTargetNote says why (#1158).
func integrationRow(i client.Integration) []string {
	return []string{strconv.FormatInt(i.ID, 10), i.Name, output.TargetHost(i.TargetURL),
		strings.Join(i.EventTypes, ","), strconv.FormatBool(i.Enabled)}
}

// deliveryFailed is a failed test's error. The hub's text can quote the target URL, credential
// included, so it is shown with the target's host only — list's and describe's rule (#1160). The
// target is read here, on failure only; when that read fails too the text is withheld, since it
// cannot be scrubbed, and only the status is said.
func deliveryFailed(c *client.Client, id int64, res *client.IntegrationTestResult) error {
	target, err := c.IntegrationGet(id)
	if err != nil {
		status := "no response"
		if res.HTTPStatus != nil {
			status = "HTTP " + strconv.Itoa(*res.HTTPStatus)
		}
		return fmt.Errorf("delivery failed (%s); the hub's message is withheld: it may quote the target URL, which lm could not read to hide", status)
	}
	return fmt.Errorf("delivery failed: %s", output.ScrubURL(res.Error, target.TargetURL))
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
	add.Flags().Bool("secret-stdin", false, "read the signing secret from stdin (required: the hub refuses a target without one)")
	_ = add.MarkFlagRequired("url")

	list := &cobra.Command{
		Use:   "list",
		Short: "List registered webhook targets",
		Long:  integrationTargetNote,
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
		Long:  integrationTargetNote,
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
			return deliveryFailed(c, id, res)
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
