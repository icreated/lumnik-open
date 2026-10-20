package cli

import (
	"fmt"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newEndpointCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "endpoint",
		Short: "Manage a connector's endpoints (jdbc: one per table; rest: one per spec.endpoints[])",
	}
	cmd.AddCommand(
		newEndpointActionCmd("enable", "Include this endpoint in its connector's runs again", func(cmd *cobra.Command, c *client.Client, cid, eid string) error {
			return c.EndpointEnable(cid, eid, true)
		}),
		newEndpointActionCmd("disable", "Skip this endpoint in its connector's runs", func(cmd *cobra.Command, c *client.Client, cid, eid string) error {
			return c.EndpointEnable(cid, eid, false)
		}),
		newEndpointActionCmd("reset-cursor", "Clear the endpoint's stored cursor: its next run reads everything again", func(cmd *cobra.Command, c *client.Client, cid, eid string) error {
			return c.EndpointResetCursor(cid, eid)
		}),
		newEndpointPurgeCmd(),
	)
	return cmd
}

func newEndpointActionCmd(name, short string, run func(cmd *cobra.Command, c *client.Client, cid, eid string) error) *cobra.Command {
	return &cobra.Command{
		Use:   name + " <connector>/<endpoint>",
		Short: short,
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			connName, epName, err := splitConnectorEndpoint(args[0])
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			cid, err := resolveConnectorID(c, connName)
			if err != nil {
				return err
			}
			eid, err := resolveEndpointID(c, cid, epName)
			if err != nil {
				return err
			}
			return run(cmd, c, cid, eid)
		},
	}
}

func newEndpointPurgeCmd() *cobra.Command {
	var force, dryRun bool
	cmd := &cobra.Command{
		Use:   "purge <connector>/<endpoint>",
		Short: "Delete the rows this endpoint has landed",
		// The hub decides what is purgeable (EndpointResource.purge, #1181).
		Long: "Delete the rows this endpoint has landed, keeping the connector and its other endpoints.\n\n" +
			"Purgeable: the endpoints of a rest-generic connector, and the table of a one-shot\n" +
			"`lm csv ingest`. Refused, the hub says why: the endpoints of a jdbc-generic, csv-file or\n" +
			"kafka connector, whose table does not record which endpoint wrote a row. To drop that\n" +
			"data, delete the connector; its run history goes with it.\n\n" +
			"A refusal comes before any question; otherwise lm states how many rows will go and asks\n" +
			"you to type 'purge'. --dry-run stops at the count.",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			connName, epName, err := splitConnectorEndpoint(args[0])
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			cid, err := resolveConnectorID(c, connName)
			if err != nil {
				return err
			}
			eid, err := resolveEndpointID(c, cid, epName)
			if err != nil {
				return err
			}
			out := cmd.OutOrStdout()
			if !force || dryRun {
				// The dry run first: a refusal arrives before any question, and the question
				// can say what it is about to delete.
				n, err := c.EndpointPurgeDryRun(cid, eid)
				if err != nil {
					return err
				}
				switch {
				case dryRun:
					fmt.Fprintf(out, "Would purge %s (dry run, nothing deleted)\n", output.RowCount(n))
					return nil
				case n == 0:
					fmt.Fprintln(out, "Nothing to purge: this endpoint has no rows")
					return nil
				}
				if err := confirmTyped(cmd, "purge", "delete "+output.RowCount(n)); err != nil {
					return err
				}
			}
			n, err := c.EndpointPurge(cid, eid)
			if err != nil {
				return err
			}
			fmt.Fprintf(out, "Purged %s\n", output.RowCount(n))
			return nil
		},
	}
	cmd.Flags().BoolVar(&force, "force", false, "Skip confirmation")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "Print how many rows would be deleted, delete nothing")
	return cmd
}

// splitConnectorEndpoint parses "connector/endpoint" into its two parts.
func splitConnectorEndpoint(s string) (string, string, error) {
	p := strings.SplitN(s, "/", 2)
	if len(p) != 2 || p[0] == "" || p[1] == "" {
		return "", "", fmt.Errorf("expected format: <connector>/<endpoint>, got %q", s)
	}
	return p[0], p[1], nil
}

// resolveEndpointID looks up an endpoint by name within a connector (by connector ID).
func resolveEndpointID(c *client.Client, connectorID, endpointName string) (string, error) {
	eps, err := c.Endpoints(connectorID)
	if err != nil {
		return "", err
	}
	for _, e := range eps {
		if e.Name == endpointName || e.ID == endpointName {
			return e.ID, nil
		}
	}
	return "", fmt.Errorf("endpoint %q not found on connector %q", endpointName, connectorID)
}
