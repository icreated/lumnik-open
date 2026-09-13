package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * V32's row-level security, actually enforced. The stores talk to the admin datasource (RLS is
 * bound in SQL there), so only the non-owner {@code lumnik_app} role of this harness can prove
 * the POLICY itself is what keeps one tenant's declared lifecycle out of another's reach —
 * the spec's "store + apply ITs under RlsEnforcedProfile: two tenants, cross-invisibility".
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkflowRlsIT {

    private static final String YAML = """
        apiVersion: apps.lumnik.io/v1
        kind: Workflow
        metadata: { name: wf-rls-it }
        spec:
          scope: demo
          provenance: declared
          on: { table: connector.t_wf_rls_it, column: statut }
          states:
            - { code: A, initial: true }
            - { code: B, terminal: true }
          transitions:
            - { from: [A], to: B }
        """;

    @Inject AgroalDataSource appDs;   // default DS = lumnik_app, RLS enforced

    @Inject SchemaCardStore cards;

    @BeforeEach
    void seedScopes() {
        cards.save(1L, "registry", new SchemaCard("demo", List.of()));
        cards.save(2L, "registry", new SchemaCard("demo", List.of()));
    }

    private static RequestSpecification as(String tenant) {
        return given().config(YamlBody.CONFIG).header("X-Tenant-ID", tenant).header("X-User-ID", tenant);
    }

    /** Count inside a tenant GUC transaction — the same idiom as ConnectorRunRlsIT. */
    private long manifestsVisibleTo(String tenant) {
        try (Connection c = appDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("BEGIN");
            st.execute("SET LOCAL app.current_tenant = '" + tenant + "'");
            try (ResultSet rs = st.executeQuery(
                "SELECT count(*) FROM core.workflow_manifest WHERE name = 'wf-rls-it'")) {
                rs.next();
                long n = rs.getLong(1);
                st.execute("COMMIT");
                return n;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Order(1)
    void tenant1_declares_its_lifecycle() {
        as("1").contentType("application/yaml").body(YAML)
            .when().post("/api/workflows/apply")
            .then().statusCode(200)
            .body("name", equalTo("wf-rls-it"))
            .body("scope", equalTo("demo"));
    }

    @Test
    @Order(2)
    void tenant2_cannot_see_tenant1s_manifest() {
        as("2").when().get("/api/workflows")
            .then().statusCode(200)
            .body("findAll { it.name == 'wf-rls-it' }", hasSize(0));
        as("2").when().get("/api/workflows/wf-rls-it/manifest")
            .then().statusCode(404);
    }

    /** The policy itself, read as the application role: tenant 1 sees its row, tenant 2 none. */
    @Test
    @Order(3)
    void the_rls_policy_hides_the_row_from_the_other_tenant() {
        assertThat(manifestsVisibleTo("1")).as("tenant 1 sees its own manifest").isEqualTo(1L);
        assertThat(manifestsVisibleTo("2")).as("tenant 2 must see nothing").isZero();
    }

    /** Same name, same table, other tenant: a legal declaration — tenancy is the outer boundary,
     *  the one-lifecycle-per-column rule is bounded by it. */
    @Test
    @Order(4)
    void tenant2_may_declare_the_same_name_on_the_same_column() {
        as("2").contentType("application/yaml").body(YAML)
            .when().post("/api/workflows/apply")
            .then().statusCode(200);
        assertThat(manifestsVisibleTo("1")).isEqualTo(1L);
        assertThat(manifestsVisibleTo("2")).isEqualTo(1L);
    }
}
