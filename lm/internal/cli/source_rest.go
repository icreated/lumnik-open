package cli

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strconv"

	"github.com/spf13/cobra"

	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/icreated/lumnik-open/lm/internal/types"
)

// restConnectorType is the only connector_type the REST engine drives; `graphql` and `soap` are
// per-endpoint request shapes on it, not families of their own.
const restConnectorType = "rest-generic"

func newSourceRestCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "rest",
		Short: "REST source connector operations",
	}
	cmd.AddCommand(newSourceRestTestCmd(), newSourceRestRunCmd(), newSourceRestFromOpenApiCmd(), newSourceRestProbeCmd())
	return cmd
}

// newSourceRestTestCmd pre-flights every enabled endpoint of a connector: one request each, the
// one an ingestion run would send first, writing nothing.
//
// The hub has no test-all route — the endpoint verbs each address one endpoint — so the walk is
// here, exactly as `lm endpoint enable|purge` resolve one. (This command used to POST the literal
// "*" as an endpoint id and hoped the server would read it as "all"; the route binds a UUID, so it
// answered 404 to every invocation ever made.)
func newSourceRestTestCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "test <connector>",
		Short: "Pre-flight every enabled endpoint: the request a run would send, no write",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			conn, err := resolveConnector(c, args[0])
			if err != nil {
				return err
			}
			if err := requireRestConnector(conn, args[0], "`lm source rest test` pre-flights REST sources"); err != nil {
				return err
			}
			eps, err := c.Endpoints(conn.ID)
			if err != nil {
				return err
			}

			var verdicts []types.PreflightVerdict
			skipped := 0
			for _, ep := range eps {
				// A disabled endpoint will not run, so a red one here would be a pre-flight
				// failing for something that was never going to be attempted.
				if !ep.Enabled {
					skipped++
					continue
				}
				v, err := c.EndpointTest(conn.ID, ep.ID)
				if err != nil {
					return err
				}
				verdicts = append(verdicts, v)
			}
			if skipped > 0 {
				fmt.Fprintf(cmd.ErrOrStderr(), "note: skipped %d disabled endpoint(s)\n", skipped)
			}
			if len(verdicts) == 0 {
				return fmt.Errorf("no enabled endpoint on %q — nothing was pre-flighted", args[0])
			}

			format, _ := cmd.Flags().GetString("output")
			err = output.Print(cmd.OutOrStdout(), format,
				[]string{"ENDPOINT", "METHOD", "STATUS", "ITEMS", "VERDICT"},
				verdicts, preflightRow)
			if err != nil {
				return err
			}

			failed := 0
			for _, v := range verdicts {
				if !v.OK {
					failed++
				}
			}
			if failed > 0 {
				// A pre-flight exists to be believed before a run is launched: it may not report
				// a refusal on stdout and still exit 0.
				return fmt.Errorf("%d of %d enabled endpoint(s) did not answer with readable data",
					failed, len(verdicts))
			}
			return nil
		},
	}
}

// requireRestConnector refuses a connector of another family BY NAME rather than by symptom: every
// other family lacks the connection blocks the REST engine composes a request from, so walking on
// would report "Unknown pagination kind: null" once per table and never mention the word jdbc.
// Shared by the two commands that reach a REST source directly — test and probe.
func requireRestConnector(conn types.Connector, named, gesture string) error {
	if conn.TypeID == restConnectorType {
		return nil
	}
	return fmt.Errorf("%q is not a %s connector (type: %q) — %s",
		named, restConnectorType, conn.TypeID, gesture)
}

// preflightRow renders one verdict. A status of 0 means the request never got an answer at all
// (unreachable host, a credential that would not resolve) — printed as "-" rather than as a
// status code no server ever sent.
func preflightRow(v types.PreflightVerdict) []string {
	status := "-"
	if v.Status > 0 {
		status = strconv.Itoa(v.Status)
	}
	verdict := "ok"
	if !v.OK {
		verdict = v.Detail
		if verdict == "" {
			verdict = "failed"
		}
	}
	return []string{v.Endpoint, v.Method, status, strconv.Itoa(v.Items), verdict}
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
	var scopes []string
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
			body, err := cli.RestFromOpenApi(args[0], name, scopes)
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
			return renderApplyWarnings(cmd, name, resp)
		},
	}
	cmd.Flags().StringVar(&name, "name", "", "connector name (required)")
	cmd.Flags().StringSliceVar(&scopes, "scope", nil, "métier scope(s) written to the manifest's metadata.scopes (repeatable)")
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
		Short: "Sample one path of a connector and draft its endpoint fragment",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			cli, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			conn, err := resolveConnector(cli, args[0])
			if err != nil {
				return err
			}
			if err := requireRestConnector(conn, args[0], "`lm source rest probe` samples a REST source"); err != nil {
				return err
			}
			body, err := cli.RestProbe(conn.ID, endpoint)
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
