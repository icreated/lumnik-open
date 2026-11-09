package cli

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

var notifyHeaders = []string{"ID", "EVENTS", "RECIPIENT", "TEMPLATE", "ENABLED"}

var mailDeliveryHeaders = []string{"ID", "RECIPIENT", "EVENT", "STATUS", "ATTEMPTS", "WHEN", "ERROR"}

func mailDeliveryRow(d client.MailDelivery) []string {
	return []string{strconv.FormatInt(d.ID, 10), d.Recipient, d.EventType, d.Status,
		strconv.Itoa(d.Attempts), output.HumanTime(&d.At), d.Error}
}

func notifyRow(r client.NotificationRule) []string {
	return []string{strconv.FormatInt(r.ID, 10), r.EventPattern, r.Recipient,
		r.Template, strconv.FormatBool(r.Enabled)}
}

// newNotifyCmd manages email notification rules — the spine's third consumer. Delivery
// needs the SMTP knobs configured on the deploy (see docs); rules without SMTP wait.
func newNotifyCmd() *cobra.Command {
	root := &cobra.Command{Use: "notify", Short: "Route matching events to email recipients"}

	add := &cobra.Command{
		Use:   "add",
		Short: "Add a rule: events matching --events go to --to",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			events, _ := cmd.Flags().GetString("events")
			to, _ := cmd.Flags().GetString("to")
			template, _ := cmd.Flags().GetString("template")
			created, err := c.NotificationRuleCreate(client.NotificationRuleCreate{
				EventPattern: events, Recipient: to, Template: template})
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "rule %d: %s → %s\n",
				created.ID, created.EventPattern, created.Recipient)
			return nil
		},
	}
	add.Flags().String("events", "", "event type, or a prefix ending in .* (required)")
	add.Flags().String("to", "", "recipient email (required)")
	add.Flags().String("template", "", "template name (default: event-notification)")
	_ = add.MarkFlagRequired("events")
	_ = add.MarkFlagRequired("to")

	list := &cobra.Command{
		Use:   "list",
		Short: "List email rules",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			items, err := c.NotificationRuleList()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format, notifyHeaders, items, notifyRow)
		},
	}

	rm := &cobra.Command{
		Use:   "rm ID",
		Args:  cobra.ExactArgs(1),
		Short: "Remove a rule",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := strconv.ParseInt(strings.TrimSpace(args[0]), 10, 64)
			if err != nil {
				return fmt.Errorf("ID must be numeric (see lm notify list): %w", err)
			}
			if err := c.NotificationRuleDelete(id); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "removed: %s\n", args[0])
			return nil
		},
	}

	deliveries := &cobra.Command{
		Use:   "deliveries",
		Short: "List the mails the rules asked for, given-up ones first",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			items, err := c.MailDeliveryList(pageLimit(cmd))
			if err != nil {
				return err
			}
			return printPage(cmd, mailDeliveryHeaders, items, mailDeliveryRow)
		},
	}
	addPageLimitFlag(deliveries, 200, client.MailDeliveriesPageCap, "max deliveries to list")

	replay := &cobra.Command{
		Use:   "replay ID",
		Short: "Send one given-up mail again (the delivery keeps its row; no second mail is recorded)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			id, err := strconv.ParseInt(strings.TrimSpace(args[0]), 10, 64)
			if err != nil {
				return fmt.Errorf("ID must be a delivery number, as in the ID column of lm notify deliveries")
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			status, err := c.MailDeliveryReplay(id)
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "delivery %d: %s\n", id, status)
			return nil
		},
	}

	root.AddCommand(add, list, rm, deliveries, replay)
	return root
}
