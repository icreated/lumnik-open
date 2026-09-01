package io.lumnik.hub.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
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

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * The aggregated conflict ledger: every fused-entity row whose sources still disagree, across
 * all entities the caller may read. Read-only on the default (RLS-enforced) datasource; scope
 * rule identical to the unit read, but the aggregate SKIPS a non-granted entity silently —
 * it shows what you are allowed to see, it never refuses. A MISSING compiled view (e.g. a
 * connector-delete cascade that outlived the manifest) is probed for BEFORE it is queried
 * and, if absent, logged and skipped without touching every other entity's result — a failed
 * query on Postgres aborts the whole transaction, so skipping only AFTER a failure would have
 * silently blanked every entity iterated after the broken one. The probe answers existence
 * only: a view that exists but is unreadable (rights revoked, dependency broken) still fails
 * at query time, and the catch in {@link #list(int)} is that case's backstop — with the
 * cascade caveat documented there.
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
  @Transactional
  public List<Map<String, Object>> list(@QueryParam("limit") @DefaultValue("100") int limit) {
    int budget = Math.min(Math.max(limit, 1), 1000);
    List<Map<String, Object>> out = new ArrayList<>();
    // Quarkus REST resources are singletons — the once-per-transaction flag must be a local,
    // never a field (a field would stay true after the first request and silently drop the
    // timeout protection for every later transaction).
    boolean timeoutSet = false;
    for (EntityManifest m : registry.all()) {
      if (out.size() >= budget) break;
      if (!ScopeGrants.allowsAny(m.scopes())) continue; // silent skip BEFORE any DB touch
      if (!timeoutSet) {
        setStatementTimeout();
        timeoutSet = true;
      }
      // Probe first: on Postgres a failed statement aborts the whole transaction, so letting a
      // missing view reach the data query (and rely on the catch below) would silently blank
      // every entity iterated AFTER it too. A SELECT against information_schema never aborts.
      if (!viewExists(m.name())) {
        LOG.warnf("entity conflicts: skipping '%s' — compiled view missing", m.name());
        continue;
      }
      try {
        collect(m, budget - out.size(), out);
      } catch (Exception e) {
        // TOCTOU backstop only: the view existed at the probe above but vanished (or broke)
        // before this query ran. Rare, but on Postgres this DOES abort the transaction, so in
        // this narrow race every later entity in this loop is lost with it — the probe above is
        // what makes that overwhelmingly unlikely, not this catch.
        LOG.warnf(e, "entity conflicts: skipping '%s' — compiled view unreadable", m.name());
      }
    }
    return out;
  }

  /** Bare, unquoted view table name for the {@code information_schema.views} probe — NOT {@link EntityViewCompiler#viewName}, which returns the quoted, schema-qualified form for use in SQL. */
  private static String bareViewName(String entityName) {
    return "v_" + entityName.toLowerCase(Locale.ROOT);
  }

  /** True when the compiled view exists right now. A 0-row SELECT never aborts the transaction, unlike a failed query against a missing view. */
  private boolean viewExists(String entityName) {
    return !em.createNativeQuery(
            "SELECT 1 FROM information_schema.views WHERE table_schema = 'connector' AND table_name = ?1")
        .setParameter(1, bareViewName(entityName))
        .getResultList()
        .isEmpty();
  }

  /** Runs the per-entity fused query; the view is expected to exist (probed by the caller). */
  private void collect(EntityManifest m, int remaining, List<Map<String, Object>> out) {
    List<String> match = m.match();
    StringBuilder sql = new StringBuilder("SELECT ");
    for (String c : match) sql.append(quoteIdent(c)).append("::text, ");
    sql.append(quoteIdent("_conflicts")).append("::text FROM ")
       .append(EntityViewCompiler.viewName(m.name()))
       .append(" WHERE ").append(quoteIdent("_conflicts")).append(" <> '{}'::jsonb ORDER BY ");
    for (int i = 0; i < match.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append(quoteIdent(match.get(i)));
    }
    for (Object r : em.createNativeQuery(sql.toString()).setMaxResults(remaining).getResultList()) {
      Object[] arr = (Object[]) r; // always >= 2 selected columns (match + _conflicts)
      out.add(row(m.name(), match, arr));
    }
  }

  /** SET LOCAL is transaction-scoped; issued once per request, and only if a view is actually scanned. */
  private void setStatementTimeout() {
    em.runWithConnection((Connection c) -> {
      try (var st = c.createStatement()) {
        st.execute("SET LOCAL statement_timeout = " + STATEMENT_TIMEOUT_MS);
      }
    });
  }

  private Map<String, Object> row(String entity, List<String> match, Object[] arr) {
    StringBuilder key = new StringBuilder();
    for (int i = 0; i < match.size(); i++) {
      if (i > 0) key.append('/');
      key.append(arr[i]);
    }
    JsonNode conflicts;
    try {
      conflicts = mapper.readTree((String) arr[match.size()]);
    } catch (Exception e) {
      throw new RuntimeException("EntityConflictsResource: unreadable _conflicts ledger for '" + entity + "': " + e, e);
    }
    List<String> columns = new ArrayList<>();
    conflicts.fieldNames().forEachRemaining(columns::add);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("entity", entity);
    row.put("key", key.toString());
    row.put("columns", columns);
    row.put("conflicts", conflicts);
    return row;
  }
}
