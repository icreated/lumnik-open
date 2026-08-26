package cli

import (
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/spf13/cobra"
)

func newCsvCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "csv",
		Short: "Quick CSV operations (one-shot ingest)",
	}
	cmd.AddCommand(newCsvIngestCmd())
	return cmd
}

func newCsvIngestCmd() *cobra.Command {
	var name, scope string
	var tags []string
	var limit int

	// Remote transport flags
	var sftpKey, sftpPwd string
	var sftpPort int
	var strictHostKey bool
	var hostKeyFingerprint string
	var s3Region, s3Endpoint, s3AccessKey, s3SecretKey string
	var pattern, suffix string

	cmd := &cobra.Command{
		Use:   "ingest <file-or-url>",
		Short: "One-shot CSV ingest: infer schema, write to ext.*, optionally index for RAG",
		Long:  "Ingest a CSV from a local path, sftp://..., or s3://...",
		Args:  cobra.ExactArgs(1),
		RunE: func(c *cobra.Command, args []string) error {
			cli, err := ClientFromCmd(c)
			if err != nil {
				return err
			}
			arg := args[0]
			scopeVal := scope
			if scopeVal == "" {
				scopeVal = "system"
			}

			if strings.HasPrefix(arg, "sftp://") || strings.HasPrefix(arg, "s3://") {
				transport, err := buildTransport(arg, sftpKey, sftpPwd, s3Region, s3Endpoint, s3AccessKey, s3SecretKey, pattern, suffix, sftpPort, strictHostKey, hostKeyFingerprint)
				if err != nil {
					return err
				}
				result, err := cli.CsvIngestRemote(transport, name, scopeVal, tags, limit)
				if err != nil {
					return err
				}
				result.SourceFile = arg
				printCsvIngestSummary(c.OutOrStdout(), result)
				return nil
			}

			// Local file path (existing behavior)
			f, err := os.Open(arg)
			if err != nil {
				return err
			}
			defer f.Close()
			result, err := cli.CsvIngest(f, arg, client.CsvIngestOpts{
				Name:       name,
				Scope:      scopeVal,
				Tags:       tags,
				Limit:      limit,
			})
			if err != nil {
				return err
			}
			printCsvIngestSummary(c.OutOrStdout(), result)
			return nil
		},
	}

	cmd.Flags().StringVar(&name, "name", "", "connector name; a globbed sftp/s3 source must match a single file (default = filename basename)")
	cmd.Flags().StringVar(&scope, "scope", "system", "scope tag stamped on the ingested rows")
	cmd.Flags().StringSliceVar(&tags, "tag", nil, "additional tags (repeatable)")
	cmd.Flags().IntVar(&limit, "limit", -1, "max rows (default: all)")

	// SFTP flags
	cmd.Flags().StringVar(&sftpKey, "key-secret", "", "SFTP: secret name for private SSH key")
	cmd.Flags().StringVar(&sftpPwd, "password-secret", "", "SFTP: secret name for password")
	cmd.Flags().IntVar(&sftpPort, "port", 22, "SFTP: port")
	cmd.Flags().BoolVar(&strictHostKey, "strict-host-key", true,
		"SFTP: refuse to connect unless the host key matches --host-key-fingerprint")
	cmd.Flags().StringVar(&hostKeyFingerprint, "host-key-fingerprint", "",
		"SFTP: expected host key, as ssh-keygen -lf prints it (SHA256:...)")
	cmd.Flags().StringVar(&pattern, "pattern", "*.csv", "SFTP: glob pattern under URL path")

	// S3 flags
	cmd.Flags().StringVar(&s3Region, "region", "", "S3: region (required for s3://)")
	cmd.Flags().StringVar(&s3Endpoint, "endpoint", "", "S3: custom endpoint (MinIO/R2/GCS)")
	cmd.Flags().StringVar(&s3AccessKey, "access-key-secret", "", "S3: secret name for access key id")
	cmd.Flags().StringVar(&s3SecretKey, "secret-key-secret", "", "S3: secret name for secret access key")
	cmd.Flags().StringVar(&suffix, "suffix", "", "S3: filter objects by suffix")

	return cmd
}

func printCsvIngestSummary(w io.Writer, r *client.CsvIngestResult) {
	fmt.Fprintf(w, "Ingested %s into %s\n", r.SourceFile, r.TableName)
	fmt.Fprintf(w, "  • rows written:    %d\n", r.RowsWritten)
	fmt.Fprintf(w, "  • promoted cols:   %d\n", len(r.PromoteColumns))
	for _, pc := range r.PromoteColumns {
		fmt.Fprintf(w, "      - %s (%s)\n", pc.Name, pc.Type)
	}
	if len(r.Warnings) > 0 {
		fmt.Fprintln(w, "  • warnings:")
		for _, msg := range r.Warnings {
			fmt.Fprintf(w, "      - %s\n", msg)
		}
	}
	// Not "Ready to chat": a one-shot records no connector run and indexes no corpus, so the
	// scope holds no schema card and `lm ask` — which is always analytic — refuses on its first
	// line (AskToSqlAgent: store.load(...).orElseThrow(NoSchemaException)). Proven by execution
	// in CsvIngestServiceTest. Say what exists instead.
	fmt.Fprintf(w, "\nA one-shot: no run recorded, nothing indexed for chat.\n")
	fmt.Fprintf(w, "The rows are in %s, and a dormant connector is registered — see `lm connector list`.\n", r.TableName)
}
