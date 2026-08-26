package cli

import (
	"fmt"
	"os"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newMappingCmd() *cobra.Command {
	m := &cobra.Command{Use: "mapping", Short: "Manage connector mappings (template + per-tenant override)"}

	m.AddCommand(&cobra.Command{
		Use:   "list",
		Short: "List mapping templates",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			ms, err := c.MappingList()
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"ID", "VERSION", "TARGET"}, ms,
				func(m client.Mapping) []string {
					return []string{m.ID, fmt.Sprintf("%d", m.Version), m.Target}
				})
		},
	})

	get := &cobra.Command{
		Use:   "get NAME",
		Args:  cobra.ExactArgs(1),
		Short: "Show effective mapping (template + override merged)",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			raw, _ := cmd.Flags().GetBool("raw")
			if raw {
				y, err := c.MappingGetRaw(args[0])
				if err != nil {
					return err
				}
				cmd.Print(y)
				return nil
			}
			d, err := c.MappingGet(args[0])
			if err != nil {
				return err
			}
			cmd.Printf("id: %s\nversion: %d\ntarget: %s\nmode: %s\ndataset: %d hooks\nrow: %d hooks\ncell: %d hooks\n",
				d.ID, d.Version, d.Target, d.Mode, d.Dataset, d.Row, d.Cell)
			return nil
		},
	}
	get.Flags().Bool("raw", false, "Show raw template YAML")
	m.AddCommand(get)

	apply := &cobra.Command{
		Use:   "apply",
		Short: "Apply per-tenant override from YAML file",
		RunE: func(cmd *cobra.Command, args []string) error {
			file, _ := cmd.Flags().GetString("file")
			data, err := os.ReadFile(file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			id := extractMappingID(string(data))
			if id == "" {
				return fmt.Errorf("mapping yaml missing 'id' at top level")
			}
			if err := c.MappingApply(id, string(data)); err != nil {
				return err
			}
			cmd.Println("override applied for mapping:", id)
			return nil
		},
	}
	apply.Flags().StringP("file", "f", "", "Path to override YAML")
	_ = apply.MarkFlagRequired("file")
	m.AddCommand(apply)

	return m
}

func extractMappingID(yaml string) string {
	for _, line := range strings.Split(yaml, "\n") {
		trimmed := strings.TrimSpace(line)
		if after, ok := strings.CutPrefix(trimmed, "id:"); ok {
			return strings.TrimSpace(after)
		}
	}
	return ""
}
