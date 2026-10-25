package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.sql.TemporalText;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.lumnik.hub.view.ColumnKind;
import io.lumnik.hub.view.ViewFilter;
import io.quarkus.agroal.DataSource;
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
import org.hibernate.Session;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  /** One entity as the list door shows it: what a client needs to pick one and read it (#1114). */
  public record EntitySummary(String name, String label, List<String> scopes, List<String> sources,
                              boolean resolution, List<String> columns) {}

  /**
   * The entities the caller may read — the same {@code allowsAny} rule as {@link #read}, so a bound
   * caller lists only what its scopes reach. Without it a client had to know a name to read one
   * ({@code :ambiguous} asked the user to type it).
   */
  @GET
  public List<EntitySummary> list() {
    return registry.all().stream()
        .filter(m -> ScopeGrants.allowsAny(m.scopes()))
        .map(m -> new EntitySummary(m.name(), m.label(), m.scopes(),
            m.sources().stream().map(EntityManifest.Source::table).toList(),
            m.resolution() != null, List.copyOf(m.canonicalColumns())))
        .toList();
  }

  /** The technical columns a resolution view keys its rows by (EntityViewCompiler, #850/#928). */
  private static final List<String> IDENTITY_COLUMNS = List.of("_entity_id", "_resolution", "_handle");

  /** One equality filter carried from the query string, already checked against the canonical columns. */
  record ColumnFilter(String column, String value) {}

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
                       @QueryParam("offset") @DefaultValue("0") int offset,
                       @QueryParam("identity") @DefaultValue("false") boolean identity) {
    EntityManifest m = registry.find(name);
    if (m == null) return ApiErrors.notFound("entity " + name + " not found");

    if (!ScopeGrants.allowsAny(m.scopes())) {
      return ApiErrors.scopeNotGranted();
    }

    List<String> cols = new ArrayList<>(m.canonicalColumns()); // union of all sources' canonical columns
    // A resolution entity's rows also filter by _entity_id: the key a search hit's link names them by.
    List<String> filterable = new ArrayList<>(cols);
    if (m.resolution() != null) filterable.add("_entity_id");
    List<ColumnFilter> filters = ApiErrors.orBadRequest(() -> filtersFrom(uriInfo, m.name(), filterable));

    // ?identity=true (#1116): a resolution entity's rows also name their identity — _handle is the
    // SOURCE:ID an assertion takes. Opt-in, so the default read every client knows stays
    // canonical columns + ledger. A match: entity has no identity columns: the flag changes nothing.
    List<String> extra = identity && m.resolution() != null ? IDENTITY_COLUMNS : List.of();
    // A column the sources could not type is text, and PostgreSQL writes a timestamp as text in the
    // session's zone, the hub JVM's; a date operand is read in it too: read in UTC, as a view does
    // (#1304, #1323).
    em.unwrap(Session.class).doWork(TemporalText::utcSession);
    Query q = em.createNativeQuery(selectSql(m, cols, extra, filters, filters.isEmpty() ? Map.of()
        : em.unwrap(Session.class).doReturningWork(c -> EntityViewCompiler.viewColumnKinds(c, m.name()))));
    for (int i = 0; i < filters.size(); i++) q.setParameter(i + 1, filters.get(i).value());
    q.setMaxResults(Math.min(Math.max(limit, 1), 1000));
    q.setFirstResult(Math.max(offset, 0));

    List<Map<String, Object>> rows = toRows(q.getResultList(), cols, extra);
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

  /**
   * Query params besides the paging controls and {@code identity}, each naming a filterable column. Any
   * other key (including {@code _conflicts}) is refused: ignoring it answered every row under a filter
   * that selected none of them (#1519).
   */
  private static List<ColumnFilter> filtersFrom(UriInfo uriInfo, String entity, List<String> filterable) {
    List<ColumnFilter> filters = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : uriInfo.getQueryParameters().entrySet()) {
      String key = e.getKey();
      if ("limit".equals(key) || "offset".equals(key) || "identity".equals(key)) continue;
      if (!filterable.contains(key)) {
        throw new IllegalArgumentException("filter " + key + " is not a column of " + entity
            + "; filterable columns: " + String.join(", ", filterable));
      }
      List<String> v = e.getValue();
      if (v == null || v.isEmpty()) continue;
      filters.add(new ColumnFilter(key, v.get(0)));
    }
    return filters;
  }

  /**
   * The canonical columns [plus the identity columns] plus the fusion ledger, filtered by bound
   * parameters, ordered by every canonical column — then by {@code _entity_id, _handle} on an identity read:
   * canonical values need not be unique, and offset paging only holds over a total order.
   */
  static String selectSql(EntityManifest m, List<String> cols, List<String> extra,
                                  List<ColumnFilter> filters, Map<String, ColumnKind> typed) {
    StringBuilder sql = new StringBuilder("SELECT ");
    for (int i = 0; i < cols.size(); i++) {
      if (i > 0) sql.append(", ");
      sql.append(quoteIdent(cols.get(i)));
    }
    for (String x : extra) sql.append(", ").append(quoteIdent(x)).append("::text");
    sql.append(", ").append(quoteIdent("_conflicts")).append("::text"); // fusion ledger
    sql.append(" FROM ").append(EntityViewCompiler.viewName(m.name()));
    for (int i = 0; i < filters.size(); i++) {
      sql.append(i == 0 ? " WHERE " : " AND ");
      ColumnFilter f = filters.get(i);
      // A date against a date or a timestamp column is equal as an instant, a date-only one being its
      // midnight UTC — the view's `=` (#1310, #1325); a number against a number column by value, 1500.00 being
      // 1500 (#1338); anything else is the exact text.
      ColumnKind kind = typed.getOrDefault(f.column(), ColumnKind.TEXT);
      if (kind.temporal() && ViewFilter.isTemporal(f.value())) {
        sql.append(quoteIdent(f.column())).append("::timestamptz = CAST(?").append(i + 1).append(" AS timestamptz)");
      } else if (kind.number() && ViewFilter.isNumeric(f.value())) {
        sql.append(quoteIdent(f.column())).append("::numeric = CAST(?").append(i + 1).append(" AS numeric)");
      } else {
        sql.append(quoteIdent(f.column())).append("::text = ?").append(i + 1);
      }
    }
    for (int i = 0; i < cols.size(); i++) {
      sql.append(i == 0 ? " ORDER BY " : ", ");
      sql.append(quoteIdent(cols.get(i)));
    }
    // A resolved row is one _entity_id (its _handle is NULL), an unresolved one one _handle: together
    // a total order.
    for (String x : extra) {
      if (!"_resolution".equals(x)) sql.append(", ").append(quoteIdent(x));
    }
    return sql.toString();
  }

  private static List<Map<String, Object>> toRows(List<?> rows, List<String> cols, List<String> extra) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object r : rows) {
      Object[] arr = (Object[]) r; // always >= 2 selected columns (canonical [+ identity] + _conflicts)
      Map<String, Object> row = new LinkedHashMap<>();
      for (int i = 0; i < cols.size(); i++) row.put(cols.get(i), TemporalText.entityWire(arr[i]));
      for (int i = 0; i < extra.size(); i++) row.put(extra.get(i), arr[cols.size() + i]);
      row.put("_conflicts", arr[cols.size() + extra.size()]); // JSON text, "{}" when no conflict
      out.add(row);
    }
    return out;
  }
}
