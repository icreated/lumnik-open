package io.lumnik.hub.orgs;

import com.sun.net.httpserver.HttpServer;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.lumnik.identity.geo.Coords;
import io.lumnik.identity.geo.Geocoder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.lumnik.hub.webhook.WebhookConsumer;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

@QuarkusTest
@TestProfile(OrgMasterRegistryE2EIT.Profile.class)
class OrgMasterRegistryE2EIT {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // The test drives the webhook consumer manually via the sweeper. Pausing the
            // scheduler means the manual sweep is the only writer of the durable 'webhook'
            // cursor — otherwise the live WebhookConsumer.tick() (5s) would race the manual
            // sweep on the SAME cursor row, making the durable cursor non-deterministic.
            return Map.of(
                "quarkus.scheduler.enabled", "false"
            );
        }
    }

    static HttpServer receiver;
    static AtomicReference<String> lastPayload = new AtomicReference<>();
    static AtomicReference<String> lastSignature = new AtomicReference<>();

    /** Tenant ID created lazily on first @BeforeEach (server is up by then). */
    static final AtomicLong tenantId = new AtomicLong(-1);
    static final AtomicBoolean receiverStarted = new AtomicBoolean(false);

    @InjectMock Geocoder geocoder;
    @Inject OutboxSweeper sweeper;
    @Inject WebhookConsumer webhookConsumer;

    @BeforeEach
    void setup() throws Exception {
        // Start the embedded HTTP receiver once
        if (receiverStarted.compareAndSet(false, true)) {
            receiver = HttpServer.create(new InetSocketAddress(0), 0);
            receiver.createContext("/hook", ex -> {
                byte[] body = ex.getRequestBody().readAllBytes();
                lastPayload.set(new String(body));
                lastSignature.set(ex.getRequestHeaders().getFirst("X-Lumnik-Signature"));
                ex.sendResponseHeaders(200, 0);
                ex.close();
            });
            receiver.start();
        }

        // Create a test tenant so OrgResource.requireTenant() passes (once per test class)
        if (tenantId.get() < 0) {
            JsonPath json = given().contentType("application/json")
                .body("""
                      {"code":"E2E-TENANT","name":"E2E Test Tenant"}""")
            .when().post("/api/tenants")
            .then().statusCode(201)
            .extract().jsonPath();
            tenantId.set(json.getLong("id"));
        }

        TenantContextFilter.CURRENT_TENANT.set(tenantId.get());
        Mockito.when(geocoder.resolve(Mockito.any()))
            .thenReturn(Optional.of(new Coords(new BigDecimal("48.85"), new BigDecimal("2.35"))));
        lastPayload.set(null);
        lastSignature.set(null);
    }

    @AfterAll
    static void stopReceiver() {
        if (receiver != null) receiver.stop(0);
    }

    @Test
    void create_org_triggers_signed_webhook() {
        long tid = tenantId.get();
        int port = receiver.getAddress().getPort();
        given().contentType("application/json")
            .header("X-Tenant-ID", Long.toString(tid))
            .body("""
                  {"name":"e2e-hook","targetUrl":"http://localhost:%d/hook",
                   "platform":"generic","authMode":"HMAC",
                   "secret":"S3CRET","eventTypes":["org.created"]}""".formatted(port))
        .when().post("/api/tenants/" + tid + "/integrations")
        .then().statusCode(201);

        given().contentType("application/json")
            .header("X-Tenant-ID", Long.toString(tid))
            .body("""
                  {"code":"E2E-AG","name":"Agence E2E","city":"Paris","country":"FR","summary":false}""")
        .when().post("/api/tenants/" + tid + "/orgs")
        .then().statusCode(201);

        // Drive the webhook consumer sweep manually (scheduler paused — this is the only cursor writer)
        await().atMost(ofSeconds(20)).pollInterval(ofSeconds(1)).until(() -> {
            try {
                TenantContext.runAsSystem(() -> sweeper.sweep("webhook", 100, webhookConsumer::process));
            } catch (Exception e) {
                // Log but continue polling — the outbox table may not be ready yet
                System.err.println("drainBatch error (will retry): " + e.getMessage());
            }
            return lastPayload.get() != null;
        });

        assertThat(lastPayload.get()).contains("E2E-AG");
        assertThat(lastSignature.get()).startsWith("sha256=");
    }
}
