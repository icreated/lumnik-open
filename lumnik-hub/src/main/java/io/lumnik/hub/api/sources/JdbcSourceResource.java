package io.lumnik.hub.api.sources;

import io.lumnik.jdbc.spi.ColumnDescriptor;
import io.lumnik.hub.sql.SqlIdentifiers;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.sources.JdbcSourceDtos.*;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorCredential;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.jdbc.HikariPoolRegistry;
import io.lumnik.hub.connector.jdbc.JdbcConnectionConfig;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.hub.connector.jdbc.JdbcConnectorType;
import io.lumnik.hub.connector.jdbc.SchemaIntrospector;
import io.lumnik.hub.connector.jdbc.TableDescriptor;
import io.lumnik.hub.connector.repository.ConnectorCredentialRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.lumnik.hub.webhook.SecretCipher;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.*;
import java.util.stream.Collectors;

@Path("/api/platform/sources/jdbc")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
public class JdbcSourceResource {

  private static final Logger LOG = Logger.getLogger(JdbcSourceResource.class);

  @Inject SchemaIntrospector introspector;
  @Inject ConnectorRepository connectorRepo;
  @Inject ConnectorCredentialRepository credentialRepo;
  @Inject ConnectorRunRepository runRepo;
  @Inject SecretCipher cipher;
  @Inject HikariPoolRegistry poolRegistry;
  @Inject JdbcDialectRegistry dialectRegistry;

  // ── POST /test ──────────────────────────────────────────────────────────────

  @POST
  @Path("/test")
  public Response test(TestRequest req) {
    long start = System.currentTimeMillis();
    JdbcConnectionConfig cc;
    try {
      cc = JdbcConnectionConfig.parse(req.url(), req.user(), req.password(), dialectRegistry);
    } catch (IllegalArgumentException e) {
      return Response.status(400).entity(Map.of("ok", false, ApiErrors.KEY, e.getMessage())).build();
    }

    try {
      DataSource ds = poolRegistry.acquire(cc.url(), cc.user(), cc.password(), -1L,
          dialectRegistry.resolve(cc.dialectId()));
      List<String> schemas = new ArrayList<>();
      String dbVersion;
      try (Connection conn = ds.getConnection()) {
        DatabaseMetaData meta = conn.getMetaData();
        dbVersion = meta.getDatabaseProductVersion();
        // Collect schemas visible to this user
        try (var rs = meta.getSchemas()) {
          while (rs.next()) schemas.add(rs.getString(1));
        }
      }
      long latency = System.currentTimeMillis() - start;
      return Response.ok(new TestResponse(true, cc.dialectId(), dbVersion, latency, schemas)).build();
    } catch (Exception e) {
      LOG.warnf("JDBC test failed for %s: %s", req.url(), e.getMessage());
      return Response.status(502)
          .entity(new TestResponse(false, cc.dialectId(), null, System.currentTimeMillis() - start, List.of()))
          .build();
    }
  }

  // ── POST /discover ──────────────────────────────────────────────────────────

  @POST
  @Path("/discover")
  public Response discover(DiscoverRequest req) {
    JdbcConnectionConfig cc;
    try {
      cc = JdbcConnectionConfig.parse(req.url(), req.user(), req.password(), dialectRegistry);
    } catch (IllegalArgumentException e) {
      return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
    }
    String schema = req.schemaOrDefault();
    try {
      List<TableDescriptor> tables = introspector.discover(
          cc, schema, req.skipPatterns(), req.includeViews());
      return Response.ok(new DiscoverResponse(tables)).build();
    } catch (Exception e) {
      LOG.errorf(e, "Discovery failed for %s/%s", req.url(), schema);
      return Response.status(502).entity(ApiErrors.error(e.getMessage())).build();
    }
  }

  // ── POST /add ───────────────────────────────────────────────────────────────

  @POST
  @Path("/add")
  public Response add(AddRequest req) {
    Long tenantId = Objects.requireNonNullElse(TenantContextFilter.current(), 0L);

    JdbcConnectionConfig cc;
    try {
      cc = JdbcConnectionConfig.parse(req.url(), req.user(), req.password(), dialectRegistry);
    } catch (IllegalArgumentException e) {
      return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
    }

    // No transaction spans this method: introspection is several round trips to the customer's
    // catalog per table, and lm's interactive picker sends every picked table at once. The hub is
    // read here and written at the end, each in a short transaction of its own (the tenant is
    // bound with SET LOCAL, which is transaction-scoped), and the introspection runs between them
    // holding no connection. Checked first so an existing connector's table is never introspected.
    Map<String, Connector> existingByName =
        QuarkusTransaction.requiringNew().call(() -> existingConnectors(req, tenantId));

    // Every table is introspected and checked BEFORE anything is persisted: a refusal names its
    // table and leaves nothing behind.
    List<PreparedTable> prepared = new ArrayList<>();
    for (AddTableRequest tableReq : req.tables()) {
      String connectorName = connectorName(req, tableReq);
      Connector existing = existingByName.get(connectorName);
      if (existing != null) {                      // idempotent on the connector name
        prepared.add(new PreparedTable(tableReq, connectorName, existing, null, null));
        continue;
      }
      try {
        TableDescriptor td = introspector.describe(cc, req.schemaOrDefault(), tableReq.table());
        prepared.add(new PreparedTable(tableReq, connectorName, null, td, buildConnectorConfig(req, cc, td, tableReq)));
      } catch (ConfigValidationException refused) {
        return Response.status(400).entity(ApiErrors.error(refused.getMessage())).build();
      } catch (Exception e) {
        LOG.errorf(e, "Failed to describe table %s", tableReq.table());
        return Response.status(502).entity(ApiErrors.error(
            "Failed processing table '" + tableReq.table() + "': " + e.getMessage())).build();
      }
    }

    return QuarkusTransaction.requiringNew().call(() -> persistPrepared(prepared, req, tenantId));
  }

  /** The connectors this add's tables already have, by name — one query for the whole request. */
  private Map<String, Connector> existingConnectors(AddRequest req, Long tenantId) {
    List<String> names = req.tables().stream().map(t -> connectorName(req, t)).toList();
    if (names.isEmpty()) return Map.of();
    return connectorRepo.find("name IN ?1 AND tenantId = ?2", names, tenantId).list().stream()
        .collect(Collectors.toMap(Connector::getName, c -> c));   // UNIQUE (tenant_id, name)
  }

  private static String connectorName(AddRequest req, AddTableRequest tableReq) {
    return req.sourceName() + "--" + tableReq.table();
  }

  /** The credential and every new connector, written inside the caller's short transaction. */
  private Response persistPrepared(List<PreparedTable> prepared, AddRequest req, Long tenantId) {
    UUID credentialId = findOrCreateCredential(req, tenantId);
    List<AddResultEntry> created = new ArrayList<>();
    List<AddResultEntry> skipped = new ArrayList<>();
    for (PreparedTable p : prepared) {
      if (p.existing() != null) {
        skipped.add(new AddResultEntry(p.existing().getId(), p.existing().getName(), "skipped"));
        continue;
      }
      try {
        created.add(persistTable(p, req, credentialId, tenantId));
      } catch (Exception e) {
        LOG.errorf(e, "Failed to add connector for table %s", p.tableReq().table());
        // Thrown, not returned: the throw rolls back (all or nothing, no half-added source), where
        // setRollbackOnly + return would make QuarkusTransaction's commit fail as a 500.
        throw new WebApplicationException(Response.status(502).type(MediaType.APPLICATION_JSON)
            .entity(ApiErrors.error("Failed processing table '" + p.tableReq().table() + "': " + e.getMessage()))
            .build());
      }
    }
    return Response.ok(new AddResponse(created, skipped)).build();
  }

  /** One requested table after introspection and checks; {@code existing} set when its connector already exists. */
  private record PreparedTable(AddTableRequest tableReq, String connectorName, Connector existing,
                               TableDescriptor td, Map<String, Object> config) {}

  /**
   * The per-(sourceName, tenant) Basic credential, created on first use and otherwise refreshed
   * with the request's user:password — the value this add just introspected with, and the one
   * every later run must authenticate with (a rotated password re-added would otherwise leave the
   * runs on the old secret). Returns its id.
   */
  private UUID findOrCreateCredential(AddRequest req, Long tenantId) {
    String credName = req.sourceName() + "--credential";
    byte[] secret = cipher.encrypt(req.user() + ":" + req.password());
    ConnectorCredential cred = credentialRepo
        .find("name = ?1 AND tenantId = ?2", credName, tenantId)
        .firstResult();
    if (cred == null) {
      cred = new ConnectorCredential();
      cred.setTenantId(tenantId);
      cred.setName(credName);
      cred.setKind("Basic");
      cred.setIv(new byte[0]);
      cred.setCiphertext(secret);
      credentialRepo.persist(cred);
    } else {
      cred.setCiphertext(secret);
    }
    return cred.getId();
  }

  /**
   * Persist the connector for one prepared table.
   *
   * <p>No mapping row is written. The guided add used to persist a {@code connector.connector_mapping}
   * row named {@code <connector>-mapping} and point the connector at it — a row nothing ever read
   * back: the {@code MappingResolver} bound into every {@code ConnectorContext} is an identity stub,
   * and the mapping a jdbc run actually executes is built from the connector's own config by
   * {@link JdbcConnectorType#dumpSpec}. What the row did have was {@code UNIQUE (tenant_id, name)},
   * which {@code connector delete} left behind — so re-adding a deleted table died at COMMIT with an
   * anonymous 500, and no command could remove the orphan.
   */
  private AddResultEntry persistTable(PreparedTable p, AddRequest req,
                                      UUID credentialId, Long tenantId) {
    Connector connector = new Connector();
    connector.setTenantId(tenantId);
    connector.setName(p.connectorName());
    connector.setTypeId(JdbcConnectorType.TYPE_ID);
    connector.setConfig(p.config());
    connector.setCredentialId(credentialId);
    if (req.scheduleCron() != null && !req.scheduleCron().isBlank()) {
      connector.setSchedule(req.scheduleCron());
    }
    connectorRepo.persist(connector);

    return new AddResultEntry(connector.getId(), p.connectorName(), "created");
  }

  /** The connector config for one table, or a refusal naming the table and the reason. */
  private Map<String, Object> buildConnectorConfig(AddRequest req, JdbcConnectionConfig cc,
                                                   TableDescriptor td, AddTableRequest tableReq) {
    String table = td.schema() + "." + td.name();
    if (!SqlIdentifiers.isPlainIdentifier(td.name())) {
      throw new ConfigValidationException("table " + table + ": the name is not a plain identifier (letters, digits, "
          + "underscore; not starting with a digit) — jdbc-generic names its hub table after it; expose a plain-named view");
    }
    if (td.primaryKey().isEmpty()) {
      throw new ConfigValidationException("table " + table
          + " has no primary key — jdbc-generic pages by primary key; declare pk_columns in a manifest instead");
    }
    String watermarkCol = tableReq.watermark() == null || tableReq.watermark().isBlank()
        ? td.suggestedWatermark().orElse(null) : tableReq.watermark();
    String mode = tableReq.mode() == null || tableReq.mode().isBlank()
        ? (watermarkCol == null ? "full" : "watermark") : tableReq.mode();
    if (!"watermark".equals(mode) && !"full".equals(mode)) {
      throw new ConfigValidationException("table " + table + ": mode '" + mode + "' is not one of watermark, full");
    }

    Map<String, Object> config = new LinkedHashMap<>();
    config.put("url", req.url());
    config.put("user", req.user());
    config.put("dialect", cc.dialectId());
    config.put("table-schema", td.schema());
    config.put("table-name", td.name());
    config.put("mode", mode);
    config.put("pk-columns", td.primaryKey());
    if ("watermark".equals(mode)) {
      if (watermarkCol == null) {
        throw new ConfigValidationException("table " + table
            + ": mode watermark needs a watermark column and none was given or could be suggested — use mode full");
      }
      // The runtime quotes identifiers, so the column is persisted as the catalog spells it.
      String column = watermarkCol;
      String catalogName = td.columns().stream().map(ColumnDescriptor::name)
          .filter(column::equalsIgnoreCase).findFirst()
          .orElseThrow(() -> new ConfigValidationException("table " + table + " has no column '" + column + "' to watermark on"));
      config.put("watermark-column", catalogName);
    }
    return config;
  }

  // ── GET /{sourceName}/tables ─────────────────────────────────────────────────

  @GET
  @Path("/{sourceName}/tables")
  @Transactional
  public Response listTables(@PathParam("sourceName") String sourceName) {
    Long tenantId = TenantContextFilter.current();
    if (tenantId == null) tenantId = 0L;

    // List all connectors whose name starts with "{sourceName}--"
    String prefix = sourceName + "--";
    List<Connector> connectors = connectorRepo
        .find("name LIKE ?1 AND tenantId = ?2 AND deletedAt IS NULL",
              prefix + "%", tenantId)
        .list();

    List<ListedTable> tables = new ArrayList<>();
    for (Connector c : connectors) {
      // Extract table name from connector name: "{sourceName}--{table}"
      String table = c.getName().startsWith(prefix)
          ? c.getName().substring(prefix.length())
          : c.getName();

      // Find the most recent run for this connector
      ConnectorRun lastRun = runRepo
          .find("connectorId = ?1 ORDER BY startedAt DESC", c.getId())
          .firstResult();

      String lastRunAt = lastRun != null && lastRun.getEndedAt() != null
          ? lastRun.getEndedAt().toString() : null;
      long rowsIngested = lastRun != null ? lastRun.getRecordsOut() : 0L;
      String status = c.isEnabled() ? "active" : "paused";

      tables.add(new ListedTable(table, c.getName(), c.getId(), lastRunAt, rowsIngested, status));
    }

    return Response.ok(new ListTablesResponse(sourceName, tables)).build();
  }
}
