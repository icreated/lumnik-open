package io.lumnik.hub.api.sources;

import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.sources.JdbcSourceDtos.*;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorCredential;
import io.lumnik.hub.connector.domain.ConnectorMapping;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.jdbc.JdbcConnectionConfig;
import io.lumnik.hub.connector.jdbc.JdbcConnectorType;
import io.lumnik.hub.connector.jdbc.MappingYamlBuilder;
import io.lumnik.hub.connector.jdbc.SchemaIntrospector;
import io.lumnik.hub.connector.jdbc.TableDescriptor;
import io.lumnik.hub.connector.jdbc.TemplateGenerator;
import io.lumnik.hub.connector.repository.ConnectorCredentialRepository;
import io.lumnik.hub.connector.repository.ConnectorMappingRepository;
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

@Path("/api/platform/sources/jdbc")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
public class JdbcSourceResource {

  private static final Logger LOG = Logger.getLogger(JdbcSourceResource.class);

  @Inject SchemaIntrospector introspector;
  @Inject TemplateGenerator templateGenerator;
  @Inject MappingYamlBuilder yamlBuilder;
  @Inject ConnectorRepository connectorRepo;
  @Inject ConnectorCredentialRepository credentialRepo;
  @Inject ConnectorMappingRepository mappingRepo;
  @Inject ConnectorRunRepository runRepo;
  @Inject SecretCipher cipher;
  @Inject io.lumnik.hub.connector.jdbc.HikariPoolRegistry poolRegistry;

  // ── POST /test ──────────────────────────────────────────────────────────────

  @POST
  @Path("/test")
  public Response test(TestRequest req) {
    long start = System.currentTimeMillis();
    JdbcConnectionConfig cc;
    try {
      cc = JdbcConnectionConfig.parse(req.url(), req.user(), req.password());
    } catch (IllegalArgumentException e) {
      return Response.status(400).entity(Map.of("ok", false, ApiErrors.KEY, e.getMessage())).build();
    }

    try {
      DataSource ds = poolRegistry.acquire(cc.url(), cc.user(), cc.password(), -1L);
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
      cc = JdbcConnectionConfig.parse(req.url(), req.user(), req.password());
    } catch (IllegalArgumentException e) {
      return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
    }
    String schema = req.schema() != null ? req.schema() : "public";
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
  @Transactional
  public Response add(AddRequest req) {
    Long tenantId = TenantContextFilter.current();
    if (tenantId == null) tenantId = 0L;

    JdbcConnectionConfig cc;
    try {
      cc = JdbcConnectionConfig.parse(req.url(), req.user(), req.password());
    } catch (IllegalArgumentException e) {
      return Response.status(400).entity(ApiErrors.error(e.getMessage())).build();
    }

    TemplateGenerator.Mode mode = resolveMode(req.templateMode());
    UUID credentialId = findOrCreateCredential(req, tenantId);

    List<AddResultEntry> created = new ArrayList<>();
    List<AddResultEntry> skipped = new ArrayList<>();

    for (AddTableRequest tableReq : req.tables()) {
      String connectorName = req.sourceName() + "--" + tableReq.table();

      // Idempotency: skip if connector already exists for this tenant
      Connector existing = connectorRepo
          .find("name = ?1 AND tenantId = ?2", connectorName, tenantId)
          .firstResult();
      if (existing != null) {
        skipped.add(new AddResultEntry(existing.getId(), existing.getMappingId(),
            existing.getName(), connectorName + "-mapping", "skipped"));
        continue;
      }

      try {
        created.add(addTable(tableReq, req, cc, mode, credentialId, tenantId, connectorName));
      } catch (Exception e) {
        LOG.errorf(e, "Failed to add connector for table %s", tableReq.table());
        return Response.status(502).entity(ApiErrors.error(
            "Failed processing table '" + tableReq.table() + "': " + e.getMessage())).build();
      }
    }

    return Response.ok(new AddResponse(created, skipped)).build();
  }

  /** Find the per-(sourceName, tenant) Basic credential, creating it on first use. Returns its id. */
  private UUID findOrCreateCredential(AddRequest req, Long tenantId) {
    String credName = req.sourceName() + "--credential";
    ConnectorCredential cred = credentialRepo
        .find("name = ?1 AND tenantId = ?2", credName, tenantId)
        .firstResult();
    if (cred == null) {
      cred = new ConnectorCredential();
      cred.setTenantId(tenantId);
      cred.setName(credName);
      cred.setKind("Basic");
      cred.setCiphertext(cipher.encrypt(req.user() + ":" + req.password()));
      cred.setIv(new byte[0]);
      credentialRepo.persist(cred);
    }
    return cred.getId();
  }

  /** Describe one table, generate its mapping, and persist the mapping + connector. */
  private AddResultEntry addTable(AddTableRequest tableReq, AddRequest req, JdbcConnectionConfig cc,
                                  TemplateGenerator.Mode mode, UUID credentialId, Long tenantId,
                                  String connectorName) {
    TableDescriptor td = introspector.describe(cc, "public", tableReq.table());
    String template = templateGenerator.generate(td, List.of(), mode);

    var spec = yamlBuilder.build(req.sourceName(), td, template);
    String yamlContent = yamlBuilder.dumpYaml(spec);

    String mappingName = connectorName + "-mapping";
    ConnectorMapping mapping = new ConnectorMapping();
    mapping.setTenantId(tenantId);
    mapping.setName(mappingName);
    mapping.setTargetEntity(td.schema() + "." + td.name());
    mapping.setSpecFormat("yaml");
    mapping.setSpecContent(yamlContent);
    mappingRepo.persist(mapping);

    Connector connector = new Connector();
    connector.setTenantId(tenantId);
    connector.setName(connectorName);
    connector.setTypeId(JdbcConnectorType.TYPE_ID);
    connector.setConfig(buildConnectorConfig(req, cc, td, tableReq));
    connector.setCredentialId(credentialId);
    connector.setMappingId(mapping.getId());
    if (req.scheduleCron() != null && !req.scheduleCron().isBlank()) {
      connector.setSchedule(req.scheduleCron());
    }
    connectorRepo.persist(connector);

    return new AddResultEntry(connector.getId(), mapping.getId(), connectorName, mappingName, "created");
  }

  private Map<String, Object> buildConnectorConfig(AddRequest req, JdbcConnectionConfig cc,
                                                   TableDescriptor td, AddTableRequest tableReq) {
    String effectiveMode = tableReq.mode() != null ? tableReq.mode() : "watermark";
    String watermarkCol = tableReq.watermark();
    if (watermarkCol == null || watermarkCol.isBlank()) {
      watermarkCol = td.suggestedWatermark().orElse(null);
    }
    List<String> pk = td.primaryKey().isEmpty() ? List.of("id") : td.primaryKey();

    Map<String, Object> config = new LinkedHashMap<>();
    config.put("url", req.url());
    config.put("user", req.user());
    config.put("dialect", cc.dialectId());
    config.put("table-schema", td.schema());
    config.put("table-name", td.name());
    config.put("mode", effectiveMode);
    config.put("pk-columns", pk);
    if (watermarkCol != null) config.put("watermark-column", watermarkCol);
    config.put("chunk-size", 500);
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

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private static TemplateGenerator.Mode resolveMode(String templateMode) {
    if (templateMode == null) return TemplateGenerator.Mode.HEURISTIC_ONLY;
    return switch (templateMode.toLowerCase(Locale.ROOT)) {
      case "llm-first", "llm_first" -> TemplateGenerator.Mode.LLM_FIRST;
      case "dump", "dump-1-1", "dump_1_1" -> TemplateGenerator.Mode.DUMP_1_1;
      default -> TemplateGenerator.Mode.HEURISTIC_ONLY;
    };
  }
}
