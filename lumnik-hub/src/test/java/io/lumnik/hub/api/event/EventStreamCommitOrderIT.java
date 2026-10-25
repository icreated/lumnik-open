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
 * The live tail must not lose an event a long transaction commits late (#1570).
 *
 * <p>An event's id is assigned at INSERT and becomes visible at COMMIT. The probe holds a transaction
 * open with the LOWER id, commits a HIGHER one, gives the stream two ticks to read it — an id cursor
 * moves past the lower id there — then commits the first. An id cursor never shows it; a cursor that
 * reads only what is decided shows both, each once.
 */
@QuarkusTest
class EventStreamCommitOrderIT {

  @TestHTTPResource("/api/platform/events/stream")
  URL streamUrl;

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Test
  void an_event_a_long_transaction_commits_late_is_shown() throws Exception {
    String nonce = Long.toString(System.nanoTime());
    String late = "SSE-LATE-" + nonce;
    String early = "SSE-EARLY-" + nonce;

    HttpRequest request = HttpRequest.newBuilder(URI.create(streamUrl + "?topic=connector.run.*"))
        .headers(TestIdentity.TENANT_HEADER, "1", TestIdentity.USER_HEADER, "1")
        .GET().build();
    HttpResponse<InputStream> response = HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofInputStream());
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

    try (Connection open = adminDs.getConnection()) {
      open.setAutoCommit(false);
      insert(open, late);            // the lower id, not yet visible

      try (Connection c = adminDs.getConnection()) {
        insert(c, early);            // the higher id, committed at once (autocommit)
      }
      // Two ticks: an id cursor reads `early` and moves past `late`'s id here.
      Thread.sleep(2_500);

      open.commit();                 // `late` becomes visible, below the cursor of an id reader
    }

    try {
      assertThat(waitFor(seen, late, 12_000))
          .as("an event committed after a higher one was read must still be shown").isTrue();
      assertThat(waitFor(seen, early, 12_000)).as("the higher one is shown too").isTrue();
      Thread.sleep(2_500);           // a duplicate would come on a later tick
      synchronized (seen) {
        assertThat(occurrences(seen.toString(), late)).as("shown once").isEqualTo(1);
        assertThat(occurrences(seen.toString(), early)).as("shown once").isEqualTo(1);
      }
    } finally {
      body.close();
      reader.join(2000);
    }
  }

  private static void insert(Connection c, String marker) throws Exception {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO core.event_outbox (tenant_id, event_type, source, payload, occurred_at, created_at) "
            + "VALUES (1, 'connector.run.completed', 'system', ?::jsonb, now(), now())")) {
      ps.setString(1, "{\"marker\":\"" + marker + "\"}");
      ps.executeUpdate();
    }
  }

  private static boolean waitFor(StringBuilder seen, String marker, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      synchronized (seen) { if (seen.indexOf(marker) >= 0) return true; }
      Thread.sleep(100);
    }
    synchronized (seen) { return seen.indexOf(marker) >= 0; }
  }

  private static int occurrences(String text, String marker) {
    int n = 0;
    for (int i = text.indexOf(marker); i >= 0; i = text.indexOf(marker, i + 1)) n++;
    return n;
  }
}
