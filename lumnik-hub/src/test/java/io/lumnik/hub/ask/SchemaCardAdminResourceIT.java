package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class SchemaCardAdminResourceIT {

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
        long tenant = 8300L;
        exec("DELETE FROM connector.connector WHERE tenant_id = " + tenant);
        exec("DELETE FROM connector.dynamic_table WHERE tenant_id = " + tenant);
        exec("DELETE FROM connector.schema_card WHERE tenant_id = " + tenant);
        UUID connId = UUID.fromString("00000000-0000-0000-0000-0000000083bb");
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags, "
           + "sync_mode, skip_policy) VALUES ('" + connId + "', " + tenant
           + ", 'src--c_order', 'csv-file', '{}'::jsonb, true, ARRAY['atelierC'], "
           + "'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
        exec("INSERT INTO connector.dynamic_table (id, tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) "
           + "VALUES (gen_random_uuid(), " + tenant + ", 'jdbc:c_order', 'connector', 't_c_order', "
           + "'{\"documentno\":\"text\"}'::jsonb, '" + connId + "')");
    }

    @Test
    void rediscoverThenGetSchema() {
        // In dev-bypass mode all roles are granted; X-Lumnik-Roles header is ignored.
        // We still send it to document intent; omitting it would also yield 200.
        given().header("X-Tenant-ID", "8300")
            .contentType(ContentType.JSON)
        .when().post("/api/platform/rag/rediscover?scope=atelierC")
        .then().statusCode(200)
            .body("scope", is("atelierC"))
            .body("tableCount", is(1))
            .body("enriched", is(false))
            .body("sourceType", is("registry"));

        given().header("X-Tenant-ID", "8300")
        .when().get("/api/platform/rag/schema?scope=atelierC")
        .then().statusCode(200)
            .body("scope", is("atelierC"))
            .body("tables[0].name", is("connector.t_c_order"))
            .body("tables[0].columns[0].name", is("documentno"));
    }

    @Test
    void getUnknownScope_returns404() {
        given().header("X-Tenant-ID", "8300")
        .when().get("/api/platform/rag/schema?scope=ghost")
        .then().statusCode(404);
    }

    /**
     * Tenant must come ONLY from the auth-resolved context (JWT in prod, validated header in
     * dev-bypass) — never from a client-supplied header defaulting to 0. Same contract as
     * RagChatResource's analytic path: no tenant in context → 401, before any other answer.
     */
    @Test
    void getSchemaWithoutTenantInContext_returns401() {
        given()
        .when().get("/api/platform/rag/schema?scope=atelierC")
        .then().statusCode(401);
    }

    @Test
    void rediscoverWithoutTenantInContext_returns401() {
        given().contentType(ContentType.JSON)
        .when().post("/api/platform/rag/rediscover?scope=atelierC")
        .then().statusCode(401);
    }

    @Test
    void zeroSentinelTenant_returns401() {
        given().header("X-Tenant-ID", "0")
        .when().get("/api/platform/rag/schema?scope=atelierC")
        .then().statusCode(401);
    }
}
