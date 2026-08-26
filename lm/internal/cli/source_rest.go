package cli

import (
	"encoding/json"
	"fmt"
	"io"
	"os"

	"github.com/spf13/cobra"
)

func newSourceRestCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "rest",
		Short: "REST source connector operations",
	}
	cmd.AddCommand(newSourceRestTestCmd(), newSourceRestRunCmd(), newSourceRestFromOpenApiCmd(), newSourceRestProbeCmd())
	return cmd
}

func newSourceRestTestCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "test <connector>",
		Short: "Pre-flight: auth probe + 1 page, no write",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			cid, err := resolveConnectorID(c, args[0])
			if err != nil {
				return err
			}
			// "*" signals test-all-endpoints to the server
			body, err := c.EndpointTest(cid, "*")
			if err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), string(body))
			return nil
		},
	}
}

func newSourceRestRunCmd() *cobra.Command {
	var endpoint string
	var dryRun bool
	var limit int
	cmd := &cobra.Command{
		Use:   "run <connector>",
		Short: "Run an endpoint (with --dry-run flag)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return fmt.Errorf("not implemented yet — defers to /run endpoint API added in Task 12")
		},
	}
	cmd.Flags().StringVar(&endpoint, "endpoint", "", "endpoint name (required)")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "fetch 1 page, do not write")
	cmd.Flags().IntVar(&limit, "limit", 5, "number of sample items to display in dry-run")
	_ = cmd.MarkFlagRequired("endpoint")
	return cmd
}

func newSourceRestFromOpenApiCmd() *cobra.Command {
	var name, output string
	var tags []string
	var apply bool
	cmd := &cobra.Command{
		Use:   "from-openapi <url>",
		Short: "Generate manifest from an OpenAPI spec",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			cli, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			body, err := cli.RestFromOpenApi(args[0], name, tags)
			if err != nil {
				return err
			}
			if output != "" {
				if err := os.WriteFile(output, body, 0644); err != nil {
					return err
				}
			}
			if !apply {
				// `output` is this subcommand's own --output/-o flag (a FILE path;
				// it shadows the root -o format flag here), meaning "write the
				// manifest to a file instead of stdout". When a file was requested,
				// don't also echo the manifest to stdout (keeps scripting quiet).
				if output == "" {
					fmt.Fprintln(cmd.OutOrStdout(), string(body))
				}
				return nil
			}
			resp, err := cli.ApplyManifest(body)
			if err != nil {
				fmt.Fprintln(cmd.ErrOrStderr(), "Generated manifest (NOT applied — error below):")
				fmt.Fprintln(cmd.ErrOrStderr(), string(body))
				return fmt.Errorf("apply failed: %w", err)
			}
			printApplySummary(cmd.OutOrStdout(), resp, name)
			return nil
		},
	}
	cmd.Flags().StringVar(&name, "name", "", "connector name (required)")
	cmd.Flags().StringSliceVar(&tags, "tag", nil, "tags (repeatable)")
	cmd.Flags().StringVarP(&output, "output", "o", "", "write to file instead of stdout")
	cmd.Flags().BoolVar(&apply, "apply", false, "upsert directly after generation (requires --name)")
	_ = cmd.MarkFlagRequired("name")
	return cmd
}

func printApplySummary(w io.Writer, rawJSON []byte, name string) {
	var parsed struct {
		ConnectorId      string `json:"connectorId"`
		EndpointsApplied int    `json:"endpointsApplied"`
	}
	_ = json.Unmarshal(rawJSON, &parsed)
	fmt.Fprintf(w, "Generated and applied manifest for %q:\n", name)
	fmt.Fprintf(w, "  • connector: %s (id: %s)\n", name, parsed.ConnectorId)
	fmt.Fprintf(w, "  • endpoints: %d created\n", parsed.EndpointsApplied)
	// apply already enables every endpoint it writes, and the scheduler wants
	// `enabled = true AND schedule IS NOT NULL` — the draft declares no schedule.
	fmt.Fprintf(w, "Nothing is scheduled yet: the draft declares no `spec.schedule`.\n")
	fmt.Fprintf(w, "Run `lm connector run %s` to fetch now, or set `spec.schedule` and re-apply.\n", name)
}

func newSourceRestProbeCmd() *cobra.Command {
	var endpoint string
	cmd := &cobra.Command{
		Use:   "probe <connector>",
		Short: "Sample fetch + LLM-augmented manifest draft",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			cli, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			cid, err := resolveConnectorID(cli, args[0])
			if err != nil {
				return err
			}
			body, err := cli.RestProbe(cid, endpoint)
			if err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), string(body))
			return nil
		},
	}
	cmd.Flags().StringVar(&endpoint, "endpoint", "", "endpoint path (required)")
	_ = cmd.MarkFlagRequired("endpoint")
	return cmd
}
