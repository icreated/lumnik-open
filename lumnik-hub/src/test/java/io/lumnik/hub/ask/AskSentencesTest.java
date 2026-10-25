package io.lumnik.hub.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every fixed sentence the ask door serves, pinned in both languages (#915). English is the default:
 * an absent, unknown or unsupported {@code lang} gets the sentence it always got.
 */
class AskSentencesTest {

    private static final List<String> MANY = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n");

    @Test
    void no_schema() {
        assertThat(AskSentences.noSchema(null)).isEqualTo(
            "I have no data declared for this scope yet, so I can't answer that. Ingest a source for this scope first.");
        assertThat(AskSentences.noSchema("fr")).isEqualTo(
            "Aucune donnée n'est encore déclarée pour ce périmètre, je ne peux donc pas répondre. "
                + "Ingérez d'abord une source pour ce périmètre.");
    }

    @Test
    void cannot_answer() {
        assertThat(AskSentences.cannotAnswer(null)).isEqualTo(
            "I can't answer that from the data available for this scope — the question doesn't map to the declared schema.");
        assertThat(AskSentences.cannotAnswer("fr")).isEqualTo(
            "Je ne peux pas répondre à partir des données disponibles pour ce périmètre — "
                + "la question ne correspond pas au schéma déclaré.");
    }

    @Test
    void the_two_signposts() {
        assertThat(AskSentences.lifecycleSignpost(null)).startsWith(" This scope does declare a lifecycle for its documents");
        assertThat(AskSentences.lifecycleSignpost("fr")).isEqualTo(
            " Ce périmètre déclare un cycle de vie pour ses documents : la réponse à une question sur ce qui bloque "
                + "l'un d'eux se trouve dans cette déclaration, non dans les données — et seules les données sont lues ici.");
        assertThat(AskSentences.conflictsSignpost(null)).startsWith(" Where this scope's entities record source conflicts");
        assertThat(AskSentences.conflictsSignpost("fr")).isEqualTo(
            " Lorsque les entités de ce périmètre enregistrent des conflits de sources ou des ambiguïtés de recherche, "
                + "ils sont consignés ligne par ligne, sur chaque fiche fusionnée, dans un registre de conflits "
                + "qui ne fait volontairement pas partie du schéma ci-dessus.");
    }

    @Test
    void could_not_translate() {
        assertThat(AskSentences.couldNotTranslate(null)).isEqualTo("I couldn't translate that into a safe query.");
        assertThat(AskSentences.couldNotTranslate("fr")).isEqualTo("Je n'ai pas pu traduire cela en requête sûre.");
    }

    @Test
    void follow_up_unresolved() {
        assertThat(AskSentences.followUpUnresolved(null)).isEqualTo(
            "I couldn't tie this question to the previous one. Ask it in full, with the value or the name it refers to.");
        assertThat(AskSentences.followUpUnresolved("fr")).isEqualTo(
            "Je n'ai pas pu rattacher cette question à la précédente. Posez-la en entier, avec la valeur ou le nom "
                + "auquel elle renvoie.");
    }

    @Test
    void zero_rows() {
        assertThat(AskSentences.zeroRows(null)).isEqualTo(
            "The query returned no rows. That can mean there are genuinely none, or that the query did not "
                + "capture your question — verify the SQL before reporting that there are none.");
        assertThat(AskSentences.zeroRows("fr")).isEqualTo(
            "La requête n'a renvoyé aucune ligne. Il peut n'y en avoir réellement aucune, ou la requête n'a pas "
                + "saisi votre question — vérifiez le SQL avant d'affirmer qu'il n'y en a pas.");
    }

    @Test
    void unbacked_answer() {
        assertThat(AskSentences.unbackedAnswer(null)).isEqualTo(
            "The phrased answer stated a figure these rows do not back, so it is withheld. "
                + "The rows and the SQL below are the answer.");
        assertThat(AskSentences.unbackedAnswer("fr")).isEqualTo(
            "La réponse rédigée citait un chiffre que ces lignes ne confirment pas ; elle est donc retenue. "
                + "Les lignes et le SQL ci-dessous font foi.");
    }

    @Test
    void query_refused() {
        assertThat(AskSentences.queryRefused(null, "boom")).isEqualTo(
            "I couldn't run that query — the database refused it: boom The SQL is below; "
                + "if it looks wrong for this scope, re-run discovery.");
        assertThat(AskSentences.queryRefused("fr", "boom")).isEqualTo(
            "Je n'ai pas pu exécuter cette requête — la base l'a refusée : boom Le SQL figure ci-dessous ; "
                + "s'il semble erroné pour ce périmètre, relancez la découverte.");
    }

    @Test
    void leak() {
        assertThat(AskSentences.leak(null, "x.y", List.of("connector.a", "connector.b"))).isEqualTo(
            "I won't run this: 'x.y' is not a table in this scope (declared: connector.a, connector.b). "
                + "The query may only read the tables this scope declares.");
        assertThat(AskSentences.leak("fr", "x.y", List.of("connector.a", "connector.b"))).isEqualTo(
            "Je n'exécuterai pas cette requête : « x.y » n'est pas une table de ce périmètre "
                + "(déclarées : connector.a, connector.b). "
                + "La requête ne peut pas lire de données hors du périmètre déclaré de ce métier.");
    }

    @Test
    void a_long_list_is_cut_after_twelve_in_each_language() {
        assertThat(AskSentences.leak(null, "x.y", MANY)).contains("a, b, c, d, e, f, g, h, i, j, k, l, … (+2 more)");
        assertThat(AskSentences.leak("fr", "x.y", MANY)).contains("a, b, c, d, e, f, g, h, i, j, k, l, … (+2 de plus)");
    }

    @Test
    void undocumented_column() {
        assertThat(AskSentences.undocumentedColumn(null, "foo", List.of("id", "status"))).isEqualTo(
            "I won't run this: 'foo' is not a declared column of this scope (known: id, status). "
                + "If it should exist, add it to the entity manifest.");
        assertThat(AskSentences.undocumentedColumn("fr", "foo", List.of("id", "status"))).isEqualTo(
            "Je n'exécuterai pas cette requête : « foo » n'est pas une colonne déclarée de ce périmètre "
                + "(connues : id, status). Si elle devrait exister, ajoutez-la au manifeste de l'entité.");
    }

    @Test
    void out_of_domain_value() {
        assertThat(AskSentences.outOfDomainValue(null, "status", "it's", List.of("OPEN", "DONE"))).isEqualTo(
            "I won't run this: 'it''s' is not a declared value of status (known: OPEN, DONE). "
                + "If it should exist, add it to the entity manifest.");
        assertThat(AskSentences.outOfDomainValue("fr", "status", "it's", List.of("OPEN", "DONE"))).isEqualTo(
            "Je n'exécuterai pas cette requête : « it''s » n'est pas une valeur déclarée de status "
                + "(connues : OPEN, DONE). Si elle devrait exister, ajoutez-la au manifeste de l'entité.");
    }

    @Test
    void ambiguous_filter() {
        List<String> ids = List.of("C-1", "C-2", "C-2");
        assertThat(AskSentences.ambiguousFilter(null, "customer id", ids, false)).isEqualTo(
            "That filter matches 3 rows, so the question has no single answer. Matching customer id: C-1, C-2. "
                + "Ask again naming one of them — for example by filtering on customer id instead of the name.");
        assertThat(AskSentences.ambiguousFilter("fr", "customer id", ids, true)).isEqualTo(
            "Ce filtre correspond à au moins 3 lignes, la question n'a donc pas de réponse unique. "
                + "Correspondances pour customer id : C-1, C-2. "
                + "Reformulez en en désignant une — par exemple en filtrant sur customer id plutôt que sur le nom.");
    }

    @Test
    void an_absent_unknown_or_regional_lang() {
        assertThat(AskSentences.zeroRows("de")).isEqualTo(AskSentences.zeroRows(null));
        assertThat(AskSentences.zeroRows("")).isEqualTo(AskSentences.zeroRows(null));
        assertThat(AskSentences.zeroRows("fr-CA")).isEqualTo(AskSentences.zeroRows("fr"));
        assertThat(AskSentences.zeroRows("FR")).isEqualTo(AskSentences.zeroRows("fr"));
    }

    @Test
    void the_phrasing_was_written_from_a_sample_of_the_rows() {
        assertThat(AskSentences.phrasingSample(null, 48, 312, false)).isEqualTo(
            "The answer summarises the first 48 of the 312 rows returned.");
        assertThat(AskSentences.phrasingSample("fr", 48, 312, false)).isEqualTo(
            "La réponse résume les 48 premières lignes parmi les 312 lignes renvoyées.");
    }

    /** A result cut at its limit has a floor, not a total: the opening sentence never says "of the 500 rows". */
    @Test
    void a_sample_of_a_result_cut_at_its_limit_is_said_as_at_least() {
        assertThat(AskSentences.phrasingSample(null, 48, 500, true)).isEqualTo(
            "The answer summarises the first 48 of at least 500 rows.");
        assertThat(AskSentences.phrasingSample("fr", 48, 500, true)).isEqualTo(
            "La réponse résume les 48 premières lignes parmi au moins 500 lignes.");
    }

    @Test
    void the_query_stopped_at_its_limit() {
        assertThat(AskSentences.queryCapped(null, 500)).isEqualTo(
            "The SQL query stops at 500 rows: there may be more.");
        assertThat(AskSentences.queryCapped("fr", 100)).isEqualTo(
            "La requête SQL s'arrête à 100 lignes : il peut y en avoir davantage.");
    }

    @Test
    void the_phrasing_could_not_be_written() {
        assertThat(AskSentences.phrasingFailed(null)).isEqualTo(
            "The model could not write the answer. The SQL ran and its rows were returned: "
                + "the rows and the SQL below are the result.");
        assertThat(AskSentences.phrasingFailed("fr")).isEqualTo(
            "Le modèle n'a pas pu rédiger la réponse. La requête SQL a bien été exécutée et ses lignes sont renvoyées : "
                + "les lignes et le SQL ci-dessous font foi.");
    }
}
