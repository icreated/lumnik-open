package io.lumnik.hub.validator.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.hub.connector.mapping.MapColumnsRules;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.ConnectorValidator;
import io.lumnik.hub.validator.JsonNodes;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationError.Severity;
import io.lumnik.hub.validator.common.CronField;
import io.lumnik.hub.validator.common.TargetBlockValidator;
import io.lumnik.hub.validator.common.WindowField;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
@ConnectorTypeId("jdbc-generic")
public class JdbcConnectorValidator implements ConnectorValidator {

    /** The installed dialect providers — the same door the runtime resolves through. */
    @Inject JdbcDialectRegistry dialectRegistry;

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
            // schedule's twin, read by the SHARED apply preamble (ApplyUpsert); declared and
            // checked through WindowField, which owns both wordings for all four connector types.
            .optional("window", Type.STRING, Importance.MEDIUM, WindowField.DOC, null)
            // No `target` block: nothing on this kind reads spec.target — JdbcGenericConnectorApplyHandler
            // never touches it, and a jdbc connector's indexes live on source.tables[].indexes. Declaring it
            // accepted `target.indexes` and dropped it in silence; undeclared, the closed sweep names it
            // with the sentence it already owns, and no code here has to say it twice (#543).
            .closed();
    }

    @Override
    public List<ValidationError> validateCrossField(JsonNode spec) {
        List<ValidationError> errs = new ArrayList<>();
        validateDialect(spec.path("connection"), errs);
        adviseAgainstInlinePassword(spec.path("connection"), errs);
        validateTables(spec.path("defaults"), spec.path("source").path("tables"), errs);
        CronField.check(spec.path("schedule"), errs,
            "Example: schedule: \"*/15 * * * *\" (every 15 minutes)");
        WindowField.check(spec.path("window"), errs);
        return errs;
    }

    /**
     * An inline credential that silently WINS: {@code resolvePassword} in {@code JdbcConnectorType}
     * reads {@code password} before it looks at {@code password_env}, so a manifest that reads safe
     * because it names an env var is overridden by a line above it. The precedence lived only in
     * the field's own description, which nobody reads while editing YAML.
     *
     * <p>A warning, not a refusal: the field is a documented dev/test affordance and refusing it
     * outside a dev profile would break it. What was missing is a mark at the moment it is used —
     * and since #531 that mark survives {@code lm apply}, not just {@code lm validate}.
     *
     * <p>REST's {@code spec.auth.token_value} is the sibling inline credential and carries its own
     * advisory. Overriding a twin is jdbc's alone: there, {@code token_env} is read first.
     */
    private void adviseAgainstInlinePassword(JsonNode conn, List<ValidationError> errs) {
        // blankOrNull, not a bare presence check: resolvePassword skips an empty string and falls
        // through to password_env, so an advisory there would claim a precedence that does not happen.
        if (JsonNodes.blankOrNull(conn.path("password"))) return;
        errs.add(new ValidationError("spec.connection.password", Severity.WARNING,
            "plain-text password in the manifest — it takes precedence over password_env, "
                + "so the secret that env var names is never read",
            List.of("Remove it: password_env already names the env var the hub reads",
                "Keep it only in a manifest that is never committed — dev/test")));
    }

    /**
     * The declared dialect (or, absent one, the URL) must resolve through {@link JdbcDialectRegistry},
     * and its message is surfaced verbatim. A declared dialect that is not installed or does not accept
     * the URL is a {@code spec.connection.dialect} error; an inference that cannot be made — no
     * provider accepts the URL, or several do — is a {@code spec.connection.url} one.
     */
    private void validateDialect(JsonNode conn, List<ValidationError> errs) {
        String url = conn.path("url").asText();
        if (url.isEmpty()) return;
        if (!URL_PATTERN.matcher(url).matches()) return;

        String declared = conn.path("dialect").asText("").trim();
        try {
            dialectRegistry.resolveFor(url, declared.isEmpty() ? null : declared);
        } catch (IllegalArgumentException e) {
            String path = declared.isEmpty() ? "spec.connection.url" : "spec.connection.dialect";
            errs.add(new ValidationError(path, Severity.ERROR, e.getMessage(), List.of()));
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
        Map<String, String> hubTables = new HashMap<>();
        for (int i = 0; i < tables.size(); i++) {
            JsonNode merged = TableDefaultsMerger.merge(defaults, tables.get(i));
            validateTable(merged, "spec.source.tables[" + i + "]", seen, errs);
            // Two tables of one connector landing in one hub table would merge their rows (#1199);
            // two of the same name are already refused above, once.
            String name = merged.path("name").asText();
            String hub = merged.path("target").asText(name);
            String earlier = hub.isEmpty() ? null : hubTables.putIfAbsent(hub, name);
            if (earlier != null && !earlier.equals(name)) {
                errs.add(new ValidationError("spec.source.tables[" + i + "].name", Severity.ERROR,
                    "another table of this connector already lands in connector.t_" + hub,
                    List.of("Name this table's hub table: target: <name>")));
            }
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
     * {@code rename} maps a source column to a hub column name. This door ACCUMULATES — reporting
     * every problem in the block from one {@code lm apply} is the whole reason an apply-time
     * refusal exists next to the run-time one. The rules are {@link MapColumnsRules}.
     */
    private void validateRename(JsonNode rename, String path, List<ValidationError> errs) {
        // Only an actual object, ON PURPOSE. SourceBlockValidator declares `rename` as
        // Type.NESTED, so ConfigDefRunner already refuses a non-object on this same path
        // (jdbc-rename-not-object.yaml pins it) — running the shared rule here too would print
        // two errors for one mistake. The csv door has no such ConfigDef entry, which is why the
        // rule itself still carries the non-object refusal for the door that needs it.
        if (!rename.isObject()) return;

        List<String> hints = List.of("Example: rename: { id: source_id }");
        for (var v : MapColumnsRules.checkRename(rename)) {
            errs.add(new ValidationError(path, Severity.ERROR, v.path() + " " + v.reason(), hints));
        }
    }

}
