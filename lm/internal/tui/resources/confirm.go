package resources

import "strings"

// ConfirmAs asks the integrator a yes/no question and calls yes only on a yes. Every destructive key
// goes through one: Del used to delete at once, on whatever the cursor sat on (#1110). The caller
// names the yes button beside the question it answers — Delete, Remove, Reindex, Re-stamp, Reset,
// Purge, Assert — so a gesture is never answered by a button it did not choose (#1158).
type ConfirmAs func(question, yesLabel string, yes func())

// step is one question of a guided prompt sequence; required stops the sequence on an empty answer.
type step struct {
	label            string
	masked, required bool
}

// askAll asks steps in order, one Prompt after the other, and hands done every answer trimmed —
// except a masked one, kept as typed. An empty answer to a required step ends the sequence there.
func askAll(ask Prompt, steps []step, done func(answers []string)) {
	var answers []string
	var next func(i int)
	next = func(i int) {
		if i == len(steps) {
			done(answers)
			return
		}
		ask(steps[i].label, steps[i].masked, func(a string) {
			if !steps[i].masked {
				a = strings.TrimSpace(a)
			}
			if steps[i].required && strings.TrimSpace(a) == "" {
				return
			}
			answers = append(answers, a)
			next(i + 1)
		})
	}
	next(0)
}

// say hands msg to a page's toast or error sink, which a bare page in a test may lack.
func say(to func(string), msg string) {
	if to != nil {
		to(msg)
	}
}
