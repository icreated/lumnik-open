package cli

import (
	"fmt"
	"strings"

	"github.com/google/uuid"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/spf13/cobra"
	"gopkg.in/yaml.v3"
)

// resolveConnectorID looks up a connector ID either by direct UUID match or by name.
// Returns the resolved ID (UUID string) or an error if not found.
// resolveConnectorFilter turns a --connector value into the id the hub's UUID-typed filters
// require. A well-formed UUID passes through unchanged: it saves listing every connector to map
// an id onto itself, and it keeps a full id filtering even when it names a since-deleted
// connector. Validate the SHAPE, not the length — connector names carry no pattern constraint,
// so a 36-character NAME must still resolve rather than be forwarded raw.
func resolveConnectorFilter(c *client.Client, value string) (string, error) {
	if value == "" || uuid.Validate(value) == nil {
		return value, nil
	}
	return resolveConnectorID(c, value)
}

func resolveConnectorID(c *client.Client, idOrName string) (string, error) {
	conn, err := resolveConnector(c, idOrName)
	return conn.ID, err
}

// resolveConnector is the same lookup for callers that need more of the row than its id — the REST
// pre-flight reads TypeID so it can name a connector of another family instead of failing deep
// inside a request builder that has no idea why the config makes no sense.
func resolveConnector(c *client.Client, idOrName string) (types.Connector, error) {
	list, err := c.ConnectorList()
	if err != nil {
		return types.Connector{}, err
	}
	for _, item := range list {
		if item.ID == idOrName || item.Name == idOrName {
			return item, nil
		}
	}
	return types.Connector{}, fmt.Errorf("connector %q not found", idOrName)
}

func newConnectorCmd() *cobra.Command {
	cn := &cobra.Command{Use: "connector", Short: "Manage data connectors"}

	cn.AddCommand(&cobra.Command{
		Use:   "list",
		Short: "List connectors",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			cs, err := c.ConnectorList()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"NAME", "TYPE", "TENANT", "SCHED", "WINDOW", "STATUS", "LAST RUN"}, cs,
				func(c types.Connector) []string {
					return []string{c.Name, c.TypeID, fmt.Sprintf("%d", c.TenantID), c.Schedule,
						c.Window, c.Status, output.HumanTime(c.LastRunAt)}
				})
		},
	})

	cn.AddCommand(&cobra.Command{
		Use:  "get NAME",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := resolveConnectorID(c, args[0])
			if err != nil {
				return err
			}
			conn, err := c.ConnectorGet(id)
			if err != nil {
				return err
			}
			data, _ := yaml.Marshal(conn)
			cmd.Print(string(data))
			return nil
		},
	})

	apply := &cobra.Command{
		Use:   "apply",
		Short: "Create or update a connector from its `get` YAML (flat fields, not a manifest)",
		Long: "Create or update a connector from the FLAT stored fields — tenantId, name, typeId,\n" +
			"schedule, window, enabled, config — which is what `lm connector get` writes. The designed loop is\n" +
			"`get` → edit → `apply`, the same one the TUI runs against $EDITOR.\n\n" +
			"A `kind: Connector` manifest goes to `lm apply -f` instead: different format,\n" +
			"different endpoint. Hand one here and this command says so rather than posting it.",
		RunE: func(cmd *cobra.Command, args []string) error {
			file, _ := cmd.Flags().GetString("file")
			body, err := readManifest(cmd, file)
			if err != nil {
				return err
			}
			payload, err := decodeManifest(body, file, "connector")
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, _ := payload["id"].(string)
			if id != "" {
				_, err = c.ConnectorUpdate(id, payload)
			} else {
				_, err = c.ConnectorCreate(payload)
			}
			if err != nil {
				return err
			}
			cmd.Println("applied")
			return nil
		},
	}
	apply.Flags().StringP("file", "f", "", "Path to connector YAML, or - for stdin")
	_ = apply.MarkFlagRequired("file")
	cn.AddCommand(apply)

	cn.AddCommand(&cobra.Command{
		Use:  "delete NAME",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := resolveConnectorID(c, args[0])
			if err != nil {
				return err
			}
			if err := c.ConnectorDelete(id); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "deleted:", args[0])
			return nil
		},
	})

	cn.AddCommand(&cobra.Command{
		Use:  "run NAME",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := resolveConnectorID(c, args[0])
			if err != nil {
				return err
			}
			out, err := c.ConnectorRun(id)
			if err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "run scheduled:", out["runId"])
			return nil
		},
	})

	cn.AddCommand(&cobra.Command{
		Use:  "pause NAME",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := resolveConnectorID(c, args[0])
			if err != nil {
				return err
			}
			if err := c.ConnectorPause(id); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "paused:", args[0])
			return nil
		},
	})

	cn.AddCommand(&cobra.Command{
		Use:  "resume NAME",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := resolveConnectorID(c, args[0])
			if err != nil {
				return err
			}
			if err := c.ConnectorResume(id); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "resumed:", args[0])
			return nil
		},
	})

	// The scope is the métier boundary a bound user must hold (scope:<name>) to read what the
	// connector ingests — declared as metadata.scopes in the manifest. The wire keeps the field
	// name `tags` (GET/PUT /connectors/{id}/tags), so an older lm keeps talking to a newer hub.
	scope := &cobra.Command{Use: "scope", Short: "Manage the connector's métier scopes"}

	scope.AddCommand(&cobra.Command{
		Use:  "ls NAME",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := resolveConnectorID(c, args[0])
			if err != nil {
				return err
			}
			tags, err := c.ConnectorGetTags(id)
			if err != nil {
				return err
			}
			for _, t := range tags {
				fmt.Fprintln(cmd.OutOrStdout(), t)
			}
			return nil
		},
	})

	scope.AddCommand(&cobra.Command{
		Use:  "set NAME SCOPES",
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := resolveConnectorID(c, args[0])
			if err != nil {
				return err
			}
			tags := client.SplitScopes(args[1])
			out, err := c.ConnectorSetTags(id, tags)
			if err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "scopes:", strings.Join(out, ","))
			return nil
		},
	})

	scope.AddCommand(&cobra.Command{
		Use:  "add NAME SCOPE",
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := resolveConnectorID(c, args[0])
			if err != nil {
				return err
			}
			current, err := c.ConnectorGetTags(id)
			if err != nil {
				return err
			}
			for _, t := range current {
				if t == args[1] {
					fmt.Fprintln(cmd.OutOrStdout(), "no-op:", args[1], "already present")
					return nil
				}
			}
			next := append(append([]string{}, current...), args[1])
			out, err := c.ConnectorSetTags(id, next)
			if err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "scopes:", strings.Join(out, ","))
			return nil
		},
	})

	scope.AddCommand(&cobra.Command{
		Use:  "rm NAME SCOPE",
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := resolveConnectorID(c, args[0])
			if err != nil {
				return err
			}
			current, err := c.ConnectorGetTags(id)
			if err != nil {
				return err
			}
			next := make([]string, 0, len(current))
			for _, t := range current {
				if t != args[1] {
					next = append(next, t)
				}
			}
			out, err := c.ConnectorSetTags(id, next)
			if err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "scopes:", strings.Join(out, ","))
			return nil
		},
	})

	scope.AddCommand(&cobra.Command{
		Use:   "sync NAME",
		Short: "Re-stamp the connector's indexed chunks with its current scopes",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id, err := resolveConnectorID(c, args[0])
			if err != nil {
				return err
			}
			out, err := c.ConnectorRetag(id)
			if err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "chunks updated:", out["chunksUpdated"])
			return nil
		},
	})
	cn.AddCommand(scope)

	return cn
}
