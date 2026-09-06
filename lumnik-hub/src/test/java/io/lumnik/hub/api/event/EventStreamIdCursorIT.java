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
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSE cursor must not lose an event that shares a {@code created_at} with one already delivered.
 * {@code core.event_outbox.created_at} is {@code now()} — the TRANSACTION time — so every row written
 * in one transaction carries the same value, and microsecond collisions happen besides. A wall-clock
 * cursor ({@code created_at > since}) that has advanced to that instant then skips the tied sibling
 * forever. An id cursor ({@code id > since}, BIGSERIAL, unique) cannot: it is the spine's own model
 * ({@code core.event_consumer.last_event_id}).
 *
 * <p>The probe sequences two events at the SAME created_at across two ticks: A delivered (cursor
 * advances), THEN B written with A's created_at. On the wall-clock cursor B never arrives; on the id
 * cursor it does.
 */
@QuarkusTest
class EventStreamIdCursorIT {

  @TestHTTPResource("/api/platform/events/stream")
  URL streamUrl;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Test
  void an_event_sharing_a_created_at_with_a_delivered_one_is_not_lost() throws Exception {
    String nonce = Long.toString(System.nanoTime());
    String markerA = "SSE-A-" + nonce;
    String markerB = "SSE-B-" + nonce;

    HttpRequest request = HttpRequest.newBuilder(URI.create(streamUrl + "?topic=connector.run.*"))
        .headers(TestIdentity.TENANT_HEADER, "1", TestIdentity.USER_HEADER, "1")
        .GET().build();
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(response.statusCode()).isEqualTo(200);

    InputStream body = response.body();
    StringBuilder seen = new StringBuilder();
    Thread reader = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) {
          synchronized (seen) { seen.append(line).append('\n'); }
        }
      } catch (Exception ignored) { /* stream closed by main thread */ }
    });
    reader.setDaemon(true);
    reader.start();

    try {
      // The two events share this created_at; only their ids differ. (What the subscription cursors
      // ON is the production question this fixes — id now; the value here just fabricates the tie.)
      // Kept a moment ahead so it clears the tail's starting point regardless of the cursor's shape.
      Timestamp tie = Timestamp.from(Instant.now().plusSeconds(2));

      // A first — wait until it is actually delivered, which means a tick ran and advanced the cursor
      // PAST A before B exists. Event-driven, not a fixed sleep, so a slow CI node cannot miss a tick.
      seed(markerA, tie);
      assertThat(waitFor(seen, markerA, 12_000))
          .as("A (the first at this created_at) must be delivered, advancing the cursor").isTrue();

      // B carries the SAME created_at as the already-delivered A, with a higher id.
      seed(markerB, tie);
      assertThat(waitFor(seen, markerB, 12_000))
          .as("B shares A's created_at — a wall-clock cursor loses it; an id cursor must not")
          .isTrue();
    } finally {
      // Close the stream and join the reader even if an assertion fails, so the connection and the
      // daemon reader thread do not leak into the test JVM.
      body.close();
      reader.join(2000);
    }
  }

  /** Poll the drained stream for a marker, up to a bounded timeout — event-driven, never flaky. */
  private static boolean waitFor(StringBuilder seen, String marker, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      synchronized (seen) { if (seen.indexOf(marker) >= 0) return true; }
      Thread.sleep(100);
    }
    synchronized (seen) { return seen.indexOf(marker) >= 0; }
  }

  private void seed(String marker, Timestamp createdAt) throws Exception {
    String sql = "INSERT INTO core.event_outbox "
        + "(tenant_id, event_type, source, payload, occurred_at, created_at) "
        + "VALUES (1, 'connector.run.completed', 'system', ?::jsonb, ?, ?)";
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, "{\"marker\":\"" + marker + "\"}");
      ps.setTimestamp(2, createdAt);
      ps.setTimestamp(3, createdAt);
      ps.executeUpdate();
    }
  }
}
