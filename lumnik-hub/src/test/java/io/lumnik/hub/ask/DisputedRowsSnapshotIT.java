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
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;

@QuarkusTest
// Production enlists the readonly connection; %test disables it, so a shared snapshot cannot be shown there.
@TestProfile(ReadonlyTransactionsProfile.class)
class DisputedRowsSnapshotIT {

    @Inject AskToSqlAgent agent;
    @Inject SchemaCardStore store;
    @Inject EntitySchemaBuilder schemas;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @InjectMock @Analytic ChatPort chatPort;

    static final String MANIFEST = """
        kind: Entity
        name: DspSnapCustomer
        match: [code]
        scopes: [dspsnap]
        sources:
          - table: t_dspsnap_erp
            map: { code: code, city: city, segment: segment }
          - table: t_dspsnap_crm
            map: { customer: code, town: city }
        """;

    @BeforeEach
    void seed() throws Exception {
        exec("DROP VIEW IF EXISTS connector.\"v_dspsnapcustomer\"",
             "DROP TABLE IF EXISTS connector.t_dspsnap_erp",
             "DROP TABLE IF EXISTS connector.t_dspsnap_crm",
             "CREATE TABLE connector.t_dspsnap_erp (code text, city text, segment text)",
             "CREATE TABLE connector.t_dspsnap_crm (customer text, town text)",
             "INSERT INTO connector.t_dspsnap_erp VALUES ('P-1000','Lyon','GOLD'), ('P-1008','Lyon','GOLD')",
             "INSERT INTO connector.t_dspsnap_crm VALUES ('P-1000','Lyon'), ('P-1008','Villeurbanne')");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(MANIFEST)
            .post("/api/entities/apply").then().statusCode(200);
        store.save(7L, "dspsnap", new SchemaCard("dspsnap", schemas.resolve("dspsnap")));
    }

    @AfterEach
    void unhook() { agent.betweenReadsForTest(() -> {}); agent.failProbesForTest(false); }

    @Test
    void a_write_committed_between_the_two_reads_is_invisible_to_the_probe() {
        // Between the main read and the probe, another connection makes P-1000 disputed on city.
        agent.betweenReadsForTest(() -> exec("UPDATE connector.t_dspsnap_crm SET town = 'Villeurbanne' WHERE customer = 'P-1000'"));
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT COUNT(*) FROM connector.v_dspsnapcustomer WHERE city = 'Lyon'")
            .thenReturn("2 clients à Lyon.");

        AskResult r = agent.ask(7L, "dspsnap", "combien de clients à Lyon ?");

        assertThat(r.disputed()).extracting(Disputed::key).containsExactly("P-1008");
    }

    @Test
    void a_failed_probe_leaves_the_answer_and_its_transaction_intact() {
        agent.failProbesForTest(true);
        Mockito.when(chatPort.chat(Mockito.anyString()))
            .thenReturn("SELECT COUNT(*) FROM connector.v_dspsnapcustomer WHERE city = 'Lyon'")
            .thenReturn("2 clients à Lyon.");

        AskResult r = agent.ask(7L, "dspsnap", "combien de clients à Lyon ?");

        assertThat(r.answer()).isEqualTo("2 clients à Lyon.");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.disputed()).isEmpty();
    }

    private void exec(String... sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            for (String s : sql) st.execute(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
