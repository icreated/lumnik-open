package io.lumnik.hub.webhook;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.EventItSupport;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionalException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Both replay doors send a delivery that is already on record — an HTTP POST to a customer's
 * endpoint, up to the delivery service's 5 s connect + 10 s request. They used to be
 * {@code @Transactional} around that call, so one hung endpoint pinned a default-pool connection
 * for the whole send (#909). The rows are read in a short transaction, the send runs with nothing
 * open, and the new attempt is recorded in a second short one — the shape the retry sweep and
 * {@code IntegrationResource#test} already have.
 *
 * <p>Under the RLS-enforced profile on purpose: the tenant is bound with {@code SET LOCAL}, which
 * is transaction-scoped, so a read or a write that slipped out of its short transaction would find
 * nothing (a 404) or fail the policy's check. The default test profile connects as the owner and
 * could not tell.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class WebhookReplayNoTxRlsIT {

    private static final String NAME_PREFIX = "ntx-replay-";

    @InjectMock WebhookDeliveryService deliveryService;
    @Inject WebhookReplayService replayService;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;   // the owner, so the fixture can seed past RLS

    /** Fail-closed: starts EMPTY, so an assertion on its contents cannot pass without a real call. */
    private final List<Boolean> txActiveAtDelivery = new CopyOnWriteArrayList<>();

    private String endpointName;
    private long endpointId;
    private long deliveryId;

    @BeforeEach
    void seed() {
        txActiveAtDelivery.clear();
        endpointName = NAME_PREFIX + System.nanoTime();
        // The retry sweep and the outbox consumer share this mock, so only this test's endpoint counts.
        when(deliveryService.deliver(any(), any(), any(), any())).thenAnswer(inv -> {
            WebhookEndpoint ep = inv.getArgument(0);
            if (endpointName.equals(ep.getName())) txActiveAtDelivery.add(QuarkusTransaction.isActive());
            return WebhookDeliveryService.DeliveryResult.success(200, "", 1);
        });
        endpointId = insertReturningId("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, ?, 'http://localhost:1/none', 'generic', 'NONE', '{ntx.replay.*}', TRUE) RETURNING id
            """, endpointName);
        // attempts = 3: past the retry sweep's window, so nothing but the replay sends this row.
        deliveryId = insertReturningId("""
            INSERT INTO core.webhook_delivery (tenant_id, endpoint_id, event_type, payload, attempted_at, attempts, succeeded)
            VALUES (1, ?, 'ntx.replay.probe', '{"k":"v"}'::jsonb, NOW(), 3, FALSE) RETURNING id
            """, endpointId);
    }

    @AfterEach
    void purge() {
        EventItSupport.purgeWebhookEndpoints(adminDs, NAME_PREFIX + "%");
    }

    @Test
    void the_platform_replay_sends_with_no_transaction_held() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/deliveries/" + deliveryId + "/replay")
            .then().statusCode(200);

        assertSentOutsideATransactionAndRecorded();
    }

    @Test
    void the_tenant_in_url_replay_sends_with_no_transaction_held() {
        given().contentType("application/json").headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/tenants/1/integrations/" + endpointId + "/deliveries/" + deliveryId + "/replay")
            .then().statusCode(200);

        assertSentOutsideATransactionAndRecorded();
    }

    /**
     * The contract is the service's, not each door's: it records the attempt in a transaction of
     * its own, so a caller holding one would both pin a connection across the send and see the
     * record commit apart from its own work. It refuses such a caller before sending anything.
     */
    @Test
    void the_replay_service_refuses_a_caller_that_holds_a_transaction() {
        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setName(endpointName);

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> replayService.replay(ep, new WebhookDelivery())))
            .isInstanceOf(TransactionalException.class);
        assertThat(txActiveAtDelivery).as("refused before anything was sent").isEmpty();
    }

    private void assertSentOutsideATransactionAndRecorded() {
        assertThat(txActiveAtDelivery)
            .as("the replay must not hold a JTA transaction (and its pooled connection) across the outbound HTTP call")
            .containsExactly(false);
        assertThat(EventItSupport.scalar(adminDs,
                "SELECT count(*) FROM core.webhook_delivery WHERE endpoint_id = " + endpointId))
            .as("the replay is recorded as a new attempt beside the original, in a transaction of its own")
            .isEqualTo(2);
    }

    private long insertReturningId(String sql, Object param) {
        return Jdbc.queryOne(adminDs, "WebhookReplayNoTxRlsIT.seed", sql,
            ps -> ps.setObject(1, param), rs -> rs.getLong(1)).orElseThrow();
    }
}
