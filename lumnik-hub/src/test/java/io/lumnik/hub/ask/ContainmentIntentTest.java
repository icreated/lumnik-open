package io.lumnik.hub.ask;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The containment rule is read only by a question that says a TEXT column contains a text (#1060). */
class ContainmentIntentTest {

    @Test void a_text_column_named_in_a_containment_construction_is_one() {
        for (String q : new String[] {
                // the text column is the subject of a containment verb
                "Donne-moi les références dont la description contient « Badu »",
                "les articles dont le nom contient Pompe", "dont la description de l'article contient Badu",
                "dont le commentaire inclut Badu", "articles dont la description mentionne Badu",
                "List the references whose description contains Badu", "products whose description includes Badu",
                "which articles have a name containing Badu", "whose title mentions Badu",
                "Quels articles ont une description qui ne contient pas Badu ?",
                // or Badu is said to sit in a named text column
                "Quels articles ont Badu dans leur description ?", "Badu dans la description de l'article",
                "articles with Badu in the description", "articles with Badu in their name",
                "Which articles have Badu in the product description?", "Quels articles ont Pompe dans leur libellé ?",
                "Combien d'articles n'ont pas Badu dans leur description ?"})
            assertThat(ContainmentIntent.in(q)).as(q).isTrue();
    }

    @Test void a_named_value_or_a_bare_with_is_not_containment() {
        for (String q : new String[] {
                "Combien d'articles appartiennent à la catégorie Pompes ?", "Combien de commandes ont le statut Validé ?",
                "How many orders have the status Validé?", "articles avec le code ABC", "orders with status open",
                "Quels articles sont dans la catégorie Pompes à chaleur ?", "combien d'articles dans le stock ?",
                "status SO-000004", "SO-000004 statut ?", "customers with a city in France",
                "description de l'article 2195.300.037"})
            assertThat(ContainmentIntent.in(q)).as(q).isFalse();
    }

    /** The same verbs, said of something that is not a text column: a category holding articles, a container, a mention. */
    @Test void a_structural_contain_or_a_noun_that_looks_like_the_verb_is_not_containment() {
        for (String q : new String[] {
                "Combien d'articles contient la catégorie Pompes ?", "La catégorie Pompes contient combien d'articles ?",
                "Combien d'articles la catégorie Pompes contient-elle ?",
                "How many articles are contained in the category Pompes?", "How many products does the category Pompes contain?",
                "How many containers are at the port Le Havre?", "Which vessel carries containers for CMA CGM?",
                "articles with the mention Bio", "la mention Très bien", "Quels articles mentionnent Badu ?",
                "Combien d'articles contiennent une description ?", "in the name of John Smith"})
            assertThat(ContainmentIntent.in(q)).as(q).isFalse();
    }

    @Test void accents_case_and_typographic_apostrophes_do_not_matter() {
        assertThat(ContainmentIntent.in("Dont le LIBELLÉ CONTIENT Pompe")).isTrue();
        assertThat(ContainmentIntent.in("dont la desIgnation contient x")).isTrue();
        assertThat(ContainmentIntent.in("Pompe dans l’intitulé")).isTrue();
    }

    @Test void a_missing_question_is_not_one() {
        assertThat(ContainmentIntent.in(null)).isFalse();
        assertThat(ContainmentIntent.in("  ")).isFalse();
    }

    // --- the one text searched (#1099) ------------------------------------------------------------------------

    @Test void the_one_text_a_question_searches_comes_back_folded() {
        assertThat(ContainmentIntent.searched("Donne-moi les références dont la description contient Badu.")).contains("badu");
        assertThat(ContainmentIntent.searched("dont la description contient \"Badu Resort\" ?")).contains("badu resort");
        assertThat(ContainmentIntent.searched("Quels articles ont badu dans leur description ?")).contains("badu");
        assertThat(ContainmentIntent.searched("Which articles have Badu in the product description?")).contains("badu");
        assertThat(ContainmentIntent.searched("List the references whose description contains SO-000004")).contains("so-000004");
        assertThat(ContainmentIntent.searched("dont le LIBELLÉ contient Été, triés par prix")).contains("ete");
    }

    @Test void no_text_or_more_than_one_is_none() {
        for (String q : new String[] {
                "dont la description contient badu ou pentair", "dont la description ne contient pas badu",
                "dont la description contient badu dans la catégorie Pompes", "Combien d'articles de la catégorie Pompes ?",
                "dont la description contient badu et dont le nom contient pentair", "Quels articles mentionnent Badu ?",
                "Combien d'articles contient la catégorie Pompes ?"})
            assertThat(ContainmentIntent.searched(q)).as(q).isEmpty();
        assertThat(ContainmentIntent.searched(null)).isEmpty();
    }
}
