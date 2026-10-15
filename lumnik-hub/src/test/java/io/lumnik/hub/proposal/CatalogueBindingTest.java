package io.lumnik.hub.proposal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogueBindingTest {

    private static final TrustedCatalogue TRUSTED = TrustedCatalogue.load();

    private static Dossier base() {
        return DossierReader.read(DossierReaderTest.fixture("valid/minimal.md"));
    }

    private static Set<String> bound(Dossier d) {
        return CatalogueBinding.bound(d, TRUSTED);
    }

    private static Dossier withLeads(Dossier d, List<Dossier.Lead> leads) {
        return new Dossier(d.catalogue(), d.created(), d.language(), d.words(), d.answers(), leads);
    }

    private static Dossier mapLead(Dossier d, UnaryOperator<Dossier.Lead> f) {
        return withLeads(d, d.leads().stream().map(f).toList());
    }

    private static Dossier.Lead withConditions(Dossier.Lead l, List<Dossier.Condition> cs) {
        return new Dossier.Lead(l.id(), l.rule(), l.source(), l.title(), l.says(), l.doesNotSay(), l.proposes(), cs);
    }

    private static Dossier mapConditions(Dossier d, UnaryOperator<Dossier.Condition> f) {
        return mapLead(d, l -> withConditions(l, l.conditions().stream().map(f).toList()));
    }

    private static Dossier.Condition cond(Dossier.Condition c, String needs, String verifier, String backedBy, String label) {
        return new Dossier.Condition(c.id(), needs, verifier, label, backedBy, c.check(), c.note(), c.evidence());
    }

    @Test
    void an_unmodified_dossier_binds_its_lead() {
        assertThat(bound(base())).containsExactly("L1");
    }

    @Test
    void presentation_texts_may_differ() {
        Dossier d = mapConditions(base(), c -> cond(c, c.needs(), c.verifier(), c.backedBy(), c.label() + " (reworded)"));
        d = mapLead(d, l -> new Dossier.Lead(l.id(), l.rule(), l.source(), "Autre titre", "Autre texte",
            List.of("Autre limite"), l.proposes(), l.conditions()));

        assertThat(bound(d)).containsExactly("L1");
    }

    @Test
    void a_catalogue_version_the_hub_does_not_hold_binds_nothing() {
        Dossier b = base();
        assertThat(bound(new Dossier("2026.9.0", b.created(), b.language(), b.words(), b.answers(), b.leads()))).isEmpty();
    }

    @Test
    void an_unknown_rule_or_rule_version_binds_nothing() {
        assertThat(bound(mapLead(base(), l -> new Dossier.Lead(l.id(), "stalled-documents@2", l.source(), l.title(), l.says(),
            l.doesNotSay(), l.proposes(), l.conditions())))).isEmpty();
        assertThat(bound(mapLead(base(), l -> new Dossier.Lead(l.id(), "invented@1", l.source(), l.title(), l.says(),
            l.doesNotSay(), l.proposes(), l.conditions())))).isEmpty();
    }

    @Test
    void the_same_rule_carried_by_two_leads_binds_neither() {
        Dossier b = base();
        Dossier.Lead second = new Dossier.Lead("L2", b.leads().get(0).rule(), "catalogue", "t", "s", List.of(),
            b.leads().get(0).proposes(), b.leads().get(0).conditions().stream()
                .map(c -> new Dossier.Condition(c.id() + "x", c.needs(), c.verifier(), c.label(), c.backedBy(), c.check(), c.note(), c.evidence())).toList());
        List<Dossier.Lead> two = new ArrayList<>(b.leads());
        two.add(second);

        assertThat(bound(withLeads(b, two))).isEmpty();
    }

    @Test
    void a_goal_answer_that_no_longer_satisfies_the_rules_when_binds_nothing() {
        Dossier b = base();
        List<Dossier.Answer> answers = b.answers().stream().map(a -> a.id().equals("goal")
            ? new Dossier.Answer("goal", "choice", a.asked(), "find-information", "Retrouver une information", "prospect") : a).toList();

        assertThat(bound(new Dossier(b.catalogue(), b.created(), b.language(), b.words(), answers, b.leads()))).isEmpty();
    }

    @Test
    void an_unanswered_goal_binds_nothing() {
        Dossier b = base();
        List<Dossier.Answer> answers = b.answers().stream().map(a -> a.id().equals("goal")
            ? new Dossier.Answer("goal", "choice", a.asked(), null, null, "prospect") : a).toList();

        assertThat(bound(new Dossier(b.catalogue(), b.created(), b.language(), b.words(), answers, b.leads()))).isEmpty();
    }

    @Test
    void proposes_altered_or_reordered_binds_nothing() {
        assertThat(bound(mapLead(base(), l -> new Dossier.Lead(l.id(), l.rule(), l.source(), l.title(), l.says(), l.doesNotSay(),
            new Dossier.Proposes("View", l.proposes().uses()), l.conditions())))).isEmpty();
        List<String> reordered = new ArrayList<>(base().leads().get(0).proposes().uses());
        Collections.reverse(reordered);
        assertThat(bound(mapLead(base(), l -> new Dossier.Lead(l.id(), l.rule(), l.source(), l.title(), l.says(), l.doesNotSay(),
            new Dossier.Proposes(l.proposes().kind(), reordered), l.conditions())))).isEmpty();
    }

    @Test
    void a_condition_deleted_binds_nothing() {
        assertThat(bound(mapLead(base(), l -> withConditions(l, l.conditions().subList(0, 4))))).isEmpty();
    }

    @Test
    void one_needs_repeated_under_two_ids_binds_nothing() {
        Dossier d = mapLead(base(), l -> {
            List<Dossier.Condition> cs = new ArrayList<>(l.conditions());
            Dossier.Condition c1 = cs.get(0);
            cs.set(1, new Dossier.Condition("C2", c1.needs(), c1.verifier(), c1.label(), c1.backedBy(), c1.check(), null, null));
            return withConditions(l, cs);
        });

        assertThat(bound(d)).isEmpty();
    }

    @Test
    void a_needs_altered_or_unknown_to_the_catalogue_binds_nothing() {
        Dossier d = mapConditions(base(), c -> c.id().equals("C1") ? cond(c, "status-column-renamed", c.verifier(), c.backedBy(), c.label()) : c);

        assertThat(bound(d)).isEmpty();
    }

    @Test
    void a_verifier_that_differs_from_the_catalogues_binds_nothing() {
        Dossier d = mapConditions(base(), c -> c.id().equals("C1") ? cond(c, c.needs(), "workflow-apply-door", c.backedBy(), c.label()) : c);

        assertThat(bound(d)).isEmpty();
    }

    @Test
    void a_backed_by_that_differs_from_the_catalogues_asked_by_binds_nothing() {
        Dossier d = mapConditions(base(), c -> c.id().equals("C1") ? cond(c, c.needs(), c.verifier(), "has-stable-id", c.label()) : c);

        assertThat(bound(d)).isEmpty();
    }

    @Test
    void an_invented_needs_with_a_real_verifier_binds_nothing() {
        Dossier d = mapConditions(base(), c -> c.id().equals("C2") ? cond(c, "invented-property", "workflow-data-truth", c.backedBy(), c.label()) : c);

        assertThat(bound(d)).isEmpty();
    }

    @Test
    void the_dossiers_own_ids_are_free() {
        Dossier d = mapLead(base(), l -> withConditions(l, l.conditions().stream().map(c ->
            new Dossier.Condition("X-" + c.id(), c.needs(), c.verifier(), c.label(), c.backedBy(), c.check(), c.note(), c.evidence())).toList()));

        assertThat(bound(d)).containsExactly("L1");
    }
}
