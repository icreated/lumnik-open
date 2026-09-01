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
    void rejectedSqlDegradesCleanlyWithoutRunning() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("UPDATE connector.t_demo_order SET grandtotal = 0");

        AskResult r = agent.ask(7L, "demo", "wipe the totals");

        assertThat(r.rowCount()).isEqualTo(0);
        assertThat(r.answer()).containsIgnoringCase("couldn't");
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
        Mockito.verify(chatPort, Mockito.times(1)).chat(Mockito.anyString()); // gen only, never phrasing
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
        assertThat(r.answer()).contains("schema-qualified");  // honest, actionable degraded result, not a 500
    }

    @Test
    void emptyCardAbstainsWithoutCallingTheModel() {
        store.save(7L, "empty", new SchemaCard("empty", List.of()));

        AskResult r = agent.ask(7L, "empty", "how many customers?");

        assertThat(r.answer()).isEqualTo(AbstentionGuard.NO_SCHEMA_MESSAGE);
        assertThat(r.rowCount()).isEqualTo(0);
        Mockito.verify(chatPort, Mockito.never()).chat(Mockito.anyString()); // model never consulted
    }

    @Test
    void modelNoAnswerAbstainsHonestlyWithoutPhrasing() {
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn("NO_ANSWER");

        AskResult r = agent.ask(7L, "demo", "what is the meaning of life?");

        assertThat(r.answer()).isEqualTo(AbstentionGuard.CANNOT_ANSWER_MESSAGE);
        assertThat(r.rowCount()).isEqualTo(0);
        Mockito.verify(chatPort, Mockito.times(1)).chat(Mockito.anyString()); // gen only, never phrasing
    }
}
