package cli

import (
	"fmt"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/spf13/cobra"
)

func newEndpointCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "endpoint",
		Short: "Manage REST connector endpoints",
	}
	cmd.AddCommand(
		newEndpointActionCmd("enable", func(cmd *cobra.Command, c *client.Client, cid, eid string) error {
			return c.EndpointEnable(cid, eid, true)
		}),
		newEndpointActionCmd("disable", func(cmd *cobra.Command, c *client.Client, cid, eid string) error {
			return c.EndpointEnable(cid, eid, false)
		}),
		newEndpointActionCmd("reset-cursor", func(cmd *cobra.Command, c *client.Client, cid, eid string) error {
			return c.EndpointResetCursor(cid, eid)
		}),
		newEndpointPurgeCmd(),
	)
	return cmd
}

func newEndpointActionCmd(name string, run func(cmd *cobra.Command, c *client.Client, cid, eid string) error) *cobra.Command {
	return &cobra.Command{
		Use:   name + " <connector>/<endpoint>",
		Short: name + " an endpoint",
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
	var force bool
	cmd := &cobra.Command{
		Use:   "purge <connector>/<endpoint>",
		Short: "Delete all ext.* rows for an endpoint",
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
			if !force {
				fmt.Fprint(cmd.OutOrStdout(), "Type 'purge' to confirm: ")
				var s string
				fmt.Scanln(&s)
				if s != "purge" {
					return fmt.Errorf("aborted")
				}
			}
			n, err := c.EndpointPurge(cid, eid)
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "Purged %d rows\n", n)
			return nil
		},
	}
	cmd.Flags().BoolVar(&force, "force", false, "Skip confirmation")
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
