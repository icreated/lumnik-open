package cli

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
	"github.com/spf13/cobra"
)

func newRagCmd() *cobra.Command {
	r := &cobra.Command{Use: "rag", Short: "RAG corpus operations"}

	search := &cobra.Command{
		Use:   "search QUERY",
		Short: "Semantic search in the RAG corpus",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			topK, _ := cmd.Flags().GetInt("top-k")
			res, err := c.RagSearch(args[0], topK, "")
			if err != nil {
				return err
			}
			format, _ := cmd.Flags().GetString("output")
			return output.Print(cmd.OutOrStdout(), format,
				[]string{"SCORE", "SOURCE", "SNIPPET"}, res.Results,
				func(h client.RagSearchHit) []string {
					return []string{
						strFloat(h.Similarity), h.Ref, truncate(h.Content, 80),
					}
				})
		},
	}
	search.Flags().Int("top-k", 5, "Number of results to return")
	r.AddCommand(search)

	r.AddCommand(&cobra.Command{
		Use:   "status",
		Short: "Show RAG corpus status",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			st, err := c.RagStatus()
			if err != nil {
				return err
			}
			cmd.Printf("corpus_size: %d\nlast_reindex: %s\n", st.CorpusSize, st.LastReindex)
			return nil
		},
	})

	r.AddCommand(&cobra.Command{
		Use:   "reindex",
		Short: "Trigger a full re-index of the corpus",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			if err := c.RagReindex(); err != nil {
				return err
			}
			cmd.Println("reindex triggered")
			return nil
		},
	})
	return r
}

func strFloat(f float64) string {
	return fmt.Sprintf("%.3f", f)
}

// truncate is output.Truncate under this package's old name — the call sites are many and the
// rule belongs beside HumanTime, where lm and its TUI already share one human-rendering decision.
func truncate(s string, n int) string { return output.Truncate(s, n) }
