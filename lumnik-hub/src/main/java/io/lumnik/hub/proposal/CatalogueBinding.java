package io.lumnik.hub.proposal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which leads of a dossier the hub vouches for (spec 2026-10-04, section 3). The catalogue is the
 * authority: a lead is bound only when it is, structurally, the catalogue's rule — its {@code when}
 * satisfied by the answers, its {@code proposes} equal, its conditions exactly the rule's (one
 * occurrence of each {@code needs}), each with the catalogue's {@code verifier} and the
 * catalogue's {@code asked-by} as {@code backed-by}. Presentation texts are free. The dossier's own
 * ids are kept elsewhere; this only decides who may be verified.
 */
final class CatalogueBinding {

    private CatalogueBinding() {}

    static Set<String> bound(Dossier d, TrustedCatalogue trusted) {
        Set<String> out = new LinkedHashSet<>();
        if (trusted.held(d.catalogue()).isEmpty()) return out;
        Map<String, Integer> carried = new HashMap<>();
        d.leads().forEach(l -> carried.merge(l.rule(), 1, Integer::sum));
        Map<String, Dossier.Answer> answers = d.answersById();
        for (Dossier.Lead lead : d.leads()) {
            if (carried.get(lead.rule()) > 1) continue;
            trusted.rule(lead.rule()).ifPresent(rule -> {
                if (matches(lead, rule, answers)) out.add(lead.id());
            });
        }
        return out;
    }

    private static boolean matches(Dossier.Lead lead, Catalogue.Rule rule, Map<String, Dossier.Answer> answers) {
        Dossier.Answer asked = answers.get(rule.when().answer());
        if (asked == null || asked.value() == null || !asked.value().equals(rule.when().is())) return false;
        if (!rule.proposes().kind().equals(lead.proposes().kind()) || !rule.proposes().uses().equals(lead.proposes().uses())) return false;
        Map<String, Catalogue.Condition> trusted = new HashMap<>();
        for (Catalogue.Condition c : rule.conditions()) {
            if (trusted.put(c.needs(), c) != null) return false;          // a catalogue defect: abstain
        }
        Set<String> seen = new HashSet<>();
        for (Dossier.Condition c : lead.conditions()) {
            if (!seen.add(c.needs())) return false;                       // one needs, once
            Catalogue.Condition t = trusted.get(c.needs());
            if (t == null) return false;
            if (!t.verifier().equals(c.verifier())) return false;
            if (!Objects.equals(t.askedBy(), c.backedBy())) return false;
        }
        return seen.equals(trusted.keySet());
    }
}
