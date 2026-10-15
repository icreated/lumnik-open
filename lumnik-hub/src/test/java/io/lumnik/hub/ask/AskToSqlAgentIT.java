package io.lumnik.hub.ask;

import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class AskToSqlAgentIT {

    @Inject AskToSqlAgent agent;
    @Inject SchemaCardStore store;
    @InjectMock @Analytic ChatPort chatPort;

    @BeforeEach
    void seed() {
        agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.t_demo_order " +
            "(documentno text, grandtotal numeric)");
        agent.seedDdl("TRUNCATE connector.t_demo_order");
        agent.seedDdl("INSERT INTO connector.t_demo_order VALUES ('SO-1', 100), ('SO-2', 250)");
        store.save(7L, "demo", new SchemaCard("demo", List.of(
            new SchemaCard.Table("connector.t_demo_order", "Demo Order", List.of(
                new SchemaCard.Column("documentno", "text", "Document No"),
                new SchemaCard.Column("grandtotal", "numeric", "Grand Total"))))));
    }

    // --- a result larger than the model reads (#1067) ------------------------------------------------

    private static final int WIDE_ROWS = 120;

    private void seedCatalogue(String table, String scope, int rows, String descriptionExpr) {
        agent.seedDdl("CREATE TABLE IF NOT EXISTS connector." + table + " (reference text, description text)");
        agent.seedDdl("TRUNCATE connector." + table);
        agent.seedDdl("INSERT INTO connector." + table + " SELECT 'REF-' || lpad(g::text, 4, '0'), " + descriptionExpr
            + " FROM generate_series(1, " + rows + ") g");
        store.save(7L, scope, new SchemaCard(scope, List.of(
            new SchemaCard.Table("connector." + table, "Catalogue", List.of(
                new SchemaCard.Column("reference", "text", "Référence"),
                new SchemaCard.Column("description", "text", "Description"))))));
    }

    private void seedWideCatalogue() {
        seedCatalogue("t_wide_catalogue", "wide", WIDE_ROWS, "'Pompe Resort ' || g || ' ' || repeat('x', 150)");
    }

    private static final String WIDE_SQL = "SELECT reference, description FROM connector.t_wide_catalogue ORDER BY reference";

    @Test
    void aLongResultIsPhrasedFromItsFirstRowsAndTheAnswerLeadsWithWhatItWasWrittenFrom() {
        seedWideCatalogue();
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(WIDE_SQL).thenReturn("Voici le résumé des références.");

        AskResult r = agent.ask(7L, "wide", "Donne-moi les références sans Badu", "fr");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatPort, Mockito.times(2)).chat(prompt.capture());
        String phrasing = prompt.getAllValues().get(1);
        assertThat(phrasing).contains("The query returned " + WIDE_ROWS + " rows in all; you are shown only some of the first ones.")
            .contains("State that count (" + WIDE_ROWS + ").")
            .doesNotContain("key totals")
            .contains("REF-0001").doesNotContain("REF-0120");
        // the result is whole and consistent; the answer says FIRST what it was written from, then the model's text
        assertThat(r.rows()).hasSize(WIDE_ROWS);
        assertThat(r.rowCount()).isEqualTo(WIDE_ROWS);
        assertThat(r.sql()).contains("t_wide_catalogue");
        assertThat(r.answer()).matches("La réponse résume les \\d+ premières lignes parmi les " + WIDE_ROWS
            + " lignes renvoyées\\. Voici le résumé des références\\.");
        assertThat(r.abstained()).isFalse();
    }

    @Test
    void theSampleSentenceIsAddedAfterTheNumbersCheckSoItsOwnFiguresAreNeverWithheld() {
        seedWideCatalogue();
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(WIDE_SQL).thenReturn("Voici le résumé.");

        AskResult r = agent.ask(7L, "wide", "Donne-moi les références", "fr");

        // K and 120 are not cells of any row — they would be 'unbacked' if the check ran after them
        assertThat(r.answer()).startsWith("La réponse résume les").doesNotContain(AskSentences.unbackedAnswer("fr"));
        assertThat(r.abstained()).isFalse();
    }

    @Test
    void aFigureTheRowsDoNotBackIsStillWithheldOnASampleAndNoSummaryIsClaimed() {
        seedWideCatalogue();
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(WIDE_SQL).thenReturn("Il y a 99999 références.");

        AskResult r = agent.ask(7L, "wide", "Donne-moi les références", "fr");

        // a withheld answer is not a summary: no sentence naming one
        assertThat(r.answer()).isEqualTo(AskSentences.unbackedAnswer("fr"));
        assertThat(r.abstained()).isTrue();
        assertThat(r.rows()).hasSize(WIDE_ROWS);
    }

    @Test
    void aResultThatHitsTheQueryLimitSaysSoWhetherOrNotTheModelReadItAll() {
        // 600 one-character rows: the guard stops at 500; 500 narrow rows fit the budget, so nothing is sampled
        agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.t_narrow (c text)");
        agent.seedDdl("TRUNCATE connector.t_narrow");
        agent.seedDdl("INSERT INTO connector.t_narrow SELECT 'a' FROM generate_series(1, 600)");
        store.save(7L, "narrow", new SchemaCard("narrow", List.of(
            new SchemaCard.Table("connector.t_narrow", "Narrow", List.of(new SchemaCard.Column("c", "text", "C"))))));
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn("SELECT c FROM connector.t_narrow")
            .thenReturn("Voici les valeurs.");

        AskResult r = agent.ask(7L, "narrow", "Donne-moi les valeurs", "fr");

        assertThat(r.rowCount()).isEqualTo(500);
        assertThat(r.answer()).isEqualTo("Voici les valeurs. La requête SQL s'arrête à 500 lignes : il peut y en avoir davantage.");
    }

    @Test
    void aLimitTheModelWroteIsReportedAsThatLimitNotAsFiveHundred() {
        seedWideCatalogue();
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(WIDE_SQL + " LIMIT 100").thenReturn("Voici le résumé.");

        AskResult r = agent.ask(7L, "wide", "Montre-moi 100 articles", "fr");

        assertThat(r.rowCount()).isEqualTo(100);
        assertThat(r.answer()).startsWith("La réponse résume les").contains("parmi au moins 100 lignes.")
            .endsWith("La requête SQL s'arrête à 100 lignes : il peut y en avoir davantage.")
            .doesNotContain("500");
    }

    /** An aggregate with LIMIT 1 is one row by construction, not a listing cut at its limit (#1067 review). */
    @Test
    void aSingleRowAggregateWithALimitOfOneIsNotAListingCutAtItsLimit() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT COUNT(*) FROM connector.t_demo_order LIMIT 1")
            .thenReturn("There are 2 demo orders.");

        AskResult r = agent.ask(7L, "demo", "how many demo orders are there?");

        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.answer()).isEqualTo("There are 2 demo orders.");
    }

    /** A sampled listing that reached its limit is a floor, not "the total": the model is not asked to state 500 as the count. */
    @Test
    void aSampledResultThatReachedTheQueryLimitIsPutToTheModelAsAFloor() {
        seedCatalogue("t_wide600", "wide600", 600, "repeat('w', 200)");
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn("SELECT reference, description FROM connector.t_wide600")
            .thenReturn("Many long values.");

        AskResult r = agent.ask(7L, "wide600", "List the values", "en");

        assertThat(r.rowCount()).isEqualTo(500);
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatPort, Mockito.times(2)).chat(prompt.capture());
        assertThat(prompt.getAllValues().get(1))
            .contains("at least 500").doesNotContain("500 rows in all").doesNotContain("State that count");
        assertThat(r.answer()).startsWith("The answer summarises the first ").contains(" of at least 500 rows.")
            .endsWith("The SQL query stops at 500 rows: there may be more.");
    }

    @Test
    void aPhrasingFailureKeepsTheRowsAndTheSqlAndTheModelIsNotAskedAgain() {
        seedWideCatalogue();
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(WIDE_SQL)
            .thenThrow(new RuntimeException("Read timed out"));

        AskResult r = agent.ask(7L, "wide", "Donne-moi les références", "fr");

        assertThat(r.answer()).isEqualTo(AskSentences.phrasingFailed("fr"));
        assertThat(r.abstained()).isTrue();
        assertThat(r.rows()).hasSize(WIDE_ROWS);
        assertThat(r.rowCount()).isEqualTo(WIDE_ROWS);
        assertThat(r.sql()).contains("t_wide_catalogue");
        // one SQL generation, ONE phrasing attempt — a deterministic timeout is never replayed by the agent
        Mockito.verify(chatPort, Mockito.times(2)).chat(Mockito.anyString());
    }

    @Test
    void aSmallResultIsStillPhrasedWholeWithNoLimitSentence() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT documentno, grandtotal FROM connector.t_demo_order")
            .thenReturn("There are 2 demo orders totalling 350.");

        AskResult r = agent.ask(7L, "demo", "list all demo orders");

        assertThat(r.answer()).isEqualTo("There are 2 demo orders totalling 350.");
    }

    @Test
    void generatesGuardedSqlExecutesAndPhrasesAnswer() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT documentno, grandtotal FROM connector.t_demo_order")
            .thenReturn("There are 2 demo orders totalling 350.");

        AskResult r = agent.ask(7L, "demo", "list all demo orders");

        assertThat(r.sql()).contains("LIMIT");
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.rows().get(0)).containsKey("documentno");
        assertThat(r.answer()).contains("demo orders");
    }

    /**
     * #873: the phrasing prompt was English and set no language, so a French question came back in
     * English. The model is not deterministic, so this pins what we SEND — the instruction to answer
     * in the question's language — not what comes back.
     */
    @Test
    void phrasingPromptTellsTheModelToAnswerInTheLanguageOfTheQuestion() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT documentno, grandtotal FROM connector.t_demo_order")
            .thenReturn("Il y a 2 commandes.");

        agent.ask(7L, "demo", "Combien de commandes y a-t-il ?");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatPort, Mockito.times(2)).chat(prompt.capture());
        String phrasing = prompt.getAllValues().get(1);
        assertThat(phrasing)
            .contains("Question: <question>\"Combien de commandes y a-t-il ?\"</question>")
            .contains("Write the answer in the same language as the question, whatever the language of this prompt.");
    }

    @Test
    void theLanguageTheSurfaceNamesReachesThePhrasingPrompt() {
        // #879: a terse question carries no language; the calling surface names it.
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT documentno, grandtotal FROM connector.t_demo_order")
            .thenReturn("Il y a 2 commandes.");

        agent.ask(7L, "demo", "CA 2026 GOLD", "fr");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatPort, Mockito.times(2)).chat(prompt.capture());
        assertThat(prompt.getAllValues().get(1))
            .contains("Write the answer in French, whatever the language of the question or of this prompt.");
    }

    @Test
    void answeredAskCarriesTheScopesLastCompletedRunAsDataAsOf() {
        // P5: the freshness dial. The signal is the run history (the doc calls it the
        // reliable one), never a MAX over million-row mirror tables.
        agent.seedDdl("DELETE FROM connector.connector_run WHERE tenant_id = 7");
        agent.seedDdl("DELETE FROM connector.connector WHERE tenant_id = 7");
        agent.seedDdl("INSERT INTO connector.connector_mapping (id, tenant_id, name, target_entity, spec_format, spec_content) "
            + "VALUES ('00000000-0000-0000-0000-00000000a5f1', 7, 'm_asof', 'public.x', 'yaml', 'x') "
            + "ON CONFLICT DO NOTHING");
        agent.seedDdl("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, mapping_id, enabled, tags, sync_mode, skip_policy) "
            + "VALUES ('00000000-0000-0000-0000-00000000a5f2', 7, 'src-asof', 'csv-file', '{}'::jsonb, "
            + "'00000000-0000-0000-0000-00000000a5f1', true, ARRAY['demo'], 'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":1}'::jsonb)");
        agent.seedDdl("INSERT INTO connector.connector_run (tenant_id, connector_id, status, sync_mode, triggered_by, started_at, ended_at) "
            + "VALUES (7, '00000000-0000-0000-0000-00000000a5f2', 'Completed', 'Incremental', 'test', "
            + "'2026-08-20T06:00:00Z', '2026-08-20T06:05:00Z')");
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT documentno FROM connector.t_demo_order")
            .thenReturn("Two orders.");

        AskResult r = agent.ask(7L, "demo", "list them");

        assertThat(r.dataAsOf()).as("an answered ask names how fresh its ground is")
            .isNotNull().startsWith("2026-08-20T06:05");
    }

    @Test
    void refusalCarriesNoDataAsOf() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("UPDATE connector.t_demo_order SET grandtotal = 0");
        AskResult r = agent.ask(7L, "demo", "wipe");
        assertThat(r.dataAsOf()).isNull();
    }

    @Test
    void an_ambiguous_filter_discloses_the_ids_so_the_next_question_can_name_one() {
        seedDuplicatePartners();
        // The SQL a live qwen2.5-coder emits for "how many orders has bpartner X" — measured, not
        // invented: at temperature 0 it chose this scalar-subquery shape 10 times out of 10.
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(
            "SELECT count(*) FROM connector.t_dup_order WHERE c_bpartner_id = "
            + "(SELECT c_bpartner_id FROM connector.t_dup_partner WHERE name = 'ACME')");

        AskResult r = agent.ask(7L, "dup", "how many orders has partner ACME");

        assertThat(r.answer())
            .as("the ids are the point: they are what makes the next question answerable")
            .contains("1000123").contains("1000456")
            .contains("Ask again");
        assertThat(r.rowCount()).as("nothing was answered — an ambiguity is not a result").isZero();
    }

    @Test
    void a_failure_that_is_not_an_ambiguity_reports_the_database_rather_than_guessing() {
        seedDuplicatePartners();
        // 'ghost' is declared in the card and exists in no table: the guards pass, Postgres refuses.
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT ghost FROM connector.t_dup_order");

        AskResult r = agent.ask(7L, "dup", "list the ghosts");

        assertThat(r.answer())
            .as("this message used to name an unqualified table as the likely cause — on a "
                + "qualified query it sent its reader to the wrong place")
            .doesNotContain("unqualified")
            .contains("ghost");
    }

    /** Two partner rows under ONE name — the arrangement that makes a scalar subquery fail. */
    private void seedDuplicatePartners() {
        agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.t_dup_partner (c_bpartner_id bigint, name text)");
        agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.t_dup_order (c_bpartner_id bigint)");
        agent.seedDdl("TRUNCATE connector.t_dup_partner");
        agent.seedDdl("TRUNCATE connector.t_dup_order");
        agent.seedDdl("INSERT INTO connector.t_dup_partner VALUES (1000123, 'ACME'), (1000456, 'ACME')");
        agent.seedDdl("INSERT INTO connector.t_dup_order VALUES (1000123), (1000123)");
        store.save(7L, "dup", new SchemaCard("dup", List.of(
            new SchemaCard.Table("connector.t_dup_order", "Order", List.of(
                new SchemaCard.Column("c_bpartner_id", "bigint", "Partner"),
                new SchemaCard.Column("ghost", "text", "Ghost"))),
            new SchemaCard.Table("connector.t_dup_partner", "Partner", List.of(
                new SchemaCard.Column("c_bpartner_id", "bigint", "Partner"),
                new SchemaCard.Column("name", "text", "Name"))))));
    }

    /**
     * The whole chain over names the source chose, which is the part no unit test reaches: the card is
     * SAVED and reloaded (a JSON round trip through {@code SchemaCardStore}) before the prompt is built,
     * the guards see a quoted name and must pass it through untouched, and Postgres keys the answer's
     * row by the source's own name. Why those three names, and what a bare rendering did to them, is on
     * {@code SchemaCard}'s renderer and in {@code SchemaCardToPromptTest}.
     */
    @Test
    void aColumnNameTheSourceChoseIsAskableEndToEnd() {
        agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.t_kafka_orders "
            + "(documentno text, \"c_bpartner.id\" text, \"Partner Name\" text)");
        agent.seedDdl("TRUNCATE connector.t_kafka_orders");
        agent.seedDdl("INSERT INTO connector.t_kafka_orders VALUES ('SO-9', 'BP-7', 'Grand Compte')");
        store.save(7L, "kafka", new SchemaCard("kafka", List.of(
            new SchemaCard.Table("connector.t_kafka_orders", "Kafka Orders", List.of(
                new SchemaCard.Column("documentno", "text", "Document No"),
                new SchemaCard.Column("c_bpartner.id", "text", "c_bpartner.id"),
                new SchemaCard.Column("Partner Name", "text", "Partner Name"))))));
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT \"Partner Name\", \"c_bpartner.id\" FROM \"connector\".\"t_kafka_orders\"")
            .thenReturn("One order, for Grand Compte.");

        AskResult r = agent.ask(7L, "kafka", "list the partner name and its id");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatPort, Mockito.atLeastOnce()).chat(prompt.capture());
        assertThat(prompt.getAllValues().get(0))
            .as("the STORED card, reloaded, must still render a name the model can copy into SQL")
            .contains("\"c_bpartner.id\" text")
            .contains("\"Partner Name\" text")
            .contains("TABLE \"connector\".\"t_kafka_orders\"");
        assertThat(r.rowCount()).as("the guards must pass a quoted name through untouched").isEqualTo(1);
        assertThat(r.rows().get(0))
            .as("the row keys are the source's own names, not a sanitized echo")
            .containsEntry("c_bpartner.id", "BP-7")
            .containsEntry("Partner Name", "Grand Compte");
    }

    @Test
    void rejectedSqlDegradesCleanlyWithoutRunning() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("UPDATE connector.t_demo_order SET grandtotal = 0");

        AskResult r = agent.ask(7L, "demo", "wipe the totals");

        assertThat(r.rowCount()).isEqualTo(0);
        assertThat(r.answer()).containsIgnoringCase("couldn't");
        assertThat(r.abstained()).as("a SqlGuard rejection is a refusal").isTrue();
    }

    @Test
    void zeroRowsReturnAnHonestCaveatWithoutPhrasing() {
        // The model emits a syntactically fine SELECT that matches nothing.
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT documentno FROM connector.t_demo_order WHERE documentno = 'NOPE'");

        AskResult r = agent.ask(7L, "demo", "list orders for NOPE");

        assertThat(r.rowCount()).isEqualTo(0);
        assertThat(r.rows()).isEmpty();
        assertThat(r.answer()).isEqualTo(ZeroRowGuard.CAVEAT);
        assertThat(r.abstained()).isTrue();
        // The phrasing LLM must NEVER be reached on the empty path: only the SQL-gen call happened.
        Mockito.verify(chatPort, Mockito.times(1)).chat(Mockito.anyString());
    }

    @Test
    void undeclaredColumnIsRefusedWithoutRunningOrPhrasing() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT * FROM connector.t_demo_order WHERE loyalty_tier = 'GOLD'");

        AskResult r = agent.ask(7L, "demo", "orders for gold loyalty tier");

        assertThat(r.rowCount()).isEqualTo(0);
        assertThat(r.answer()).contains("loyalty_tier").contains("not a declared column");
        assertThat(r.abstained()).isTrue();
        // Refused before executing/phrasing: only the SQL-gen call happened.
        Mockito.verify(chatPort, Mockito.times(1)).chat(Mockito.anyString());
    }

    @Test
    void undeclaredTableIsRefusedWithoutRunningOrPhrasing() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT * FROM connector.t_secret_other_metier");

        AskResult r = agent.ask(7L, "demo", "everything from the other table");

        assertThat(r.rowCount()).isEqualTo(0);
        assertThat(r.answer()).contains("connector.t_secret_other_metier").contains("not a table in this scope");
        assertThat(r.abstained()).isTrue();
        Mockito.verify(chatPort, Mockito.times(1)).chat(Mockito.anyString()); // gen only, never phrasing
    }

    @Test
    void guardRefusalsAreServedInTheRequestedLanguage() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT * FROM connector.t_demo_order WHERE loyalty_tier = 'GOLD'");
        assertThat(agent.ask(7L, "demo", "commandes gold", "fr").answer())
            .contains("loyalty_tier").contains("n'est pas une colonne déclarée");

        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT * FROM connector.t_secret_other_metier");
        assertThat(agent.ask(7L, "demo", "tout l'autre table", "fr").answer())
            .contains("connector.t_secret_other_metier").contains("n'est pas une table de ce périmètre");
    }

    @Test
    void aBareTableNameCannotLeakAnotherScopesTableViaSearchPath() {
        // A cross-scope connector table NOT in the 'demo' card, holding secret data.
        agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.t_other_metier (secret text)");
        agent.seedDdl("TRUNCATE connector.t_other_metier");
        agent.seedDdl("INSERT INTO connector.t_other_metier VALUES ('LEAKED')");
        // The model omits the schema, so LeakGuard does NOT inspect it. The empty search_path stops the
        // bare name from resolving to the connector table; the read fails and is reported honestly — no leak.
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn("SELECT secret FROM t_other_metier");

        AskResult r = agent.ask(7L, "demo", "read the other metier's secret");

        assertThat(r.rowCount()).isEqualTo(0);
        assertThat(r.answer()).doesNotContain("LEAKED");      // the secret never surfaces
        // Honest, actionable degraded result, not a 500. This asserted the word "schema-qualified"
        // while the message invented that cause for every failure; it now carries Postgres' own
        // words, which say the bare name did not resolve — the very property under test, stated by
        // the database rather than guessed by us.
        assertThat(r.answer()).contains("t_other_metier").contains("does not exist");
        assertThat(r.abstained()).as("a refused read is a refusal").isTrue();
    }

    @Test
    void emptyCardAbstainsWithoutCallingTheModel() {
        store.save(7L, "empty", new SchemaCard("empty", List.of()));

        AskResult r = agent.ask(7L, "empty", "how many customers?");

        assertThat(r.answer()).isEqualTo(AbstentionGuard.NO_SCHEMA_MESSAGE);
        assertThat(r.abstained()).isTrue();
        assertThat(r.rowCount()).isEqualTo(0);
        Mockito.verify(chatPort, Mockito.never()).chat(Mockito.anyString()); // model never consulted
    }

    @Test
    void emptyCardAbstainsInTheRequestedLanguage() {
        store.save(7L, "empty", new SchemaCard("empty", List.of()));

        AskResult r = agent.ask(7L, "empty", "combien de clients ?", "fr");

        assertThat(r.answer()).isEqualTo(AskSentences.noSchema("fr"));
        assertThat(r.abstained()).isTrue();
    }

    @Test
    void modelNoAnswerAbstainsInTheRequestedLanguage() {
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn("NO_ANSWER");

        AskResult r = agent.ask(7L, "demo", "quel est le sens de la vie ?", "fr");

        assertThat(r.answer()).startsWith(AskSentences.cannotAnswer("fr"));
        assertThat(r.abstained()).as("recognised by the mark, not by the English prefix").isTrue();
    }

    @Test
    void zeroRowsAreCaveatedInTheRequestedLanguage() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT documentno FROM connector.t_demo_order WHERE documentno = 'NOPE'");

        AskResult r = agent.ask(7L, "demo", "commandes pour NOPE", "fr");

        assertThat(r.answer()).isEqualTo(AskSentences.zeroRows("fr"));
        assertThat(r.abstained()).isTrue();
    }

    @Test
    void rejectedSqlIsRefusedInTheRequestedLanguage() {
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn("DROP TABLE connector.t_demo_order");

        AskResult r = agent.ask(7L, "demo", "efface tout", "fr");

        assertThat(r.answer()).isEqualTo(AskSentences.couldNotTranslate("fr"));
    }

    @Test
    void modelNoAnswerAbstainsHonestlyWithoutPhrasing() {
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn("NO_ANSWER");

        AskResult r = agent.ask(7L, "demo", "what is the meaning of life?");

        assertThat(r.answer()).startsWith(AbstentionGuard.CANNOT_ANSWER_MESSAGE);
        assertThat(r.abstained()).isTrue();
        assertThat(r.rowCount()).isEqualTo(0);
        // gen, then the #918 rewrite — which abstains too, so no retry and never phrasing
        Mockito.verify(chatPort, Mockito.times(2)).chat(Mockito.anyString());
    }

    /** A {@code connector.v_}-prefixed table is the fused-entity signal {@code EntityViewCompiler}
     *  writes — no live workflow/entity fixture needed to trip {@code hasAFusedEntity} (#527). */
    private void seedFusedEntityScope() {
        agent.seedDdl("CREATE TABLE IF NOT EXISTS connector.v_customer (name text)");
        agent.seedDdl("TRUNCATE connector.v_customer");
        agent.seedDdl("INSERT INTO connector.v_customer VALUES ('ACME')");
        store.save(7L, "fused", new SchemaCard("fused", List.of(
            new SchemaCard.Table("connector.v_customer", "Customer", List.of(
                new SchemaCard.Column("name", "text", "Name"))))));
    }

    @Test
    void rejectedSqlNamesTheConflictsDoorOnlyWhenTheQuestionIsAboutConflicts() {
        seedFusedEntityScope();
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("UPDATE connector.v_customer SET name = 'x'");

        AskResult about = agent.ask(7L, "fused", "wipe the conflicting customer names");
        assertThat(about.answer())
            .as("SqlGuard rejection is a structural dead end (#527); the door is named when it can answer (#1047)")
            .containsIgnoringCase("couldn't")
            .endsWith(AbstentionGuard.CONFLICTS_SIGNPOST);
        assertThat(about.otherDoors()).containsExactly(AskResult.DOOR_CONFLICTS);

        AskResult unrelated = agent.ask(7L, "fused", "wipe the customer name");
        assertThat(unrelated.answer()).containsIgnoringCase("couldn't")
            .as("a request that has nothing to do with disagreeing sources gets no conflicts paragraph")
            .doesNotContain(AbstentionGuard.CONFLICTS_SIGNPOST);
        assertThat(unrelated.otherDoors()).isEmpty();
    }

    @Test
    void aMissingQuestionAbstainsHonestlyInsteadOfFailing() {
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(AbstentionGuard.SENTINEL)
            .thenReturn("some rewrite");

        AskResult r = agent.ask(7L, "demo", null);

        assertThat(r.abstained()).isTrue();
    }

    @Test
    void aRewriteThatOnlyRestatesTheStraightenedQuotesIsNotARewrite() {
        // « Badu » reaches the model as "Badu"; a rewrite echoing that is the same question, not a new attempt
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(AbstentionGuard.SENTINEL)
            .thenReturn("noms contenant \"Badu\"");

        AskResult r = agent.ask(7L, "demo", "noms contenant « Badu »");

        assertThat(r.abstained()).isTrue();
        Mockito.verify(chatPort, Mockito.times(2)).chat(Mockito.anyString());
    }

    @Test
    void aTranslationAbstentionNamesTheConflictsDoorOnlyWhenTheQuestionIsAboutConflicts() {
        seedFusedEntityScope();
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(AbstentionGuard.SENTINEL);

        AskResult search = agent.ask(7L, "fused", "customers whose name contains Badu");
        assertThat(search.abstained()).isTrue();
        assertThat(search.answer()).startsWith(AbstentionGuard.CANNOT_ANSWER_MESSAGE)
            .doesNotContain(AbstentionGuard.CONFLICTS_SIGNPOST);
        assertThat(search.otherDoors()).isEmpty();

        AskResult dispute = agent.ask(7L, "fused", "where do the ERP and the CRM disagree on the name?");
        assertThat(dispute.answer()).endsWith(AbstentionGuard.CONFLICTS_SIGNPOST);
        assertThat(dispute.otherDoors()).containsExactly(AskResult.DOOR_CONFLICTS);
    }

    @Test
    void zeroRowsNameTheConflictsDoorOnlyWhenTheQuestionIsAboutConflicts() {
        seedFusedEntityScope();
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT name FROM connector.v_customer WHERE name = 'NOPE'");

        AskResult about = agent.ask(7L, "fused", "find customer NOPE where the sources disagree");
        assertThat(about.answer())
            .as("ZeroRowGuard.CAVEAT is a dead end too (#527); the door is named when the question is about it (#1047)")
            .startsWith(ZeroRowGuard.CAVEAT)
            .endsWith(AbstentionGuard.CONFLICTS_SIGNPOST);
        assertThat(about.otherDoors()).containsExactly(AskResult.DOOR_CONFLICTS);

        AskResult search = agent.ask(7L, "fused", "find customer NOPE");
        assertThat(search.answer()).startsWith(ZeroRowGuard.CAVEAT).doesNotContain(AbstentionGuard.CONFLICTS_SIGNPOST);
        assertThat(search.otherDoors()).isEmpty();
    }
}
