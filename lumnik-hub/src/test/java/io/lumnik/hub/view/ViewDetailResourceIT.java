package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ViewDetailResourceIT {

    @Inject SchemaCardStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() {
        exec("CREATE TABLE IF NOT EXISTS connector.t_view_detail_res "
           + "(customer_code text, name text, _conflicts text)");
        exec("TRUNCATE connector.t_view_detail_res");
        exec("INSERT INTO connector.t_view_detail_res VALUES "
           + "('C-200','Marie','{\"name\": [{\"src\":\"cust_siebel\",\"value\":\"Marie\"},"
           + "{\"src\":\"t_cust_csv\",\"value\":\"MARIE A.\"}]}'),"
           + "('C-100','Jean','{}'),"
           + "('DUP','A','{}'), ('DUP','B','{}')");

        store.save(1L, "registry", new SchemaCard("desk", List.of(
            new SchemaCard.Table("connector.t_view_detail_res", "Desk", List.of(
                new SchemaCard.Column("customer_code", "text", "Code client"),
                new SchemaCard.Column("name", "text", "Nom"))))));
    }

    private static final String MANIFEST = """
        apiVersion: apps.lumnik.io/v1
        kind: View
        metadata: { name: desk-view }
        spec:
          scope: desk
          table: connector.t_view_detail_res
          title: Clients
          columns: [customer_code, name]
        """;

    @Test
    void rendersOneRecordWithItsConflictLedger() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("key", "C-200").body(MANIFEST)
        .when().post("/api/views/detail")
        .then().statusCode(200)
            .body("title", is("Clients"))
            .body("key", is("C-200"))
            .body("fields.label", contains("Code client", "Nom"))
            .body("fields.value", contains("C-200", "Marie"))
            .body("conflictLedger", is(true))
            .body("conflicts[0].label", is("Nom"))
            .body("conflicts[0].sources.value", contains("Marie", "MARIE A."));
    }

    @Test
    void agreementCaseHasEmptyConflicts() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("key", "C-100").body(MANIFEST)
        .when().post("/api/views/detail")
        .then().statusCode(200)
            .body("conflictLedger", is(true))
            .body("conflicts", hasSize(0));
    }

    @Test
    void unknownKey_404() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("key", "GHOST").body(MANIFEST)
        .when().post("/api/views/detail")
        .then().statusCode(404)
            .body("error", containsString("customer_code"));
    }

    @Test
    void ambiguousKey_400() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("key", "DUP").body(MANIFEST)
        .when().post("/api/views/detail")
        .then().statusCode(400)
            .body("error", containsString("ambiguous"));
    }

    @Test
    void missingKey_400() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .body(MANIFEST)
        .when().post("/api/views/detail")
        .then().statusCode(400)
            .body("error", containsString("key"));
    }

    @Test
    void withoutTenant_401() {
        given().config(YamlBody.CONFIG).contentType("application/yaml")
            .queryParam("key", "C-200").body(MANIFEST)
        .when().post("/api/views/detail")
        .then().statusCode(401);
    }
}
