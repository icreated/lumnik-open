package io.lumnik.hub.api.event;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class EventStreamResourceTest {

  @TestHTTPResource("/api/platform/events/stream")
  URL streamUrl;

  /**
   * Headers only, deliberately: an SSE stream never ends, so any client that buffers the
   * body (RestAssured's validation-failure logging does) hangs until its read timeout.
   */
  @Test
  void sse_endpoint_responds_with_event_stream_content_type() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(streamUrl + "?topic=connector.run.*"))
        .header("X-Tenant-Id", "1")
        .header("X-User-Id", "test")
        .GET()
        .build();
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream body = response.body()) {
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().firstValue("Content-Type").orElse(""))
          .startsWith("text/event-stream");
    }
  }
}
