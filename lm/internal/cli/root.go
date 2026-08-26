package cli

import (
	"fmt"
	"io"

	"github.com/icreated/lumnik-open/lm/internal/textsafe"
	"github.com/spf13/cobra"
)

func NewRootCmd() *cobra.Command {
	root := &cobra.Command{
		Use:           "lm",
		Short:         "lumnik CLI",
		Long:          "lm — kubectl-style CLI and k9s-style TUI for the lumnik platform.",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	root.PersistentFlags().String("context", "", "Override the current context from ~/.lm/config.yaml")
	root.PersistentFlags().String("server", "", "Override the server URL")
	root.PersistentFlags().String("token", "", "Override the auth token")
	root.PersistentFlags().StringP("output", "o", "table", "Output format: table|json|yaml")
	root.PersistentFlags().BoolP("verbose", "v", false, "Verbose output (debug logs)")
	root.AddCommand(newVersionCmd())
	root.AddCommand(newConfigCmd())
	root.AddCommand(newLoginCmd())
	root.AddCommand(newRagCmd())
	root.AddCommand(newAskCmd())
	root.AddCommand(newChatCmd())
	root.AddCommand(newHookCmd())
	root.AddCommand(newIntegrationCmd())
	root.AddCommand(newNotifyCmd())
	root.AddCommand(newModuleCmd())
	root.AddCommand(newMigrationCmd())
	root.AddCommand(newConnectorCmd())
	root.AddCommand(newEntityCmd())
	root.AddCommand(newViewCmd())
	root.AddCommand(newProcessCmd())
	root.AddCommand(newWorkflowCmd())
	root.AddCommand(newInboxCmd())
	root.AddCommand(newScopeCmd())
	root.AddCommand(newScopesCmd())
	root.AddCommand(newDlqCmd())
	root.AddCommand(newMappingCmd())
	root.AddCommand(newDataCmd())
	root.AddCommand(newSourceCmd())
	root.AddCommand(newApplyCmd())
	root.AddCommand(newValidateCmd())
	root.AddCommand(newDescribeCmd())
	root.AddCommand(newCsvCmd())
	root.AddCommand(newEndpointCmd())
	root.AddCommand(newSecretCmd())
	root.AddCommand(newUiCmd())
	root.AddCommand(newRunCmd())
	root.AddCommand(newConflictsCmd())
	root.AddCommand(newObservationsCmd())
	root.AddCommand(newOutputsCmd())
	root.RunE = func(cmd *cobra.Command, args []string) error {
		if len(args) == 0 {
			return newUiCmd().RunE(cmd, args)
		}
		return cmd.Help()
	}
	return root
}

// Run installs textsafe.Writer as the default stdout/stderr for the command
// tree, so human-facing output (errors, tables, plain text) cannot reflect a
// raw control/bidi rune into a terminal. Output explicitly requested in a machine
// format (a --output json / -o json|yaml flag) opts out by unwrapping to the raw
// target writer (see textsafe.Unwrap) and stays byte-exact. It executes root and
// returns the process exit code. The returned-error print (root has SilenceErrors)
// reuses the same stderr
// Writer — a single instance keeps the carry buffer (incomplete trailing rune)
// intact and avoids an extra allocation; that print is the path that produced the
// OTK text findings.
func Run(root *cobra.Command, stdout, stderr io.Writer) int {
	errW := textsafe.NewWriter(stderr)
	root.SetOut(textsafe.NewWriter(stdout))
	root.SetErr(errW)
	if err := root.Execute(); err != nil {
		fmt.Fprintf(errW, "Error: %v\n", err)
		return 1
	}
	return 0
}

// machineOut returns the byte-exact writer for a command's stdout, bypassing the
// textsafe sanitizer. Use it ONLY when the user explicitly requested a machine
// format (a --output json / -o json|yaml flag): there a consumer may byte-compare
// or feed a non-JSON-aware tool. Default command output — even when it echoes a
// raw server JSON body — must keep the sanitizing wrapper (cmd.OutOrStdout()), or
// it can reflect a control/bidi rune into the operator's terminal.
func machineOut(cmd *cobra.Command) io.Writer { return textsafe.Unwrap(cmd.OutOrStdout()) }
