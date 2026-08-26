package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * Read-only, tenant-isolated access to a canonical entity. Reads go through the default
 * (RLS-enforced) datasource via EntityManager, so the request's app.current_tenant GUC plus
 * the security_invoker view enforce isolation with no per-entity policy.
 */
@Path("/api/entities")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_user")
public class EntityReadResource {

  private static final Logger LOG = Logger.getLogger(EntityReadResource.class);

  @Inject EntityRegistry registry;
  @Inject EntityManager em;
  @Inject @io.quarkus.agroal.DataSource("admin") AgroalDataSource adminDs;

  /** One equality filter carried from the query string, already checked against the canonical columns. */
  private record ColumnFilter(String column, String value) {}

  /**
   * Resolve, authorize, build, execute, map. (S3776: five phases, three of them string-building
   * loops, sharing one method.)
   */
  @GET
  @Path("/{name}")
  @Transactional
  public Response read(@PathParam("name") String name,
                       @Context UriInfo uriInfo,
                       @QueryParam("limit") @DefaultValue("100") int limit,
                       @QueryParam("offset") @DefaultValue("0") int offset) {
    EntityManifest m = registry.find(name);
    if (m == null) return Response.status(Response.Status.NOT_FOUND).build();

    if (!ScopeGrants.allowsAny(m.scopes())) {
      return ApiErrors.scopeNotGranted();
    }

    List<String> cols = new ArrayList<>(m.canonicalColumns()); // union of all sources' canonical columns
    List<ColumnFilter> filters = filtersFrom(uriInfo, new HashSet<>(cols));

    Query q = em.createNativeQuery(selectSql(m, cols, filters));
    for (int i = 0; i < filters.size(); i++) q.setParameter(i + 1, filters.get(i).value());
    q.setMaxResults(Math.min(Math.max(limit, 1), 1000));
    q.setFirstResult(Math.max(offset, 0));

    List<Map<String, Object>> rows = toRows(q.getResultList(), cols);
    recordRead(m); // the read actually happened — audit it. A refused (403/404) read never reaches here.
    return Response.ok(rows).build();
  }

  /**
   * A read is not a JPA event, so {@code AuditEntityListener} cannot see it — this is the one
   * explicit call site for the one case (no annotation, no interceptor, no registry: Kalashnikov
   * holds until there are three of these). Placed after the query has actually run, so a refused
   * read (403 scope not granted, 404 unknown entity) is never recorded as an access that happened.
   * Runs on the admin pool — a separate connection with JTA enlistment disabled, so a broken write
   * here cannot poison the read's own transaction — and any failure is caught and logged, never
   * allowed to fail the read that already succeeded.
   */
  private void recordRead(EntityManifest m) {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO core.audit_outbox "
           + "(tenant_id, actor_id, entity_type, entity_key, operation, changes, created_at) "
           + "VALUES (?, ?, ?, ?, 'READ', '{}'::jsonb, now())")) {
      ps.setLong(1, TenantContextFilter.current());
      Long actorId = TenantContextFilter.currentUser();
      if (actorId != null) ps.setLong(2, actorId); else ps.setNull(2, Types.BIGINT);
      ps.setString(3, m.kind());
      ps.setString(4, m.name());
      ps.executeUpdate();
    } catch (Exception e) {
      // Never let audit failure break the read — same rule as AuditEntityListener. The throwable
      // goes to the logger, not just its message: this path is silent by design, so this line is
      // the only diagnostic anyone will ever get for a trail that quietly stopped recording.
      LOG.warnf(e, "Audit capture skipped for read of entity '%s'", m.name());
    }
  }

  /** Query params that name a canonical column; anything else (including {@code _conflicts}) is ignored. */
  private static List<ColumnFilter> filtersFrom(UriInfo uriInfo, Set<String> allowed) {
    List<ColumnFilter> filters = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : uriInfo.getQueryParameters().entrySet()) {
      String key = e.getKey();
      if ("limit".equals(key) || "offset".equals(key)) continue;
      if (!allowed.contains(key)) continue;
      List<String> v = e.getValue();
      if (v == null || v.isEmpty()) continue;
      filters.add(new ColumnFilter(key, v.get(0)));
    }
    return filters;
  }

  /** The canonical columns plus the fusion ledger, filtered by bound parameters, ordered by every column. */
  private static String selectSql(EntityManifest m, List<String> cols, List<ColumnFilter> filters) {
    StringBuilder sql = new StringBuilder("SELECT ");
    for (int i = 0; i < cols.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append(quoteIdent(cols.get(i)));
    }
    sql.append(", ").append(quoteIdent("_conflicts")).append("::text"); // fusion ledger
    sql.append(" FROM ").append(EntityViewCompiler.viewName(m.name()));
    for (int i = 0; i < filters.size(); i++) {
      sql.append(i == 0 ? " WHERE " : " AND ");
      sql.append(quoteIdent(filters.get(i).column())).append("::text = ?").append(i + 1);
    }
    for (int i = 0; i < cols.size(); i++) {
      sql.append(i == 0 ? " ORDER BY " : ", ");
      sql.append(quoteIdent(cols.get(i)));
    }
    return sql.toString();
  }

  private static List<Map<String, Object>> toRows(List<?> rows, List<String> cols) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object r : rows) {
      Object[] arr = (Object[]) r; // always >= 2 selected columns (canonical + _conflicts)
      Map<String, Object> row = new LinkedHashMap<>();
      for (int i = 0; i < cols.size(); i++) row.put(cols.get(i), arr[i]);
      row.put("_conflicts", arr[cols.size()]); // JSON text, "{}" when no conflict
      out.add(row);
    }
    return out;
  }
}
