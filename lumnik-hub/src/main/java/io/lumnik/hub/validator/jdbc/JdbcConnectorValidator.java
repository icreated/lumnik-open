package io.lumnik.hub.validator.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.writer.DynamicTableWriter;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.sql.SqlIdentifiers;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.ConnectorValidator;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationError.Severity;
import io.lumnik.hub.validator.common.CronField;
import io.lumnik.hub.validator.common.TargetBlockValidator;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
@ConnectorTypeId("jdbc-generic")
public class JdbcConnectorValidator implements ConnectorValidator {

    private static final Set<String> SUPPORTED_DIALECTS = Set.of("postgres", "postgresql");
    private static final Pattern URL_PATTERN = Pattern.compile("^jdbc:([a-z0-9]+):.*$");

    @Override public String kind() { return "jdbc-generic"; }

    @Override
    public ConfigDef configDef() {
        return new ConfigDef()
            .requireNested("connection", Importance.HIGH, "JDBC connection",
                ConnectionBlockValidator.configDef())
            .optional("defaults", Type.NESTED, Importance.LOW, "Per-table defaults", null)
            .requireNested("source", Importance.HIGH, "Source tables",
                SourceBlockValidator.configDef())
            .optional("schedule", Type.STRING, Importance.MEDIUM,
                "Cron expression for recurring ingestion", null)
            .requireNested("target", Importance.HIGH, "Target persistence",
                TargetBlockValidator.configDef());
    }

    @Override
    public List<ValidationError> validateCrossField(JsonNode spec) {
        List<ValidationError> errs = new ArrayList<>();
        validateDialect(spec.path("connection"), errs);
        validateTables(spec.path("defaults"), spec.path("source").path("tables"), errs);
        validateSchedule(spec.path("schedule"), errs);
        validateWindow(spec.path("window"), errs);
        validateTags(spec.path("target"), errs);
        return errs;
    }

    /** The URL scheme and the declared dialect must agree and name a dialect v1 supports. */
    private void validateDialect(JsonNode conn, List<ValidationError> errs) {
        String url = conn.path("url").asText();
        if (url.isEmpty()) return;
        Matcher m = URL_PATTERN.matcher(url);
        if (!m.matches()) return;

        String scheme = m.group(1);
        String declared = conn.path("dialect").asText("").toLowerCase();
        String effective = declared.isEmpty() ? scheme : declared;

        if (!SUPPORTED_DIALECTS.contains(effective)) {
            errs.add(new ValidationError("spec.connection.url", Severity.ERROR,
                "unsupported JDBC dialect: " + effective,
                List.of("v1 supports: postgres")));
        } else if (!declared.isEmpty() && !declared.equals(scheme)
                && !(SUPPORTED_DIALECTS.contains(declared) && SUPPORTED_DIALECTS.contains(scheme))) {
            errs.add(new ValidationError("spec.connection.dialect", Severity.ERROR,
                "dialect '" + declared + "' does not match URL scheme '" + scheme + "'",
                List.of()));
        }
    }

    private void validateTables(JsonNode defaults, JsonNode tables, List<ValidationError> errs) {
        if (!tables.isArray()) return;
        if (tables.isEmpty()) {
            errs.add(new ValidationError("spec.source.tables", Severity.ERROR,
                "at least one table is required",
                List.of("Add a table: { name: 'invoices', mode: 'full', pk_columns: ['id'] }")));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < tables.size(); i++) {
            JsonNode merged = TableDefaultsMerger.merge(defaults, tables.get(i));
            validateTable(merged, "spec.source.tables[" + i + "]", seen, errs);
        }
    }

    /** Per-table rules over the defaults-merged node ({@code merged} = defaults + table overrides). */
    private void validateTable(JsonNode merged, String basePath, Set<String> seen, List<ValidationError> errs) {
        errs.addAll(ConfigDefRunner.run(SourceBlockValidator.perTableDef(), merged, basePath));

        String name = merged.path("name").asText();
        if (!name.isEmpty() && !seen.add(name)) {
            errs.add(new ValidationError(basePath + ".name", Severity.ERROR,
                "duplicate table name: " + name, List.of()));
        }

        // Declarative indexes are per-table for jdbc, matching what the runtime parser reads.
        errs.addAll(TargetBlockValidator.validateIndexes(
            merged.path("indexes"), basePath + ".indexes"));

        String mode = merged.path("mode").asText();
        if ("watermark".equals(mode) && merged.path("watermark_column").isMissingNode()) {
            errs.add(new ValidationError(basePath + ".watermark_column", Severity.ERROR,
                "watermark_column is required when mode=watermark",
                List.of("Example: watermark_column: updated_at")));
        }

        JsonNode pk = merged.path("pk_columns");
        if (pk.isMissingNode() || (pk.isArray() && pk.isEmpty())) {
            errs.add(new ValidationError(basePath + ".pk_columns", Severity.ERROR,
                "pk_columns is required and must be non-empty",
                List.of("Example: pk_columns: [id]")));
        }

        validateRename(merged.path("rename"), basePath + ".rename", errs);

        // chunk_size was a dead knob — validated here for months while no execution code ever
        // read it (the engine always reads type.defaultChunkSize() = 500). Refuse it loudly
        // rather than accept-and-ignore, so no manifest can claim a lever that does not exist
        // (decision #286; the CSV validator warns for its dead knob — jdbc refuses because its
        // doc sold the knob as live).
        JsonNode chunk = merged.path("chunk_size");
        if (!chunk.isMissingNode()) {
            errs.add(new ValidationError(basePath + ".chunk_size", Severity.ERROR,
                "chunk_size is not a knob — jdbc chunking is fixed at 500 rows per read; remove it",
                List.of("The engine reads its chunk size from the connector type, never from the manifest")));
        }
    }

    /**
     * {@code rename} maps a source column to a hub column name. The ConfigDef runner already
     * reports a non-object value (case 5 in the tests); this only rules on an actual object.
     */
    private void validateRename(JsonNode rename, String path, List<ValidationError> errs) {
        if (!rename.isObject()) return;

        List<String> hints = List.of("Example: rename: { id: source_id }");
        Set<String> targets = new HashSet<>();
        for (var it = rename.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getValue().isNull()) {
                // A manifest typo — "rename:\n  id:" — parses to a YAML/JSON null, not an empty
                // string. asText() on a NullNode returns the STRING "null", which would then pass
                // isPlainIdentifier unnoticed — refuse it before that stringification happens.
                errs.add(new ValidationError(path, Severity.ERROR,
                    "rename target for '" + entry.getKey() + "' is null — a manifest column with a "
                    + "colon and nothing after it is not a rename target", hints));
                continue;
            }
            String target = entry.getValue().asText();

            if (!SqlIdentifiers.isPlainIdentifier(target)) {
                errs.add(new ValidationError(path, Severity.ERROR,
                    "rename target '" + target + "' is not a valid identifier", hints));
            } else if (DynamicTableWriter.SYSTEM_COLUMNS.contains(target)) {
                errs.add(new ValidationError(path, Severity.ERROR,
                    "rename target '" + target + "' collides with a hub system column", hints));
            } else if (!targets.add(target)) {
                errs.add(new ValidationError(path, Severity.ERROR,
                    "rename target '" + target + "' is used by more than one source column", hints));
            }
        }
    }

    private void validateSchedule(JsonNode sched, List<ValidationError> errs) {
        if (sched.isMissingNode() || sched.isNull()) return;
        CronField.validCron().apply(sched).ifPresent(msg ->
            errs.add(new ValidationError("spec.schedule", Severity.ERROR, msg,
                List.of("Example: schedule: \"*/15 * * * *\" (every 15 minutes)"))));
    }

    private void validateWindow(JsonNode window, List<ValidationError> errs) {
        if (window.isMissingNode() || window.isNull()) return;
        io.lumnik.hub.validator.common.WindowField.validWindow().apply(window).ifPresent(msg ->
            errs.add(new ValidationError("spec.window", Severity.ERROR, msg,
                List.of("Example: window: \"22:00-06:00\" (UTC; scheduled runs are skipped outside it)"))));
    }

    private void validateTags(JsonNode target, List<ValidationError> errs) {
        if (target.path("tags").isMissingNode()) {
            errs.add(new ValidationError("spec.target.tags", Severity.WARNING,
                "no tags defined — RAG scope filtering will be impossible",
                List.of("Add: target.tags: [\"erp\", \"billing\"]")));
        }
    }
}
