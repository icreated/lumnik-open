package io.lumnik.hub.proposal;

import java.util.Map;

/**
 * What a dossier's checks are, given only its answers (contract section 2, "What the site does").
 * The hub's import exception: it recomputes instead of believing the state the file carries in, so a
 * carried {@code confirmed} over an {@code unknown} answer comes back {@code missing}. Evidence is
 * never touched — it stays attached as history.
 */
final class Derivation {

    private Derivation() {}

    static String check(Dossier.Condition c, Map<String, Dossier.Answer> answers) {
        if (c.backedBy() == null) return "missing";
        Dossier.Answer answer = answers.get(c.backedBy());
        if (answer == null || answer.value() == null) return "missing";
        return answer.value().equals("unknown") ? "missing" : "unverified";
    }

    static Dossier recompute(Dossier d) {
        Map<String, Dossier.Answer> answers = d.answersById();
        return d.withLeads(d.leads().stream().map(l -> l.withConditions(
            l.conditions().stream().map(c -> c.withCheck(check(c, answers))).toList())).toList());
    }
}
