package io.lumnik.hub.proposal;

import io.lumnik.hub.workflow.WorkflowDataTruth.Report;
import io.lumnik.hub.workflow.WorkflowGraph;
import io.lumnik.hub.workflow.WorkflowManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DossierCheckerTest {

    private static final TrustedCatalogue TRUSTED = TrustedCatalogue.load();
    private static final WorkflowGraph GRAPH = new WorkflowGraph("wf", "demo", "connector.t_commandes", "statut",
        "declared", "id", new WorkflowManifest.DormancyDef("7d"), List.of(), "A", List.of());
    private static final Report READ = new Report(List.of(), List.of(), 0L, false, false, null, false, null);

    private static Dossier minimal() {
        return DossierReader.read(DossierReaderTest.fixture("valid/minimal.md"));
    }

    private static Dossier.Condition condition(Dossier d, String id) {
        return d.leads().get(0).conditions().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static String check(Dossier d, String id) {
        return condition(d, id).check();
    }

    private static List<Dossier.Evidence> evidence(Dossier d, String id) {
        return condition(d, id).evidence();
    }

    @Test
    void with_no_designated_workflow_the_dossier_is_only_recomputed_and_nothing_is_replayed() {
        AtomicInteger replays = new AtomicInteger();

        Dossier out = DossierChecker.check(minimal(), TRUSTED, Optional.empty(), g -> { replays.incrementAndGet(); return READ; });

        assertThat(replays).hasValue(0);
        assertThat(out).isEqualTo(Derivation.recompute(minimal()));
    }

    @Test
    void a_designated_workflow_raises_the_verifiable_conditions_and_only_those() {
        Dossier out = DossierChecker.check(minimal(), TRUSTED, Optional.of(GRAPH), g -> READ);

        assertThat(check(out, "C1")).isEqualTo("confirmed");   // answer yes: unverified -> confirmed
        assertThat(check(out, "C3")).isEqualTo("confirmed");   // answer unknown: missing -> confirmed
        assertThat(check(out, "C5")).isEqualTo("confirmed");   // the apply door
        assertThat(check(out, "C2")).isEqualTo("unverified");  // verifier none: untouched
        assertThat(check(out, "C4")).isEqualTo("missing");
        assertThat(evidence(out, "C2")).isNull();
        assertThat(evidence(out, "C4")).isNull();
        assertThat(evidence(out, "C1")).hasSize(1);
    }

    @Test
    void the_probe_is_replayed_once_for_both_data_truth_conditions() {
        AtomicInteger replays = new AtomicInteger();

        DossierChecker.check(minimal(), TRUSTED, Optional.of(GRAPH), g -> { replays.incrementAndGet(); return READ; });

        assertThat(replays).hasValue(1);
    }

    @Test
    void an_unrunnable_probe_leaves_the_recomputed_checks_but_the_apply_door_still_speaks() {
        Report unavailable = new Report(List.of(), List.of(), 0L, false, false, null, true, "SQLSTATE 57014: timeout");

        Dossier out = DossierChecker.check(minimal(), TRUSTED, Optional.of(GRAPH), g -> unavailable);

        assertThat(check(out, "C1")).isEqualTo("unverified");
        assertThat(check(out, "C3")).isEqualTo("missing");
        assertThat(check(out, "C5")).isEqualTo("confirmed");
    }

    @Test
    void checking_twice_gives_the_same_dossier_and_never_doubles_evidence() {
        Dossier once = DossierChecker.check(minimal(), TRUSTED, Optional.of(GRAPH), g -> READ);
        Dossier twice = DossierChecker.check(DossierReader.read(DossierWriter.write(once)), TRUSTED, Optional.of(GRAPH), g -> READ);

        assertThat(twice).isEqualTo(once);
        assertThat(evidence(twice, "C1")).hasSize(1);
    }

    @Test
    void history_that_the_verifier_does_not_repeat_stays_beside_the_new_evidence() {
        Dossier carried = DossierReader.read(DossierReaderTest.fixture("valid-recheck/confirmed-with-evidence.md"));
        Dossier.Evidence old = evidence(carried, "C1").get(0);

        Dossier out = DossierChecker.check(carried, TRUSTED, Optional.of(GRAPH), g -> READ);

        assertThat(evidence(out, "C1")).hasSize(2).first().isEqualTo(old);
    }

    @Test
    void a_lead_the_catalogue_does_not_vouch_for_gets_no_evidence_at_all() {
        Dossier d = minimal();
        Dossier.Lead l = d.leads().get(0);
        Dossier tampered = new Dossier(d.catalogue(), d.created(), d.language(), d.words(), d.answers(), List.of(
            new Dossier.Lead(l.id(), l.rule(), l.source(), l.title(), l.says(), l.doesNotSay(), l.proposes(),
                l.conditions().subList(0, 4))));
        AtomicInteger replays = new AtomicInteger();

        Dossier out = DossierChecker.check(tampered, TRUSTED, Optional.of(GRAPH), g -> { replays.incrementAndGet(); return READ; });

        assertThat(replays).hasValue(0);
        assertThat(out.carriesEvidence()).isFalse();
        assertThat(out).isEqualTo(Derivation.recompute(tampered));
    }

    @Test
    void the_dispatch_follows_the_catalogue_not_the_dossiers_verifier() {
        Dossier d = minimal();
        Dossier.Lead l = d.leads().get(0);
        // C1 relabelled as an apply-door condition: the copy no longer matches, the lead abstains
        List<Dossier.Condition> cs = l.conditions().stream().map(c -> c.id().equals("C1")
            ? new Dossier.Condition("C1", c.needs(), "workflow-apply-door", c.label(), c.backedBy(), c.check(), c.note(), null) : c).toList();
        Dossier tampered = new Dossier(d.catalogue(), d.created(), d.language(), d.words(), d.answers(), List.of(
            new Dossier.Lead(l.id(), l.rule(), l.source(), l.title(), l.says(), l.doesNotSay(), l.proposes(), cs)));

        Dossier out = DossierChecker.check(tampered, TRUSTED, Optional.of(GRAPH), g -> READ);

        assertThat(out.carriesEvidence()).isFalse();
    }
}
