package io.lumnik.hub.api.event;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.api.TenantWide;
import io.lumnik.hub.tenant.RequireTenant;
import io.quarkus.agroal.DataSource;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * SSE endpoint that polls core.event_outbox and streams matching events to the TUI or other SSE
 * consumers. It reads on the admin datasource (BYPASSRLS) — so the query itself must scope by
 * tenant, exactly as its sibling {@code InboxResource} does on the same datasource. The caller's
 * tenant is resolved ONCE on the request thread ({@link RequireTenant#current()}) and bound into
 * every poll: the tick loop runs on worker threads where the tenant ThreadLocal is not set, so it
 * could not resolve it itself.
 *
 * <p>Deliberately tenant-scoped. A cross-tenant, whole-deployment stream is a legitimate future
 * operator capability, but it is the {@code lm_superadmin} shape — the role this product reserves
 * for cross-tenant reads ({@code TenantListResource}). When that is wanted, add a variant gated
 * {@code @RequiresPermission("lm_superadmin")} that passes a cross-tenant marker to {@link
 * #fetchSince}; do NOT widen this per-tenant door, which every {@code lm_integrator} can reach.
 *
 * <p>{@link TenantWide}: the stream carries every métier's events — what a webhook receives, pulled
 * instead of pushed — so a caller bound to a métier is refused it, as the subscriptions are (#1207).
 *
 * Path: GET /api/platform/events/stream?topic=connector.run.*
 */
@TenantWide
@Path("/api/platform/events/stream")
public class EventStreamResource {

  private static final Logger LOG = Logger.getLogger(EventStreamResource.class);

  @Inject
  @DataSource("admin")
  AgroalDataSource dataSource;

  @GET
  @Blocking
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.APPLICATION_JSON)
  public Multi<Map<String, Object>> stream(@QueryParam("topic") List<String> topics) {
    List<Pattern> patterns = (topics == null ? List.<String>of() : topics).stream()
        .map(EventStreamResource::topicToPattern)
        .toList();
    // Resolve on the REQUEST thread: the tick lambda below runs on worker threads where the tenant
    // ThreadLocal is unset, so it cannot resolve it itself. (Why the predicate is the isolation —
    // BYPASSRLS datasource — is on fetchSince, next to the WHERE.)
    long tenant = RequireTenant.current();
    // Start AFTER the events that already exist, so the stream is a LIVE tail (as created_at > now()
    // used to be) — but keyed on id, resolved eagerly here on the request thread so an event arriving
    // before the first tick is still delivered. currentMaxId returns -1 (UNINITIALISED) if it can't
    // resolve — NOT 0, which would be "id > 0" and replay the whole history to a live client.
    AtomicLong lastId = new AtomicLong(currentMaxId(tenant));

    return Multi.createFrom().ticks().every(Duration.ofSeconds(1))
        .onOverflow().drop()
        .flatMap(tick -> {
          long since = lastId.get();
          if (since < 0) {
            // Eager resolve failed (transient DB error): retry it, still tailing from "now", never
            // from 0. Emit nothing until it succeeds, then the next tick tails from that id.
            lastId.set(currentMaxId(tenant));
            return Multi.createFrom().empty();
          }
          Poll poll = fetchSince(tenant, since, patterns);
          lastId.set(poll.cursor());
          return Multi.createFrom().iterable(poll.events());
        });
  }

  /** A poll's matching events and the cursor to resume from — the max id actually scanned. */
  private record Poll(List<Map<String, Object>> events, long cursor) {}

  /**
   * Compile a client-supplied topic glob to an anchored regex. Only {@code *} is a wildcard; every
   * other character — including regex metacharacters like {@code [ ( \ +} an integrator can put in a
   * {@code ?topic=} query — is matched literally. The old {@code replace(".","\\.")} escaped only the
   * dot, so any other metacharacter reached {@code Pattern.compile} and a malformed topic threw a
   * {@code PatternSyntaxException} (a client-triggerable 500) or matched unintendedly. Splitting on
   * {@code *} and {@link Pattern#quote quoting} each literal segment cannot throw.
   */
  static Pattern topicToPattern(String topic) {
    String[] parts = topic.split("\\*", -1);   // -1 keeps trailing empties, so `a*` and `*` work
    StringBuilder regex = new StringBuilder("^");
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) regex.append(".*");           // the `*` that separated parts[i-1] from parts[i]
      if (!parts[i].isEmpty()) regex.append(Pattern.quote(parts[i]));
    }
    return Pattern.compile(regex.append("$").toString());
  }

  /**
   * The current highest event id for the tenant — the live tail's starting point — or {@code -1} if
   * it could not be resolved. The caller treats -1 as UNINITIALISED and retries; it must NOT be 0,
   * which as {@code id > 0} would replay the tenant's whole history to a live subscriber.
   */
  private long currentMaxId(long tenant) {
    try {
      return Jdbc.queryOne(dataSource, "EventStreamResource.currentMaxId",
          "SELECT COALESCE(MAX(id), 0) FROM core.event_outbox WHERE tenant_id = ?",
          ps -> ps.setLong(1, tenant),
          rs -> rs.getLong(1)).orElse(0L);
    } catch (RuntimeException e) {
      // Could not resolve a starting point — return the UNINITIALISED sentinel so the loop retries and
      // keeps tailing from "now", rather than starting from 0 (a full replay) or crashing the
      // subscription. (Jdbc.queryOne rethrows SQL failures as runtime.)
      LOG.debugf(e, "event stream: could not resolve starting id for tenant %d, will retry next tick", tenant);
      return -1L;
    }
  }

  private Poll fetchSince(long tenant, long sinceId, List<Pattern> patterns) {
    List<Map<String, Object>> out = new ArrayList<>();
    // event_outbox schema (V24): id, tenant_id, org_id, event_type, source, entity, entity_key,
    // payload, dedup_key, occurred_at, created_at. Cursor by id, not created_at: created_at is the
    // TRANSACTION time (now()), so a whole transaction's rows share it and a wall-clock cursor that
    // reached that instant would skip the tied siblings forever. id is BIGSERIAL — unique and
    // monotonic — the same key the durable spine uses (core.event_consumer.last_event_id), and it
    // rides V36's (tenant_id, id DESC) index. tenant_id FIRST: this runs on the admin (BYPASSRLS)
    // datasource, so the predicate — not RLS — is what isolates tenants.
    //
    // Best-effort by nature: id is assigned at INSERT but a row becomes visible at COMMIT, so a long
    // transaction can commit a lower id after a higher one, and a live poll may miss the late one.
    // That is acceptable for a LIVE view (the TUI); DURABLE delivery is the consumer-position model's
    // job, not this tail's — closing the commit-order window here (overlap + dedup) is a separate lot.
    String sql = "SELECT id, event_type, payload, created_at FROM core.event_outbox " +
                 "WHERE tenant_id = ? AND id > ? ORDER BY id LIMIT 500";
    long maxId = sinceId;
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, tenant);
      ps.setLong(2, sinceId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          long id = rs.getLong("id");
          if (id > maxId) maxId = id;   // advance over EVERY scanned row, matching or not
          String eventType = rs.getString("event_type");
          if (!patterns.isEmpty() && patterns.stream().noneMatch(p -> p.matcher(eventType).matches())) {
            continue;
          }
          out.add(Map.of(
              "topic",      eventType,
              "payload",    rs.getString("payload"),
              "capturedAt", Jdbc.instant(rs, "created_at").toString()
          ));
        }
      }
    } catch (SQLException e) {
      // Transient DB error — return an empty batch and DON'T advance the cursor (maxId stays
      // sinceId), so the next tick retries the same window. Logged at debug so a persistent failure
      // is diagnosable without spamming a line every second, and without crashing the stream.
      LOG.debugf(e, "event stream: poll failed for tenant %d, empty batch (cursor held at %d)", tenant, sinceId);
      return new Poll(List.of(), sinceId);
    }
    return new Poll(out, maxId);
  }
}
