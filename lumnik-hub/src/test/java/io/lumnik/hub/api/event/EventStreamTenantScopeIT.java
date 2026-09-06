package io.lumnik.hub.api.event;

import io.agroal.api.AgroalDataSource;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSE stream reads {@code core.event_outbox} on the admin (BYPASSRLS) datasource. It must
 * return only the CALLER's tenant — like its sibling {@code InboxResource} on the same datasource,
 * and unlike its old self, which streamed every tenant's payloads to any {@code lm_integrator}
 * (a per-tenant role). A tenant-1 caller must see tenant 1's event and never tenant 2's.
 */
@QuarkusTest
class EventStreamTenantScopeIT {

  @TestHTTPResource("/api/platform/events/stream")
  URL streamUrl;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Test
  void stream_returns_only_the_callers_tenant_events() throws Exception {
    String nonce = Long.toString(System.nanoTime());
    String mineMarker = "SSE-MINE-" + nonce;      // tenant 1
    String foreignMarker = "SSE-FOREIGN-" + nonce; // tenant 2

    // Open the stream FIRST as tenant 1 — the endpoint's cursor is created_at > now(), so events
    // must be written AFTER the subscription to be delivered.
    HttpRequest request = HttpRequest.newBuilder(URI.create(streamUrl + "?topic=connector.run.*"))
        .headers(TestIdentity.TENANT_HEADER, "1", TestIdentity.USER_HEADER, "1")
        .GET()
        .build();
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(response.statusCode()).isEqualTo(200);

    InputStream body = response.body();
    StringBuilder seen = new StringBuilder();
    // A background reader drains the never-ending stream into `seen`; the main thread seeds both
    // events, lets several 1s ticks fire, then closes the stream to end the reader. Reading a FIXED
    // window (not breaking on the first marker) is what lets a same-tick foreign leak be caught —
    // both tenants' rows share a created_at and arrive in one batch in no guaranteed order (the
    // query orders by created_at only), so a leak could surface before OR after our own marker.
    Thread reader = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) {
          synchronized (seen) { seen.append(line).append('\n'); }
        }
      } catch (Exception ignored) {
        // stream closed by the main thread — expected end of the read window
      }
    });
    reader.setDaemon(true);
    reader.start();

    seedRunEvent(1L, mineMarker);
    seedRunEvent(2L, foreignMarker);

    Thread.sleep(5000);   // ~5 ticks: ample for both rows (created_at > subscription) to be pushed
    body.close();
    reader.join(2000);

    String out;
    synchronized (seen) { out = seen.toString(); }
    assertThat(out).as("caller's own tenant event is delivered").contains(mineMarker);
    assertThat(out).as("another tenant's event must never appear").doesNotContain(foreignMarker);
  }

  private void seedRunEvent(long tenantId, String marker) throws Exception {
    String sql = "INSERT INTO core.event_outbox "
        + "(tenant_id, event_type, source, payload, occurred_at, created_at) "
        + "VALUES (?, 'connector.run.completed', 'system', ?::jsonb, NOW(), NOW())";
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, tenantId);
      ps.setString(2, "{\"marker\":\"" + marker + "\"}");
      ps.executeUpdate();
    }
  }
}
