package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.helpers.Configs;
import io.lumnik.hub.connector.api.helpers.EnvSecrets;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.api.types.*;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialect;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.hub.connector.mapping.MappingEngine;
import io.lumnik.hub.connector.mapping.spec.ErrorPolicy;
import io.lumnik.hub.connector.mapping.spec.HookInvocation;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.mapping.MapColumnsRules;
import io.lumnik.hub.connector.mapping.writer.DynamicTableWriter;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.sql.SqlIdentifiers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

@ConnectorTypeId(JdbcConnectorType.TYPE_ID)
@ApplicationScoped
public class JdbcConnectorType implements ConnectorType {

  public static final String TYPE_ID = "jdbc-generic";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject JdbcDialectRegistry dialectRegistry;
  @Inject HikariPoolRegistry  poolRegistry;
  @Inject MappingEngine       mappingEngine;

  @Override public String id() { return TYPE_ID; }

  @Override
  public WritePerformanceProfile defaultWriteProfile() { return new WritePerformanceProfile.Safe(); }

  @Override
  public Set<TriggerMode> supportedTriggerModes() {
    return Set.of(TriggerMode.MANUAL, TriggerMode.POLL);
  }

  @Override
  public void validateConfig(Map<String, Object> config) {
    // Unified endpoint config keys (connection url/user merged in by the engine).
    Configs.requireString(config, "url");
    Configs.requireString(config, "user");
    // dialect is optional in the manifest — explicit, else inferred from the JDBC url.
    resolveDialectId(config);
    Configs.requireString(config, "schema");
    String name = Configs.requireString(config, "name");
    // validator == runtime: dumpSpec refuses a 'name' that is not a plain identifier before
    // building the t_<name> destination table — refuse it here too, so `lm apply` never accepts
    // a manifest that would only fail mid-run.
    if (!SqlIdentifiers.isPlainIdentifier(name)) {
      throw new ConfigValidationException(
          "config key 'name' must be a plain identifier (letters, digits, underscore; not starting with a digit): " + name);
    }
    String mode = Configs.requireString(config, "mode");
    if (!Set.of("watermark", "full").contains(mode))
      throw new ConfigValidationException("mode must be 'watermark' or 'full'");
    if ("watermark".equals(mode)) Configs.requireString(config, "watermark_column");
    Object pk = config.get("pk_columns");
    if (!(pk instanceof List<?> l) || l.isEmpty())
      throw new ConfigValidationException("pk_columns must be a non-empty list");
    // Dead knob, refused (decision #286): no execution code ever read chunk_size — the engine
    // always reads defaultChunkSize() = 500. Same rule as JdbcConnectorValidator.
    if (config.containsKey("chunk_size"))
      throw new ConfigValidationException(
          "chunk_size is not a knob — jdbc chunking is fixed at 500 rows per read; remove it");
    validateRename(readRename(config));
  }

  @Override
  public ItemReader<?> reader(ConnectorContext ctx) {
    String url      = ctx.configValue("url",      String.class);
    String user     = ctx.configValue("user",     String.class);
    String dialectId = resolveDialectId(ctx.config());
    String schema   = ctx.configValue("schema",   String.class);
    String table    = ctx.configValue("name",     String.class);
    String modeStr  = ctx.configValue("mode",     String.class);
    String watermark = ctx.configValue("watermark_column", String.class, null);
    @SuppressWarnings("unchecked")
    List<String> pk = (List<String>) ctx.config().get("pk_columns");

    // Password comes from a Basic credential.
    // ChunkExecutor.buildContext injects "_credentialId" into the config map
    // from Connector.credentialId when a credential is attached.
    String password = resolvePassword(ctx);

    JdbcDialect dialect = dialectRegistry.resolve(dialectId);
    JdbcChunkReader.Mode mode = "full".equals(modeStr)
        ? JdbcChunkReader.Mode.FULL : JdbcChunkReader.Mode.WATERMARK;

    return new JdbcChunkReader(url, user, password, dialectId, schema, table,
        watermark, pk, mode, dialect, poolRegistry);
  }

  /**
   * Persists the read rows 1:1 into the hub table {@code connector.t_<name>} (auto-schema:
   * columns inferred from the source rows). Without this the engine would only count reads.
   * Writes go through {@link MappingEngine} → {@code MultiTargetWriter} → {@code DynamicTableWriter}
   * (RLS + tenant_id + lineage columns), the same machinery csv/rest use.
   */
  @Override
  public ItemWriter<?> writer(ConnectorContext ctx) {
    String table = ctx.configValue("name", String.class);
    Map<String, String> rename = readRename(ctx.config());
    MappingSpec spec = dumpSpec(table, parseIndexes(ctx.config()), rename);
    // Built through MappingEngine.itemWriter — the seam that reaches the ItemWriter.prepare hook
    // (a bare write-only lambda cannot override it). dumpSpec is Table-only today (no rag target),
    // so this has no observable effect yet, but any future rag-target support in dumpSpec, or a
    // mapping resolved via MappingResolver, hoists its embedding for free through this same seam.
    return mappingEngine.itemWriter(spec, c -> {
      // The reader stashes the source's declared column types on the shared context config
      // (see JdbcChunkReader); they authoritatively type all-NULL-sampled columns at DDL time.
      @SuppressWarnings("unchecked")
      Map<String, String> typeHints =
          (Map<String, String>) c.config().getOrDefault(JdbcChunkReader.SOURCE_COLUMN_TYPES_KEY, Map.of());
      // The map-columns row hook (see dumpSpec) renames a row's keys BEFORE DynamicTableWriter
      // sees it, so SchemaInferrer looks the hint up by the POST-rename name — translate the
      // hint map's keys the same way, or a renamed column's hint never matches.
      return renameHintKeys(typeHints, rename);
    });
  }

  /**
   * Translates {@code hints}' keys through {@code rename} (source column name → hub column
   * name), so a type hint declared for a source column follows it to its renamed hub name. See
   * {@link #writer}: {@link io.lumnik.hub.connector.mapping.writer.SchemaInferrer} looks hints up
   * by the column name it actually sees, which is the POST-rename name.
   */
  static Map<String, String> renameHintKeys(Map<String, String> hints, Map<String, String> rename) {
    if (rename.isEmpty() || hints.isEmpty()) return hints;
    Map<String, String> out = new LinkedHashMap<>(hints.size());
    hints.forEach((col, type) -> out.put(rename.getOrDefault(col, col), type));
    return out;
  }

  @SuppressWarnings("unchecked")
  private static List<MappingTarget.Index> parseIndexes(Map<String, Object> config) {
    Object raw = config.get("indexes");
    if (!(raw instanceof List<?> list)) return List.of();
    var out = new ArrayList<MappingTarget.Index>(list.size());
    for (Object o : list) {
      if (o instanceof Map<?, ?> m && m.get("columns") instanceof List<?> cols && !cols.isEmpty()) {
        out.add(new MappingTarget.Index(
            cols.stream().map(String::valueOf).toList(), Boolean.TRUE.equals(m.get("unique"))));
      }
    }
    return out;
  }

  /**
   * 1:1 dump mapping: one Table target {@code connector.t_<table>} (auto-schema), plus — when
   * {@code rename} is non-empty — one {@code map-columns} row hook translating source column
   * names before the write, so a source column that collides with a hub system column
   * (see {@link DynamicTableWriter#SYSTEM_COLUMNS}) can be renamed instead of refused. Only
   * {@code rename} is exposed on this path, never {@code map-columns}' {@code drop}: a jdbc
   * dump promises 1:1, and dropping a column is a mapping decision, not a connection one.
   *
   * <p>{@link #validateRename} is re-run here, not merely in {@link #validateConfig}: like the
   * {@code name}-identifier check below, {@code validateConfig} has no production caller for
   * jdbc-generic ({@code ChunkExecutor} builds the reader/writer straight off {@code type.reader()}
   * / {@code type.writer()}) — this method is the one the run path actually reaches, via
   * {@link #writer(ConnectorContext)}, so it is the one that must refuse a bad rename before it
   * reaches {@link io.lumnik.hub.connector.mapping.hooks.row.MapColumnsHook}, where a
   * two-sources-one-target collision would silently drop a column's values forever.
   */
  static MappingSpec dumpSpec(String table, List<MappingTarget.Index> indexes, Map<String, String> rename) {
    // DDL-injection boundary (layer 2, alongside the escaping fix in RlsDdl): `table` is prefixed
    // with `t_` and handed straight to DDL that DynamicTableWriter executes on the BYPASSRLS admin
    // datasource, so refuse anything that is not a plain identifier before that happens.
    if (!SqlIdentifiers.isPlainIdentifier(table)) {
      throw new ConfigValidationException(
          "config key 'name' must be a plain identifier (letters, digits, underscore; not starting with a digit): " + table);
    }
    validateRename(rename);
    MappingTarget.Table tbl = new MappingTarget.Table(
        "connector", "t_" + table, /* autoSchema */ true,
        MappingTarget.OnConflict.SKIP, indexes);
    MappingTarget target = new MappingTarget(null, tbl, null, null);
    List<HookInvocation> rowHooks = List.of();
    if (rename != null && !rename.isEmpty()) {
      ObjectNode params = MAPPER.createObjectNode();
      ObjectNode renameNode = params.putObject("rename");
      rename.forEach(renameNode::put);
      rowHooks = List.of(new HookInvocation("map-columns", params));
    }
    return new MappingSpec(
        "jdbc:" + table, 1, target, MappingSpec.StreamMode.STREAM,
        "1:1 dump of " + table,
        List.of(), rowHooks, List.of(), ErrorPolicy.soft());
  }

  /**
   * Refuses a {@code rename} the hub cannot honour. The rules are {@link MapColumnsRules}; this
   * door throws on the FIRST violation, where the two apply-time doors accumulate all of them.
   *
   * <p>The one thing local to here: a source column that happens to BE a system column is not an
   * error — resolving exactly that collision is what this knob exists for. Only a rename INTO one
   * is refused.
   */
  static void validateRename(Map<String, ?> rename) {
    // The rule takes a node; this door is the one that has a Map, so it converts here rather
    // than making the shared rule carry two shapes of the same loop.
    var violations = MapColumnsRules.checkRename(MAPPER.valueToTree(rename));
    if (!violations.isEmpty()) {
      var v = violations.get(0);
      throw new ConfigValidationException("config key '" + v.path() + "' " + v.reason());
    }
  }

  /**
   * Reads the {@code rename} config key: a map of source column name to hub column name (see
   * {@link #dumpSpec}). Absent → {@code Map.of()}. Present but not a map (e.g. a manifest author
   * wrote a list or a scalar) is a config error, not a silent no-op. A null value (a manifest
   * typo — {@code rename:\n  id:} — with a colon and nothing after it) is refused here, not
   * stringified: {@code String.valueOf(null)} would silently produce the literal column name
   * {@code "null"}, which is itself a valid plain identifier.
   */
  private static Map<String, String> readRename(Map<String, Object> config) {
    Object raw = config.get("rename");
    if (raw == null) return Map.of();
    if (!(raw instanceof Map<?, ?> m)) {
      throw new ConfigValidationException("config key 'rename' must be a map of source column name to hub column name");
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : m.entrySet()) {
      if (e.getValue() == null) {
        throw new ConfigValidationException(
            "config key 'rename." + e.getKey() + "' must not be null — a manifest column with a "
            + "colon and nothing after it is not a rename target");
      }
      out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
    }
    return out;
  }

  // -----------------------------------------------------------------------

  /**
   * Extracts the JDBC password from the context.
   *
   * <p>Two sources are supported (in priority order):
   * <ol>
   *   <li>{@code "_credentialId"} in config — a UUID written by {@code ChunkExecutor.buildContext}
   *       from {@code Connector.credentialId}. Resolved via {@link io.lumnik.hub.connector.api.helpers.CredentialResolver}
   *       and cast to {@link ResolvedCredential.Basic} to extract the password.</li>
   *   <li>{@code "password"} in config — plain-text fallback for dev/test environments.</li>
   * </ol>
   */
  private static String resolvePassword(ConnectorContext ctx) {
    Object credId = ctx.config().get("_credentialId");
    if (credId != null && ctx.credentials() != null) {
      ResolvedCredential cred = ctx.credentials().resolve(UUID.fromString(credId.toString()));
      if (cred instanceof ResolvedCredential.Basic b) return b.password();
      throw new ConfigValidationException(
          "jdbc-generic requires a Basic credential; got: " + cred.getClass().getSimpleName());
    }
    // Plain-text password in config (dev/test only).
    String pw = ctx.configValue("password", String.class, "");
    if (!pw.isEmpty()) return pw;
    // Declarative manifest: 'password_env' names an environment variable holding the secret
    // (so it is never committed in the manifest).
    Object envName = ctx.config().get("password_env");
    if (envName instanceof String e && !e.isBlank()) {
      String v = EnvSecrets.lookup(e);
      if (v != null && !v.isEmpty()) return v;
      throw new ConfigValidationException(
          "password_env '" + e + "' is not set in the server environment");
    }
    return "";
  }

  /**
   * Resolves the SQL dialect id: the explicit {@code "dialect"} config key if present, otherwise
   * inferred from the JDBC url. Keeps the declarative manifest's {@code connection} block minimal
   * (it need only carry {@code url}). Only the dialect we actually support (postgres) is inferred;
   * anything else must be declared explicitly.
   */
  static String resolveDialectId(Map<String, Object> config) {
    Object d = config.get("dialect");
    // Registry keys are lowercase; "postgresql" is a validator-accepted alias of "postgres".
    if (d instanceof String s && !s.isBlank()) {
      String v = s.trim().toLowerCase(Locale.ROOT);
      return "postgresql".equals(v) ? "postgres" : v;
    }
    Object url = config.get("url");
    if (url instanceof String u && u.startsWith("jdbc:postgresql:")) return "postgres";
    throw new ConfigValidationException(
        "Missing 'dialect' and could not infer it from url: " + url);
  }
}
