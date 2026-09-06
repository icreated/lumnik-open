package io.lumnik.hub.webhook;

import io.lumnik.testkit.auth.TestIdentity;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;

/** The platform-family replay: addressed by delivery, tenant from context, never leaking another
 *  tenant's rows. The send targets an unroutable host on purpose — what is under test is the
 *  authorization boundary and the recording of a new attempt, not the HTTP outcome. */
@QuarkusTest
class PlatformDeliveryReplayIT {

    @Inject EntityManager em;

    static WireMockServer wm;

    long mine;
    long theirs;
    long mineEndpoint;
    long liveEndpoint;

    @BeforeAll
    static void startWm() {
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stopWm() { wm.stop(); }

    @BeforeEach
    void seed() {
        wm.resetAll();
        seedTwoTenants();
        mine = idOf("replay.probe.mine");
        theirs = idOf("replay.probe.theirs");
        mineEndpoint = endpointIdOf("replay-probe-1");
        liveEndpoint = endpointIdOf("replay-probe-live");
    }

    @Transactional
    void seedTwoTenants() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE event_type LIKE 'replay.probe%'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_endpoint WHERE name LIKE 'replay-probe%'").executeUpdate();
        for (int tenant = 1; tenant <= 2; tenant++) {
            em.createNativeQuery("""
                INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
                VALUES (:t, :n, 'http://localhost:1/x', 'generic', 'NONE', '{replay.probe.*}', TRUE)
                """).setParameter("t", tenant).setParameter("n", "replay-probe-" + tenant).executeUpdate();
        }
        insertFailed(1, "replay-probe-1", "replay.probe.mine");
        insertFailed(2, "replay-probe-2", "replay.probe.theirs");

        // A second tenant-1 endpoint that actually answers 200: an unroutable target can never
        // discriminate "succeeded was recorded" from the succeeded-defaults-to-false bug.
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, 'replay-probe-live', :url, 'generic', 'NONE', '{replay.probe.*}', TRUE)
            """).setParameter("url", wm.baseUrl() + "/live").executeUpdate();
        insertFailed(1, "replay-probe-live", "replay.probe.live");
    }

    private void insertFailed(int tenant, String endpointName, String type) {
        em.createNativeQuery("""
            INSERT INTO core.webhook_delivery
                (tenant_id, endpoint_id, event_type, payload, http_status, error, duration_ms, attempted_at, attempts, succeeded)
            SELECT :t, e.id, :type, '{"probe":1}'::jsonb, 502, 'boom', 10, NOW(), 1, FALSE
            FROM core.webhook_endpoint e WHERE e.name = :name
            """).setParameter("t", tenant).setParameter("type", type)
            .setParameter("name", endpointName).executeUpdate();
    }

    @Transactional
    long idOf(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM core.webhook_delivery WHERE event_type = :type ORDER BY id DESC LIMIT 1")
            .setParameter("type", type).getSingleResult()).longValue();
    }

    @Transactional
    long endpointIdOf(String name) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM core.webhook_endpoint WHERE name = :name")
            .setParameter("name", name).getSingleResult()).longValue();
    }

    @Transactional
    long attemptCount(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM core.webhook_delivery WHERE event_type = :type")
            .setParameter("type", type).getSingleResult()).longValue();
    }

    @Transactional
    boolean newestSucceeded(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return (Boolean) em.createNativeQuery(
                "SELECT succeeded FROM core.webhook_delivery WHERE event_type = :type ORDER BY id DESC LIMIT 1")
            .setParameter("type", type).getSingleResult();
    }

    /**
     * Deleting an integration must close the replay door too. The retry sweep now resolves through
     * {@code findLiveById}; this is the other delivery path — a human asking for one more send —
     * and it used a plain {@code findById}, which filters nothing. {@code softDelete()} stamps only
     * {@code deletedAt}, so the endpoint came back and the replay POSTed to an integration the
     * customer had removed.
     */
    @Test
    void replaying_to_a_deleted_integration_is_refused() {
        softDelete("replay-probe-live");
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/deliveries/" + idOf("replay.probe.live") + "/replay")
            .then().statusCode(404);
        wm.verify(0, WireMock
            .postRequestedFor(WireMock.urlEqualTo("/live")));
    }

    /**
     * The list's own javadoc promises "(deleted)" for an integration removed after its deliveries
     * were recorded — but namesFor resolved names with an unfiltered `find`, so the name came back
     * and the promise never happened. It matters more now that replay refuses a deleted endpoint:
     * the row showed a live-looking name while replay answered 404, with nothing to explain why.
     */
    @Test
    void the_list_shows_a_deleted_integration_as_deleted() {
        softDelete("replay-probe-live");
        given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/platform/deliveries")
            .then().statusCode(200)
            .body("find { it.eventType == 'replay.probe.live' }.integration", equalTo("(deleted)"));
    }

    @Transactional
    void softDelete(String endpointName) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("UPDATE core.webhook_endpoint SET deleted_at = NOW() WHERE name = :n")
          .setParameter("n", endpointName).executeUpdate();
    }

    @Test
    void replaying_another_tenants_delivery_is_a_404_not_a_403() {
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/deliveries/" + theirs + "/replay")
            .then().statusCode(404);
    }

    @Test
    void replaying_my_own_delivery_records_a_new_attempt() {
        assertThat(attemptCount("replay.probe.mine")).isEqualTo(1);
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/deliveries/" + mine + "/replay")
            .then().statusCode(200);
        assertThat(attemptCount("replay.probe.mine"))
            .as("the replay must be recorded as a new attempt, never overwrite the original")
            .isEqualTo(2);
    }

    /** The discriminating case: a replay that actually succeeds must record succeeded=TRUE. An
     *  unroutable target (as the sibling tests use) can never tell "the flag was set" apart from
     *  "the flag defaults to false" — both look identical. This one hits a live 200 endpoint. */
    @Test
    void a_successful_replay_records_succeeded_true() {
        wm.stubFor(post("/live").willReturn(aResponse().withStatus(200)));
        given().headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/platform/deliveries/" + idOf("replay.probe.live") + "/replay")
            .then().statusCode(200);
        assertThat(newestSucceeded("replay.probe.live"))
            .as("a replay that got a 200 back must not be recorded as a failure")
            .isTrue();
    }

    /** The pre-existing tenant-in-URL address: the platform sibling shares its service, but this
     *  one's URL and response shape must never move underneath its callers. No test exercised it
     *  before this one — the "extraction moved nothing" claim had no net. */
    @Test
    void the_old_tenant_in_url_replay_address_still_works() {
        assertThat(attemptCount("replay.probe.mine")).isEqualTo(1);
        given().contentType("application/json")
            .headers(TestIdentity.tenantAndUser(1))
            .when().post("/api/tenants/1/integrations/" + mineEndpoint
                + "/deliveries/" + mine + "/replay")
            .then().statusCode(200)
            .body("$", hasKey("ok"))
            .body("$", hasKey("httpStatus"))
            .body("ok", equalTo(false));
        assertThat(attemptCount("replay.probe.mine"))
            .as("the old tenant-in-URL address must also record a new attempt, never overwrite")
            .isEqualTo(2);
    }
}
