package io.lumnik.hub.api.event;

import io.quarkus.test.junit.QuarkusTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class EventStreamResourceTest {

  @Test
  void sse_endpoint_responds_with_event_stream_content_type() {
    given()
      .header("X-Tenant-Id", "1")
      .header("X-User-Id", "test")
      .queryParam("topic", "connector.run.*")
      .when().get("/api/platform/events/stream")
      .then()
        .statusCode(200)
        .contentType(Matchers.startsWith("text/event-stream"));
  }
}
