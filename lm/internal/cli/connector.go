package cli

import (
	"fmt"
	"os"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/spf13/cobra"
	"gopkg.in/yaml.v3"
)

// resolveConnectorID looks up a connector ID either by direct UUID match or by name.
// Returns the resolved ID (UUID string) or an error if not found.
func resolveConnectorID(c *client.Client, idOrName string) (string, error) {
	list, err := c.ConnectorList()
	if err != nil {
		return "", err
	}
	for _, item := range list {
		if item.ID == idOrName || item.Name == idOrName {
			return item.ID, nil
		}
	}
	return "", fmt.Errorf("connector %q not found", idOrName)
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
				[]string{"NAME", "TYPE", "TENANT", "SCHED", "STATUS"}, cs,
				func(c types.Connector) []string {
					return []string{c.Name, c.TypeID, fmt.Sprintf("%d", c.TenantID), c.Schedule, c.Status}
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
		Short: "Create or update a connector from YAML",
		RunE: func(cmd *cobra.Command, args []string) error {
			file, _ := cmd.Flags().GetString("file")
			data, err := os.ReadFile(file)
			if err != nil {
				return err
			}
			var payload map[string]any
			if err := yaml.Unmarshal(data, &payload); err != nil {
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
	apply.Flags().StringP("file", "f", "", "Path to connector YAML")
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
			return c.ConnectorPause(id)
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
			return c.ConnectorResume(id)
		},
	})

	tag := &cobra.Command{Use: "tag", Short: "Manage connector tags"}

	tag.AddCommand(&cobra.Command{
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

	tag.AddCommand(&cobra.Command{
		Use:  "set NAME TAGS",
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
			tags := splitTags(args[1])
			out, err := c.ConnectorSetTags(id, tags)
			if err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "tags:", strings.Join(out, ","))
			return nil
		},
	})

	tag.AddCommand(&cobra.Command{
		Use:  "add NAME TAG",
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
			fmt.Fprintln(cmd.OutOrStdout(), "tags:", strings.Join(out, ","))
			return nil
		},
	})

	tag.AddCommand(&cobra.Command{
		Use:  "rm NAME TAG",
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
			fmt.Fprintln(cmd.OutOrStdout(), "tags:", strings.Join(out, ","))
			return nil
		},
	})

	cn.AddCommand(tag)

	cn.AddCommand(&cobra.Command{
		Use:   "retag NAME",
		Short: "Propagate connector tags to its rag.chunk rows",
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

	return cn
}

func splitTags(s string) []string {
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}
