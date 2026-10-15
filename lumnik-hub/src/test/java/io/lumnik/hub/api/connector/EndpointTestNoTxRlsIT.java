package io.lumnik.hub.api.connector;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.rest.runtime.RestPreflight;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.EventItSupport;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@code lm source rest test} sends the one request a run would send — an HTTP call to the
 * customer's source, up to the client's timeout. The door used to be {@code @Transactional} around
 * it, so a slow source pinned a default-pool connection for the whole pre-flight (#909). The two
 * rows are read in a short transaction and the request goes out with nothing open.
 *
 * <p>Under the RLS-enforced profile on purpose: a read that slipped out of its transaction would
 * lose the {@code SET LOCAL} tenant binding and answer 404 here; the owner-connected default
 * profile could not tell.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EndpointTestNoTxRlsIT {

    private static final String NAME_PREFIX = "ntx-preflight-";

    @InjectMock RestPreflight preflight;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;   // the owner, so the fixture can seed past RLS

    @AfterEach
    void purge() {   // the endpoint row goes with its connector (ON DELETE CASCADE)
        EventItSupport.execSql(adminDs, "DELETE FROM connector.connector WHERE name LIKE '" + NAME_PREFIX + "%'");
    }

    @Test
    void the_preflight_request_goes_out_with_no_transaction_held() {
        List<Boolean> txActiveAtCheck = new CopyOnWriteArrayList<>();
        when(preflight.check(any(), any())).thenAnswer(inv -> {
            txActiveAtCheck.add(QuarkusTransaction.isActive());
            return new RestPreflight.Verdict("contacts", "GET", "https://api.example.com/v1/contacts",
                200, true, 0, "", "");
        });
        UUID cid = insertReturningId("""
            INSERT INTO connector.connector (tenant_id, name, type_id, config)
            VALUES (1, ?, 'rest-generic', '{"kind":"rest-generic","base_url":"https://api.example.com"}'::jsonb)
            RETURNING id
            """, NAME_PREFIX + System.nanoTime());
        UUID eid = insertReturningId("""
            INSERT INTO connector.connector_endpoint (connector_id, tenant_id, name, path, config)
            VALUES (?, 1, 'contacts', '/v1/contacts', '{}'::jsonb) RETURNING id
            """, cid);

        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/connectors/" + cid + "/endpoints/" + eid + "/test")
            .then().statusCode(200).body("endpoint", is("contacts"));

        assertThat(txActiveAtCheck)
            .as("the pre-flight must not hold a JTA transaction (and its pooled connection) across its HTTP call")
            .containsExactly(false);
    }

    private UUID insertReturningId(String sql, Object param) {
        return Jdbc.queryOne(adminDs, "EndpointTestNoTxRlsIT.seed", sql,
            ps -> ps.setObject(1, param), rs -> rs.getObject(1, UUID.class)).orElseThrow();
    }
}
