package io.lumnik.hub.export;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class EntitySnapshotDownloadNoAuditOnFailureIT {

    private static final String ENTITY = """
        kind: Entity
        name: SnapshotFailureEntity677
        match: [code]
        scopes: [sales]
        sources:
          - table: t_snapshotfailure677_erp
            map:
              cust_id: code
        """;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @InjectMock EntitySnapshotStreamer streamer;

    @BeforeEach
    void applyEntityAndSeedARow() throws Exception {
        EntitySourceTableFixture.create(adminDs, "t_snapshotfailure677_erp",
            "v_snapshotfailureentity677", "cust_id");
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(ENTITY)
            .post("/api/entities/apply")
            .then().statusCode(200);
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO connector.t_snapshotfailure677_erp (tenant_id, cust_id) VALUES (1, '1')");
        }
        when(streamer.stream(any(), any(), any(), any(), any(), any())).thenThrow(new IOException("simulated mid-stream failure"));
    }

    @Test
    void aFailureInsideStreamProducesNoAuditRow() throws Exception {
        given().headers(TestIdentity.tenantAndUser(1))
            .get("/api/exports/entities/SnapshotFailureEntity677/download");
            // No .then().statusCode(...) assertion on purpose: a StreamingOutput failure after
            // headers have already committed to 200 does not have one single well-defined status
            // the client sees — what this test actually cares about is the audit table, below.

        try (Connection c = adminDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT count(*) FROM core.audit_outbox WHERE entity_key = 'SnapshotFailureEntity677' "
               + "AND operation = 'EGRESS'")) {
            rs.next();
            assertEquals(0, rs.getLong(1));
        }
    }
}
