package io.lumnik.hub.ask;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A signpost is offered only when its door can answer what was asked (#1047). */
class DoorRelevanceTest {

    @Test void a_question_about_disagreeing_sources_is_about_the_conflicts() {
        for (String q : new String[] {
                "où l'ERP et le CRM se contredisent-ils ?", "quels clients ont un conflit sur le nom ?",
                "Quelles divergences entre les sources ?", "Il y a un désaccord sur la ville de P-1002 ?",
                "which customers have conflicting values?", "where do the sources disagree",
                "any discrepancy on the city?", "show the disputed rows", "des valeurs incohérentes ?"})
            assertThat(DoorRelevance.conflicts(q)).as(q).isTrue();
    }

    @Test void a_question_about_how_a_document_moves_is_about_the_lifecycle() {
        for (String q : new String[] {
                "pourquoi je ne peux pas clôturer la commande ?", "why can't this document move on?",
                "quel est le cycle de vie d'une commande", "à quelle étape est la commande SO-1",
                "which workflow applies to orders", "lifecycle of an order", "quelles commandes sont dormantes",
                "which orders are stuck", "quelle phase pour ce dossier", "pourquoi la commande est bloquée ?"})
            assertThat(DoorRelevance.lifecycle(q)).as(q).isTrue();
    }

    @Test void a_plain_lookup_or_count_is_neither() {
        for (String q : new String[] {
                "Donne-moi les références dont la description contient « Badu »",
                "Quels articles ont Badu dans leur description ?", "list the products whose description contains Badu",
                "wipe the customer name", "combien de clients à Lyon ?",
                // a column that happens to be called status / state is data, not a lifecycle question
                "combien de commandes par statut ?", "what is the status of SO-000004", "SO-000004 statut ?",
                "customers by state", "combien de produits différents ?", "close the browser",
                "how many distinct references differ in price",
                // an inability to find something is not a lifecycle question
                "why can't I find the customer whose description contains Badu?", "pourquoi je ne peux pas trouver cet article ?"}) {
            assertThat(DoorRelevance.conflicts(q)).as(q).isFalse();
            assertThat(DoorRelevance.lifecycle(q)).as(q).isFalse();
        }
    }

    @Test void a_word_counts_where_it_starts_not_inside_another_word() {
        assertThat(DoorRelevance.conflicts("combien de preconflits")).isFalse();
        assertThat(DoorRelevance.lifecycle("the backstage pass")).isFalse();
        assertThat(DoorRelevance.lifecycle("why can\u2019t this document move on")).isTrue(); // a typographic apostrophe
    }

    @Test void accents_and_case_do_not_matter() {
        assertThat(DoorRelevance.conflicts("DÉSACCORD sur le nom")).isTrue();
        assertThat(DoorRelevance.conflicts("desaccord sur le nom")).isTrue();
        assertThat(DoorRelevance.lifecycle("ÉTAPE de la commande")).isTrue();
    }

    @Test void a_missing_question_is_neither() {
        assertThat(DoorRelevance.conflicts(null)).isFalse();
        assertThat(DoorRelevance.lifecycle("")).isFalse();
    }
}
