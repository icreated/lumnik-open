package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newApplyCmd() *cobra.Command {
	var file string
	cmd := &cobra.Command{
		Use:   "apply",
		Short: "Apply a kind: Connector manifest (YAML), whatever its connector type",
		RunE: func(cmd *cobra.Command, _ []string) error {
			body, err := readManifest(cmd, file)
			if err != nil {
				return err
			}
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			resp, err := c.ApplyManifest(body)
			if err != nil {
				var apiErr *client.APIError
				if errors.As(err, &apiErr) && apiErr.Status == 400 {
					var vr client.ValidationResult
					if json.Unmarshal([]byte(apiErr.Body), &vr) == nil &&
						(len(vr.Errors) > 0 || len(vr.Warnings) > 0) {
						return output.FormatValidation(cmd.OutOrStdout(), manifestLabel(file), rerunCommand(file), &vr)
					}
				}
				return err
			}
			err = printApplied(cmd, resp, func(w io.Writer, a connectorApplied) {
				endpoints := fmt.Sprintf("%d endpoints", a.EndpointsApplied)
				if a.EndpointsApplied == 1 {
					endpoints = "1 endpoint"
				}
				fmt.Fprintf(w, "Applied connector \"%s\" — %s\n", a.Name, endpoints)
			})
			if err != nil {
				return err
			}
			return renderApplyWarnings(cmd, manifestLabel(file), resp)
		},
	}
	cmd.Flags().StringVarP(&file, "file", "f", "", "YAML manifest file, or - to read it from stdin (required)")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}

// connectorApplied is the hub's answer to a kind: Connector apply.
type connectorApplied struct {
	Name             string                   `json:"name"`
	ConnectorID      string                   `json:"connectorId"`
	EndpointsApplied int                      `json:"endpointsApplied"`
	Warnings         []client.ValidationError `json:"warnings,omitempty"`
}

// printApplied prints an apply door's answer: one line for a human by default; under -o json /
// -o yaml, the hub's answer as it came, so a field this lm does not know still reaches the script.
func printApplied[T any](cmd *cobra.Command, resp []byte, human func(io.Writer, T)) error {
	if format := outputFormat(cmd); format != "table" {
		return output.PrintObject(cmd.OutOrStdout(), format, json.RawMessage(resp))
	}
	var applied T
	if err := json.Unmarshal(resp, &applied); err != nil {
		return fmt.Errorf("the hub's answer to the apply is not readable: %w", err)
	}
	human(cmd.OutOrStdout(), applied)
	return nil
}

// renderApplyWarnings prints the advisories a successful apply carried back, and returns nil.
//
// Applied, and still worth saying something: "no scopes declared" decides whether anything a
// connector ingests is askable in any métier, and the hub can only answer it in the body it
// returns (#531). On stderr, because stdout under -o json is the body a script pipes into jq; exit stays
// 0, as a warning has never failed a validate and a CI gate built on that must not start failing.
//
// Every door that calls ApplyManifest goes through here: `lm apply`, and the --apply of
// `lm source rest`, whose --scope is optional and could therefore mint the very scopeless
// connector the advisory is about.
func renderApplyWarnings(cmd *cobra.Command, label string, resp json.RawMessage) error {
	var vr client.ValidationResult
	if json.Unmarshal(resp, &vr) == nil && len(vr.Warnings) > 0 {
		// A 200 means written. FormatValidation returns an error when the result carries errors —
		// which is what the refusal path wants and this one must never do: a stray `errors` in a
		// success body would exit 1 on a manifest the hub had already applied.
		vr.Errors = nil
		return output.FormatValidation(cmd.ErrOrStderr(), label, "", &vr)
	}
	return nil
}
