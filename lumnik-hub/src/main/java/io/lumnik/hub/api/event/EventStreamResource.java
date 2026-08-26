package io.lumnik.hub.api.event;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * SSE endpoint that polls core.event_outbox and streams matching events
 * to the TUI or other SSE consumers. Uses the admin datasource (BYPASSRLS)
 * to see events across all tenants.
 *
 * Path: GET /api/platform/events/stream?topic=connector.run.*
 */
@Path("/api/platform/events/stream")
public class EventStreamResource {

  @Inject
  @io.quarkus.agroal.DataSource("admin")
  DataSource dataSource;

  @GET
  @Blocking
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.APPLICATION_JSON)
  public Multi<Map<String, Object>> stream(@QueryParam("topic") List<String> topics) {
    List<Pattern> patterns = (topics == null ? List.<String>of() : topics).stream()
        .map(EventStreamResource::topicToPattern)
        .toList();
    AtomicReference<Instant> lastSeen = new AtomicReference<>(Instant.now());

    return Multi.createFrom().ticks().every(Duration.ofSeconds(1))
        .onOverflow().drop()
        .flatMap(tick -> {
          Instant since = lastSeen.get();
          List<Map<String, Object>> batch = fetchSince(since, patterns);
          if (!batch.isEmpty()) {
            lastSeen.set(Instant.now());
          }
          return Multi.createFrom().iterable(batch);
        });
  }

  private static Pattern topicToPattern(String topic) {
    String regex = topic.replace(".", "\\.").replace("*", ".*");
    return Pattern.compile("^" + regex + "$");
  }

  private List<Map<String, Object>> fetchSince(Instant since, List<Pattern> patterns) {
    List<Map<String, Object>> out = new ArrayList<>();
    // event_outbox schema (V24): id, tenant_id, org_id, event_type, source, entity,
    // entity_key, payload, dedup_key, occurred_at, created_at
    String sql = "SELECT event_type, payload, created_at FROM core.event_outbox " +
                 "WHERE created_at > ? ORDER BY created_at LIMIT 500";
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(since));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String eventType = rs.getString("event_type");
          if (!patterns.isEmpty() && patterns.stream().noneMatch(p -> p.matcher(eventType).matches())) {
            continue;
          }
          out.add(Map.of(
              "topic",      eventType,
              "payload",    rs.getString("payload"),
              "capturedAt", rs.getTimestamp("created_at").toInstant().toString()
          ));
        }
      }
    } catch (java.sql.SQLException e) {
      // table may not exist yet or transient DB error — return empty batch silently
    }
    return out;
  }
}
