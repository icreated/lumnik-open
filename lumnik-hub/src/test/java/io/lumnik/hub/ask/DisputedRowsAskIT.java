package io.lumnik.hub.ask;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.Disputed;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;

@QuarkusTest
class DisputedRowsAskIT {

    @Inject AskToSqlAgent agent;
    @Inject SchemaCardStore store;
    @Inject EntitySchemaBuilder schemas;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @InjectMock @Analytic ChatPort chatPort;

    static final String MANIFEST = """
        kind: Entity
        name: DspCustomer
        match: [code]
        scopes: [dsp]
        columns:
          segment:
            description: "Commercial segment."
            values: [GOLD, SILVER, BRONZE]
        sources:
          - table: t_dsp_erp
            map: { code: code, city: city, segment: segment }
          - table: t_dsp_crm
            map: { customer: code, town: city }
        """;

    @BeforeEach
    void seed() throws Exception {
        exec("DROP VIEW IF EXISTS connector.\"v_dspcustomer\"",
             "DROP TABLE IF EXISTS connector.t_dsp_erp",
             "DROP TABLE IF EXISTS connector.t_dsp_crm",
             "CREATE TABLE connector.t_dsp_erp (code text, city text, segment text)",
             "CREATE TABLE connector.t_dsp_crm (customer text, town text)",
             "INSERT INTO connector.t_dsp_erp VALUES ('P-1000','Lyon','GOLD'), ('P-1008','Lyon','GOLD'), ('P-1002','Milano','BRONZE')",
             "INSERT INTO connector.t_dsp_crm VALUES ('P-1000','Lyon'), ('P-1008','Villeurbanne'), ('P-1002','Milan')");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(MANIFEST)
            .post("/api/entities/apply").then().statusCode(200);
        store.save(7L, "dsp", new SchemaCard("dsp", schemas.resolve("dsp")));
    }

    @AfterEach
    void unhook() { agent.betweenReadsForTest(null); }

    @Test
    void counted_on_a_disputed_value_is_named_and_opens_the_conflicts_door() {
        when("SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city = 'Lyon' AND segment = 'GOLD'", "2 clients GOLD à Lyon.");
        AskResult r = agent.ask(7L, "dsp", "combien de clients GOLD à Lyon ?");
        assertThat(r.disputed()).singleElement().satisfies(d -> {
            assertThat(d.key()).isEqualTo("P-1008");
            assertThat(d.column()).isEqualTo("city");
            assertThat(d.counted()).isTrue();
            assertThat(d.sources()).extracting(Disputed.SourceValue::value).containsExactly("Lyon", "Villeurbanne");
        });
        assertThat(r.otherDoors()).contains(AskResult.DOOR_CONFLICTS);
        assertThat(r.answer()).isEqualTo("2 clients GOLD à Lyon.");
    }

    @Test
    void excluded_while_a_source_holds_the_value_rides_on_the_zero_row_caveat() {
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT code FROM connector.v_dspcustomer WHERE city = 'Villeurbanne'");
        AskResult r = agent.ask(7L, "dsp", "les clients à Villeurbanne ?");
        assertThat(r.rowCount()).isZero();
        assertThat(r.answer()).startsWith(ZeroRowGuard.CAVEAT);
        assertThat(r.disputed()).singleElement().satisfies(d -> {
            assertThat(d.key()).isEqualTo("P-1008");
            assertThat(d.counted()).isFalse();
            assertThat(d.matched()).isEqualTo("Villeurbanne");
            assertThat(d.retained()).isEqualTo("Lyon");
        });
        assertThat(r.otherDoors()).containsOnlyOnce(AskResult.DOOR_CONFLICTS);
    }

    @Test
    void an_undisputed_column_names_nothing() {
        when("SELECT COUNT(*) FROM connector.v_dspcustomer WHERE segment = 'GOLD'", "2.");
        assertThat(agent.ask(7L, "dsp", "combien de GOLD ?").disputed()).isEmpty();
    }

    @Test
    void a_lookup_by_the_match_key_takes_no_probe() {
        AtomicInteger probeRounds = new AtomicInteger();
        agent.betweenReadsForTest(probeRounds::incrementAndGet);
        when("SELECT city FROM connector.v_dspcustomer WHERE code = 'P-1008'", "Lyon.");
        AskResult r = agent.ask(7L, "dsp", "la ville du client P-1008 ?");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.disputed()).isEmpty();
        assertThat(probeRounds).hasValue(0);
    }

    @Test
    void the_match_key_still_narrows_the_probe_of_another_filter() {
        // A second Lyon dispute under another key: only the key filter keeps it out of the answer's block.
        exec("INSERT INTO connector.t_dsp_erp VALUES ('P-1009', 'Lyon', 'GOLD')",
             "INSERT INTO connector.t_dsp_crm VALUES ('P-1009', 'Caluire')");
        when("SELECT COUNT(*) FROM connector.v_dspcustomer WHERE code = 'P-1008' AND city = 'Lyon'", "1.");
        assertThat(agent.ask(7L, "dsp", "P-1008 est-il à Lyon ?").disputed()).singleElement().satisfies(d -> {
            assertThat(d.key()).isEqualTo("P-1008");
            assertThat(d.column()).isEqualTo("city");
            assertThat(d.counted()).isTrue();
        });
    }

    @Test
    void an_or_query_is_left_alone() {
        when("SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city = 'Lyon' OR segment = 'BRONZE'", "3.");
        assertThat(agent.ask(7L, "dsp", "Lyon ou BRONZE ?").disputed()).isEmpty();
    }

    @Test
    void a_model_written_limit_reports_no_counted_entry() {
        when("SELECT code FROM connector.v_dspcustomer WHERE city = 'Lyon' ORDER BY code LIMIT 1", "P-1000.");
        assertThat(agent.ask(7L, "dsp", "le premier client à Lyon ?").disputed()).isEmpty();
    }

    @Test
    void the_disputed_list_is_empty_and_the_doors_unchanged_when_nothing_is_disputed() {
        when("SELECT COUNT(*) FROM connector.v_dspcustomer WHERE segment = 'BRONZE'", "1.");
        AskResult r = agent.ask(7L, "dsp", "BRONZE ?");
        assertThat(r.otherDoors()).doesNotContain(AskResult.DOOR_CONFLICTS);
        assertThat(r.disputed()).isEmpty();
        assertThat(r.disputedTruncated()).isFalse();
    }

    @Test
    void a_domain_guard_refusal_names_nothing_disputed() {
        when("SELECT COUNT(*) FROM connector.v_dspcustomer WHERE segment = 'PLATINUM'", "0.");
        AskResult r = agent.ask(7L, "dsp", "combien de PLATINUM ?");
        assertThat(r.answer()).contains("is not a declared value of");
        assertThat(r.rowCount()).isZero();
        assertThat(r.disputed()).isEmpty();
    }

    @Test
    void an_excluded_record_after_ten_counted_ones_in_key_order_is_still_named() {
        exec("INSERT INTO connector.t_dsp_erp SELECT 'A-' || lpad(i::text, 2, '0'), 'Lyon', 'GOLD' FROM generate_series(1, 10) i",
             "INSERT INTO connector.t_dsp_crm SELECT 'A-' || lpad(i::text, 2, '0'), 'Bron' FROM generate_series(1, 10) i",
             "INSERT INTO connector.t_dsp_erp VALUES ('Z-99', 'Nice', 'GOLD')",
             "INSERT INTO connector.t_dsp_crm VALUES ('Z-99', 'Lyon')");
        when("SELECT COUNT(*) FROM connector.v_dspcustomer WHERE city = 'Lyon'", "12.");
        AskResult r = agent.ask(7L, "dsp", "combien de clients à Lyon ?");
        assertThat(r.disputed()).first().satisfies(d -> {
            assertThat(d.key()).isEqualTo("Z-99");
            assertThat(d.counted()).isFalse();
        });
    }

    @Test
    void inadmissible_counted_entries_neither_crowd_out_an_excluded_one_nor_raise_truncation() {
        exec("INSERT INTO connector.t_dsp_erp SELECT 'A-' || lpad(i::text, 2, '0'), 'Lyon', 'GOLD' FROM generate_series(1, 11) i",
             "INSERT INTO connector.t_dsp_crm SELECT 'A-' || lpad(i::text, 2, '0'), 'Bron' FROM generate_series(1, 11) i",
             "INSERT INTO connector.t_dsp_erp VALUES ('Z-99', 'Nice', 'GOLD')",
             "INSERT INTO connector.t_dsp_crm VALUES ('Z-99', 'Lyon')");
        when("SELECT code FROM connector.v_dspcustomer WHERE city = 'Lyon' ORDER BY code LIMIT 3", "A-01, A-02, A-03.");
        AskResult r = agent.ask(7L, "dsp", "trois clients à Lyon ?");
        assertThat(r.disputed()).singleElement().satisfies(d -> {
            assertThat(d.key()).isEqualTo("Z-99");
            assertThat(d.counted()).isFalse();
        });
        assertThat(r.disputedTruncated()).isFalse();
    }

    private void when(String sql, String answer) {
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(sql).thenReturn(answer);
    }

    private void exec(String... sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            for (String s : sql) st.execute(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
