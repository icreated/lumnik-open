package io.lumnik.hub.api.event;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.api.TenantWide;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.EventOutboxRow.Position;
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
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>{@link TenantWide}: the stream carries every scope's events — what a webhook receives, pulled
 * instead of pushed — so a caller bound to a scope is refused it, as the subscriptions are (#1207).
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
    // Start at the horizon (#1570), resolved eagerly here on the request thread so an event settling
    // before the first tick is still delivered: what was decided before the subscription is history,
    // what is still settling is shown when it settles — a few events committed a moment earlier may
    // show with it. A null start (UNINITIALISED) means the horizon could not be read: never ORIGIN,
    // which would replay the tenant's whole history to a live client.
    AtomicReference<Position> cursor = new AtomicReference<>(startingPoint());

    return Multi.createFrom().ticks().every(Duration.ofSeconds(1))
        .onOverflow().drop()
        .flatMap(tick -> {
          Position since = cursor.get();
          if (since == null) {
            // Eager resolve failed (transient DB error): retry it, still tailing from "now", never
            // from the origin. Emit nothing until it succeeds, then the next tick tails from there.
            cursor.set(startingPoint());
            return Multi.createFrom().empty();
          }
          Poll poll = fetchSince(tenant, since, patterns);
          cursor.set(poll.cursor());
          return Multi.createFrom().iterable(poll.events());
        });
  }

  /** A poll's matching events and the cursor to resume from — the last row actually scanned. */
  private record Poll(List<Map<String, Object>> events, Position cursor) {}

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
   * The live tail's starting point — every transaction below the horizon is decided — or {@code null}
   * if it could not be resolved. The caller treats null as UNINITIALISED and retries; it must NOT be
   * {@link Position#ORIGIN}, which would replay the tenant's whole history to a live subscriber.
   */
  private Position startingPoint() {
    try {
      return Jdbc.queryOne(dataSource, "EventStreamResource.startingPoint", EventOutboxRow.HORIZON,
              ps -> {}, rs -> new Position(rs.getLong(1), 0)).orElse(null);
    } catch (RuntimeException e) {
      // Could not resolve a starting point — return the UNINITIALISED sentinel so the loop retries and
      // keeps tailing from "now", rather than starting from the origin (a full replay) or crashing the
      // subscription. (Jdbc.queryOne rethrows SQL failures as runtime.)
      LOG.debugf(e, "event stream: could not resolve its starting point, will retry next tick");
      return null;
    }
  }

  private Poll fetchSince(long tenant, Position since, List<Pattern> patterns) {
    List<Map<String, Object>> out = new ArrayList<>();
    // event_outbox schema (V24, V85): id, tenant_id, org_id, event_type, source, entity, entity_key,
    // payload, dedup_key, occurred_at, created_at, xact_id. Never a wall-clock cursor: created_at is
    // the TRANSACTION time (now()), so a whole transaction's rows share it and a cursor that reached
    // that instant would skip the tied siblings forever. tenant_id FIRST: this runs on the admin
    // (BYPASSRLS) datasource, so the predicate — not RLS — is what isolates tenants.
    //
    // id is assigned at INSERT but a row becomes visible at COMMIT, so a long transaction can commit a
    // lower id after a higher one: an id cursor moved past it and the tail never showed it (#1570). The
    // cursor is now (xact_id, id), the order the durable consumers read too, and a poll reads only
    // what is decided (EventOutboxRow.DECIDED) — rides V85's (tenant_id, xact_id, id) index.
    String sql = "SELECT id, xact_id, event_type, payload, created_at FROM core.event_outbox " +
                 "WHERE tenant_id = ? AND " + EventOutboxRow.DECIDED + " AND (xact_id, id) > (?, ?) " +
                 "ORDER BY xact_id, id LIMIT 500";
    Position last = since;
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, tenant);
      ps.setLong(2, since.xact());
      ps.setLong(3, since.id());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          last = new Position(rs.getLong("xact_id"), rs.getLong("id"));  // advance over EVERY scanned row
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
      // Transient DB error — return an empty batch and DON'T advance the cursor, so the next tick
      // retries the same window. Logged at debug so a persistent failure is diagnosable without
      // spamming a line every second, and without crashing the stream.
      LOG.debugf(e, "event stream: poll failed for tenant %d, empty batch (cursor held at %s)", tenant, since);
      return new Poll(List.of(), since);
    }
    return new Poll(out, last);
  }
}
