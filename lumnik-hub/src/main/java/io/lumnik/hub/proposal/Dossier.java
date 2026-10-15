package io.lumnik.hub.proposal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A proposal dossier, schema 1, as the hub holds it (spec 2026-10-04, section 5). Data, plus the
 * {@code with…} copies that change one field. An optional JSON member that is absent is {@code null}
 * — the contract's one spelling of "absent": an absent {@code evidence} is never an empty list.
 */
record Dossier(String catalogue, String created, String language, String words,
               List<Answer> answers, List<Lead> leads) {

    record Answer(String id, String kind, String asked, String value, String label, String source) {}

    record Lead(String id, String rule, String source, String title, String says,
                List<String> doesNotSay, Proposes proposes, List<Condition> conditions) {
        Lead withConditions(List<Condition> conditions) {
            return new Lead(id, rule, source, title, says, doesNotSay, proposes, conditions);
        }
    }

    record Proposes(String kind, List<String> uses) {}

    record Condition(String id, String needs, String verifier, String label, String backedBy,
                     String check, String note, List<Evidence> evidence) {
        Condition withCheck(String check) {
            return new Condition(id, needs, verifier, label, backedBy, check, note, evidence);
        }

        Condition withEvidence(List<Evidence> evidence) {
            return new Condition(id, needs, verifier, label, backedBy, check, note, evidence);
        }
    }

    record Evidence(String source, String via, String target, String observed) {}

    Dossier withLeads(List<Lead> leads) {
        return new Dossier(catalogue, created, language, words, answers, leads);
    }

    Map<String, Answer> answersById() {
        Map<String, Answer> byId = new HashMap<>();
        answers.forEach(a -> byId.put(a.id(), a));
        return byId;
    }

    /** Some condition carries evidence: the contract's {@code valid-recheck} cue. */
    boolean carriesEvidence() {
        return leads.stream().flatMap(l -> l.conditions().stream()).anyMatch(c -> c.evidence() != null);
    }
}
