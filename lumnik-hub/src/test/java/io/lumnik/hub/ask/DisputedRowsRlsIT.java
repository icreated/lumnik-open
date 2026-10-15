package io.lumnik.hub.ask;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.Disputed;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The disputed-row probe runs under the caller's tenant: a tenant never sees another tenant's
 * disputed records. Tenant 2 owns the dispute (control); tenant 1 holds the same key undisputed.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class DisputedRowsRlsIT {

    @Inject AskToSqlAgent agent;
    @Inject SchemaCardStore store;
    @Inject EntitySchemaBuilder schemas;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @InjectMock @Analytic ChatPort chatPort;

    static final String MANIFEST = """
        kind: Entity
        name: DspRlsCustomer
        match: [code]
        scopes: [dsprls]
        sources:
          - table: t_dsprls_erp
            map: { code: code, city: city }
          - table: t_dsprls_crm
            map: { customer: code, town: city }
        """;

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP VIEW IF EXISTS connector.\"v_dsprlscustomer\"");
            st.execute("DROP TABLE IF EXISTS connector.t_dsprls_erp");
            st.execute("DROP TABLE IF EXISTS connector.t_dsprls_crm");
            st.execute("CREATE TABLE connector.t_dsprls_erp (tenant_id bigint NOT NULL, code text, city text)");
            st.execute("CREATE TABLE connector.t_dsprls_crm (tenant_id bigint NOT NULL, customer text, town text)");
            st.execute("INSERT INTO connector.t_dsprls_erp VALUES (1,'P-1008','Lyon'), (2,'P-1008','Lyon')");
            st.execute("INSERT INTO connector.t_dsprls_crm VALUES (1,'P-1008','Lyon'), (2,'P-1008','Villeurbanne')");
            // seeded before FORCE: the owner would otherwise be bound by the policy
            for (String t : new String[] {"t_dsprls_erp", "t_dsprls_crm"}) {
                st.execute("ALTER TABLE connector." + t + " ENABLE ROW LEVEL SECURITY");
                st.execute("ALTER TABLE connector." + t + " FORCE ROW LEVEL SECURITY");
                st.execute("CREATE POLICY tenant_isolation ON connector." + t
                    + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
                st.execute("GRANT SELECT ON connector." + t + " TO lumnik_app");
            }
        }
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(MANIFEST)
            .post("/api/entities/apply").then().statusCode(200);
        store.save(1L, "dsprls", new SchemaCard("dsprls", schemas.resolve("dsprls")));
        store.save(2L, "dsprls", new SchemaCard("dsprls", schemas.resolve("dsprls")));
    }

    @Test
    void another_tenants_disputed_record_never_appears() {
        when("SELECT COUNT(*) FROM connector.v_dsprlscustomer WHERE city = 'Lyon'", "1.");
        assertThat(agent.ask(1L, "dsprls", "Lyon ?").disputed()).isEmpty();
    }

    @Test
    void the_same_question_as_the_tenant_that_owns_the_dispute_names_it() {
        when("SELECT COUNT(*) FROM connector.v_dsprlscustomer WHERE city = 'Lyon'", "1.");
        assertThat(agent.ask(2L, "dsprls", "Lyon ?").disputed()).extracting(Disputed::key).containsExactly("P-1008");
    }

    private void when(String sql, String answer) {
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(sql).thenReturn(answer);
    }
}
