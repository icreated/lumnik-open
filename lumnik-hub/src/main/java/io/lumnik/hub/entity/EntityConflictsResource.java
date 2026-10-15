package io.lumnik.hub.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * The aggregated conflict ledger: every fused-entity row with a source conflict or a lookup ambiguity, across
 * all entities the caller may read. Read-only on the default (RLS-enforced) datasource; scope
 * rule identical to the unit read, but the aggregate SKIPS a non-granted entity silently —
 * it shows what you are allowed to see, it never refuses. A MISSING compiled view (e.g. a
 * connector-delete cascade that outlived the manifest) is probed for BEFORE it is queried
 * and, if absent, logged and skipped. The probe answers existence only: a view that exists but
 * is unreadable (rights revoked, dependency broken) still fails at query time; {@link #list(int)}
 * catches that and skips the entity. Each entity is read in its OWN short transaction, because a
 * failed statement on Postgres aborts the transaction it runs in (#929). Only that read and its
 * commit are skipped: any other failure, the probe's included, surfaces, so a database that cannot
 * answer is a 500 and never an empty "no conflicts".
 *
 * <p>Performance posture (spec 2026-08-13, amended): the compiled view's heavy jsonb_agg is
 * already conditional, so the filter is the jsonb-native {@code _conflicts <> '{}'::jsonb}
 * (no per-row text serialization); the remaining global limit is pushed into each per-entity
 * query; a local statement_timeout bounds a scan over a frozen giant.
 */
@Path("/api/entities/conflicts")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_user")
public class EntityConflictsResource {

  private static final Logger LOG = Logger.getLogger(EntityConflictsResource.class);

  static final int STATEMENT_TIMEOUT_MS = 5000;

  @Inject EntityRegistry registry;
  @Inject EntityManager em;

  @Inject ObjectMapper mapper;   // the Quarkus-managed one: one JSON behavior app-wide

  @GET
  public List<Map<String, Object>> list(@QueryParam("limit") @DefaultValue("100") int limit) {
    int budget = Math.min(Math.max(limit, 1), 1000);
    List<Map<String, Object>> out = new ArrayList<>();
    for (EntityManifest m : registry.all()) {
      if (out.size() >= budget) break;
      if (!ScopeGrants.allowsAny(m.scopes())) continue; // silent skip BEFORE any DB touch
      AtomicBoolean readDone = new AtomicBoolean();
      try {
        // Its own transaction is also what binds the RLS tenant (SET LOCAL) for this read.
        out.addAll(QuarkusTransaction.requiringNew().call(() -> {
          List<Map<String, Object>> rows = read(m, budget - out.size());
          readDone.set(true);
          return rows;
        }));
      } catch (UnreadableView e) {
        LOG.warnf(e.getCause(), "entity conflicts: skipping '%s' — compiled view unreadable", m.name());
      } catch (QuarkusTransactionException e) {
        // QuarkusTransaction rethrows the read's own exceptions as-is but wraps what commit()
        // throws, and also a failed begin or rollback: only a failure after the read completed is
        // this entity's commit.
        if (!readDone.get()) throw e;
        LOG.warnf(e, "entity conflicts: skipping '%s' — compiled view unreadable", m.name());
      }
    }
    return out;
  }

  /** One entity's disputed rows, inside the transaction {@link #list(int)} opened for it. */
  private List<Map<String, Object>> read(EntityManifest m, int remaining) {
    if (!viewExists(m.name())) {
      LOG.warnf("entity conflicts: skipping '%s' — compiled view missing", m.name());
      return List.of();
    }
    setStatementTimeout();
    try {
      return collect(m, remaining);
    } catch (PersistenceException e) {
      throw new UnreadableView(e);
    }
  }

  /**
   * This entity's view failed to read (rights revoked, dependency broken, the statement timeout): the
   * one database failure, with its commit, that the aggregate skips (#929). A programming error is
   * not a {@link PersistenceException} and surfaces (#890).
   */
  private static final class UnreadableView extends RuntimeException {
    UnreadableView(PersistenceException cause) {
      super(cause);
    }
  }

  /** Bare, unquoted view table name for the {@code information_schema.views} probe — NOT {@link EntityViewCompiler#viewName}, which returns the quoted, schema-qualified form for use in SQL. */
  private static String bareViewName(String entityName) {
    return "v_" + entityName.toLowerCase(Locale.ROOT);
  }

  /** True when the compiled view exists right now, so a missing view is logged as missing, not as unreadable. */
  private boolean viewExists(String entityName) {
    return !em.createNativeQuery(
            "SELECT 1 FROM information_schema.views WHERE table_schema = 'connector' AND table_name = ?1")
        .setParameter(1, bareViewName(entityName))
        .getResultList()
        .isEmpty();
  }

  /** Runs the per-entity fused query; the view is expected to exist (probed by the caller). */
  private List<Map<String, Object>> collect(EntityManifest m, int remaining) {
    // A resolution: entity declares no match: columns; its view groups by the identity it exposes, or, for a
    // row nothing has resolved yet, by its source handle (#928).
    List<String> keys = m.resolution() != null
        ? List.of("coalesce(" + quoteIdent("_entity_id") + "::text, " + quoteIdent("_handle") + ")")
        : m.match().stream().map(c -> quoteIdent(c)).toList();
    StringBuilder sql = new StringBuilder("SELECT ");
    for (String k : keys) sql.append(k).append("::text, ");
    sql.append(quoteIdent("_conflicts")).append("::text FROM ")
       .append(EntityViewCompiler.viewName(m.name()))
       .append(" WHERE ").append(quoteIdent("_conflicts")).append(" <> '{}'::jsonb ORDER BY ")
       .append(String.join(", ", keys));
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object r : em.createNativeQuery(sql.toString()).setMaxResults(remaining).getResultList()) {
      Object[] arr = (Object[]) r; // always >= 2 selected columns (key + _conflicts)
      out.add(row(m.name(), keys.size(), arr));
    }
    return out;
  }

  /** SET LOCAL is transaction-scoped; issued once per entity transaction, and only for an entity actually scanned. */
  private void setStatementTimeout() {
    em.runWithConnection((Connection c) -> {
      try (var st = c.createStatement()) {
        st.execute("SET LOCAL statement_timeout = " + STATEMENT_TIMEOUT_MS);
      }
    });
  }

  private Map<String, Object> row(String entity, int keyCount, Object[] arr) {
    StringBuilder key = new StringBuilder();
    for (int i = 0; i < keyCount; i++) {
      if (i > 0) key.append('/');
      key.append(arr[i]);
    }
    JsonNode conflicts;
    try {
      conflicts = mapper.readTree((String) arr[keyCount]);
    } catch (Exception e) {
      throw new RuntimeException("EntityConflictsResource: unreadable _conflicts ledger for '" + entity + "': " + e, e);
    }
    List<String> columns = new ArrayList<>();
    conflicts.fieldNames().forEachRemaining(columns::add);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("entity", entity);
    // A match: key can be null: JSON null, never the text "null". A resolution: key never is (its handle stands in).
    row.put("key", arr[0] == null ? null : key.toString());
    row.put("columns", columns);
    row.put("conflicts", conflicts);
    return row;
  }
}
