package io.lumnik.hub.proposal;

import io.lumnik.hub.workflow.WorkflowDataTruth;
import io.lumnik.hub.workflow.WorkflowGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Recompute, bind, verify (spec 2026-10-04, sections 1-3). Without a designated workflow the answer
 * is the recomputed dossier. With one, each bound lead's conditions are dispatched by the
 * CATALOGUE's verifier; a conclusion raises an {@code unverified}/{@code missing} condition and
 * appends its evidence once. Never lowers a check, never touches a {@code verifier: none}
 * condition, never touches the tenant: {@code replay} is the read-only probe, called at most once.
 */
final class DossierChecker {

    private DossierChecker() {}

    static Dossier check(Dossier d, TrustedCatalogue trusted, Optional<WorkflowGraph> designated,
                         Function<WorkflowGraph, WorkflowDataTruth.Report> replay) {
        Dossier recomputed = Derivation.recompute(d);
        if (designated.isEmpty()) return recomputed;
        WorkflowGraph graph = designated.get();
        Set<String> bound = CatalogueBinding.bound(recomputed, trusted);
        WorkflowDataTruth.Report[] report = new WorkflowDataTruth.Report[1];
        List<Dossier.Lead> leads = new ArrayList<>();
        for (Dossier.Lead lead : recomputed.leads()) {
            if (!bound.contains(lead.id())) {
                leads.add(lead);
                continue;
            }
            Map<String, String> verifiers = new HashMap<>();
            trusted.rule(lead.rule()).orElseThrow().conditions().forEach(c -> verifiers.put(c.needs(), c.verifier()));
            List<Dossier.Condition> conditions = new ArrayList<>();
            for (Dossier.Condition c : lead.conditions()) {
                Optional<WorkflowEvidence.Conclusion> conclusion = switch (verifiers.get(c.needs())) {
                    case WorkflowEvidence.VIA_DATA_TRUTH -> {
                        if (report[0] == null) report[0] = replay.apply(graph);
                        yield WorkflowEvidence.dataTruth(c.needs(), graph, report[0]);
                    }
                    case WorkflowEvidence.VIA_APPLY_DOOR -> WorkflowEvidence.applyDoor(c.needs(), graph);
                    default -> Optional.empty();
                };
                conditions.add(conclusion.map(k -> raised(c, k)).orElse(c));
            }
            leads.add(lead.withConditions(conditions));
        }
        return recomputed.withLeads(leads);
    }

    private static Dossier.Condition raised(Dossier.Condition c, WorkflowEvidence.Conclusion k) {
        List<Dossier.Evidence> evidence = new ArrayList<>(c.evidence() == null ? List.of() : c.evidence());
        if (!evidence.contains(k.evidence())) evidence.add(k.evidence());
        return c.withCheck(k.check()).withEvidence(evidence);
    }
}
