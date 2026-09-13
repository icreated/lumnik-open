package cli

import (
	"bufio"
	"fmt"
	"os"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/textsafe"

	"github.com/spf13/cobra"
)

type chatSession struct {
	corpus string
	// scope is the métier this REPL is aimed at — the `scope` field of POST
	// /api/platform/rag/chat. Empty hands the question back to the server, which
	// falls back to the user's default (`lm scope set`), then to 'system'.
	scope string
	topK  int
}

func newChatCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "chat",
		Short: "Open an interactive REPL chat with your data",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := ClientFromCmd(cmd)
			if err != nil {
				return err
			}
			session := &chatSession{topK: 10}

			fmt.Fprintln(cmd.OutOrStdout(), "lm chat — type :help for commands, :exit to quit")
			scanner := bufio.NewScanner(os.Stdin)
			for {
				fmt.Fprintf(cmd.OutOrStdout(), "\n[corpus=%s scope=%s topK=%d] > ",
					coalesceStr(session.corpus, "*"), coalesceStr(session.scope, "default"), session.topK)
				if !scanner.Scan() {
					return nil
				}
				line := strings.TrimSpace(scanner.Text())
				if line == "" {
					continue
				}
				if strings.HasPrefix(line, ":") {
					if exit := handleSlash(cmd, session, line); exit {
						return nil
					}
					continue
				}
				res, err := c.RagChatScoped(line, session.corpus, session.scope, session.topK)
				if err != nil {
					fmt.Fprintln(cmd.ErrOrStderr(), "ERROR:", err)
					continue
				}
				fmt.Fprintln(cmd.OutOrStdout())
				fmt.Fprintln(cmd.OutOrStdout(), res.Answer)
				if len(res.Citations) > 0 {
					fmt.Fprintln(cmd.OutOrStdout())
					fmt.Fprintln(cmd.OutOrStdout(), "Sources:")
					for _, h := range res.Citations {
						fmt.Fprintf(cmd.OutOrStdout(), "  [row:%s]\n", h)
					}
				}
			}
		},
	}
}

func handleSlash(cmd *cobra.Command, s *chatSession, line string) (exit bool) {
	parts := strings.SplitN(line, " ", 2)
	switch parts[0] {
	case ":exit", ":q", ":quit":
		return true
	case ":help", ":h":
		fmt.Fprintln(cmd.OutOrStdout(), `Commands:
  :corpus NAME     Scope chat to a corpus (or 'all' / '*' for all)
  :scope TAG       Bind chat to one métier scope (or 'default' for your own)
  :topK N          Set retrieval top-K
  :clear           Clear screen
  :help / :h       Show this help
  :exit / :q       Quit`)
	case ":corpus":
		if len(parts) < 2 {
			fmt.Fprintln(cmd.OutOrStdout(), "current corpus:", coalesceStr(s.corpus, "*"))
			return false
		}
		val := strings.TrimSpace(parts[1])
		if val == "all" || val == "*" {
			s.corpus = ""
		} else {
			s.corpus = val
		}
		fmt.Fprintln(cmd.OutOrStdout(), "corpus →", coalesceStr(s.corpus, "*"))
	case ":scope":
		if len(parts) < 2 {
			fmt.Fprintln(cmd.OutOrStdout(), "current scope:", coalesceStr(s.scope, "default"))
			return false
		}
		val := strings.TrimSpace(parts[1])
		// 'default' is this command's 'all': it hands the choice back to the server, which
		// answers from the user's own default scope rather than from a scope named here.
		if val == "default" {
			s.scope = ""
		} else {
			s.scope = val
		}
		fmt.Fprintln(cmd.OutOrStdout(), "scope →", coalesceStr(s.scope, "default"))
	case ":topK":
		if len(parts) < 2 {
			fmt.Fprintln(cmd.OutOrStdout(), "current topK:", s.topK)
			return false
		}
		var n int
		_, err := fmt.Sscanf(parts[1], "%d", &n)
		if err != nil || n < 1 {
			fmt.Fprintln(cmd.ErrOrStderr(), "invalid topK, must be positive int")
			return false
		}
		s.topK = n
		fmt.Fprintln(cmd.OutOrStdout(), "topK →", n)
	case ":clear":
		// Straight to the raw writer: stdout is a textsafe.Writer and ESC is a C0 control rune,
		// so the guarded path would print the sequence instead of obeying it. Safe here for the
		// one reason that licenses the bypass anywhere — this is our own compile-time constant.
		// Every other branch above writes user- or server-derived text and keeps the guard.
		fmt.Fprint(textsafe.Unwrap(cmd.OutOrStdout()), "\033[H\033[2J")
	default:
		fmt.Fprintln(cmd.ErrOrStderr(), "unknown command:", parts[0])
	}
	return false
}

func coalesceStr(a, fallback string) string {
	if a == "" {
		return fallback
	}
	return a
}
