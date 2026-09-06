package io.lumnik.hub.validator.csv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.lumnik.hub.connector.mapping.MapColumnsRules;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ConnectorValidator;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationError.Severity;
import io.lumnik.hub.validator.common.CronField;
import io.lumnik.hub.validator.common.TargetBlockValidator;
import io.lumnik.hub.validator.common.WindowField;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@ConnectorTypeId("csv-file")
public class CsvFileConnectorValidator implements ConnectorValidator {

    private static final String TARGET = "target";

    @Override public String kind() { return "csv-file"; }

    @Override
    public ConfigDef configDef() {
        return new ConfigDef()
            .requireNested("transport", Importance.HIGH, "Transport block",
                TransportBlockValidator.configDef())
            .requireNested("parser", Importance.HIGH, "CSV parser config",
                ParserBlockValidator.configDef())
            .optional("schedule", Type.STRING, Importance.MEDIUM,
                "Cron expression for recurring ingestion", null)
            .requireEnum("after_process", Importance.MEDIUM,
                "What to do after a file is processed",
                "archive", "delete", "move", "none")
            .optional("archive_path", Type.STRING, Importance.LOW,
                "Destination for `move` action", null)
            .requireNested(TARGET, Importance.HIGH, "Target persistence",
                TargetBlockValidator.configDef());
    }

    /**
     * The cross-field catalogue. Each rule below is independent and self-contained — this method's
     * job is to name them in order, not to hold them. (S3776: the complexity was never tangled, it
     * was a list; extracted so the list reads as one.)
     */
    @Override
    public List<ValidationError> validateCrossField(JsonNode spec) {
        JsonNode tr = spec.path("transport");
        String tKind = tr.path("kind").asText();
        String afterProc = spec.path("after_process").asText();

        List<ValidationError> errs = new ArrayList<>();
        errs.addAll(transportPathRequired(tr, tKind));
        errs.addAll(sftpNeedsACredential(tr, tKind));
        errs.addAll(moveNeedsAnArchivePath(spec, afterProc));
        errs.addAll(localCannotRelocate(tKind, afterProc));
        errs.addAll(oneShotOnlyTransportKeys(tr));
        errs.addAll(headerlessNeedsColumns(spec));
        errs.addAll(scheduleCronIsValid(spec));
        errs.addAll(windowValid(spec));
        errs.addAll(TargetBlockValidator.validateIndexes(
            spec.path(TARGET).path("indexes"), "spec.target.indexes"));
        errs.addAll(aggregatorRefused(spec));
        errs.addAll(validateRowAfterSplitRowRefused(spec));
        errs.addAll(mapColumnsRefused(spec));
        return errs;
    }

    private static List<ValidationError> transportPathRequired(JsonNode tr, String tKind) {
        JsonNode pathNode = tr.path("path");
        boolean pathBlank = pathNode.isMissingNode() || pathNode.isNull()
                || pathNode.asText().isBlank();
        if (!("local".equals(tKind) || "sftp".equals(tKind)) || !pathBlank) return List.of();
        return List.of(new ValidationError("spec.transport.path", Severity.ERROR,
            "path is required for local/sftp transport",
            List.of("Set path: /imports/*.csv (local) or a remote glob (sftp)")));
    }

    private static List<ValidationError> sftpNeedsACredential(JsonNode tr, String tKind) {
        if (!"sftp".equals(tKind)) return List.of();
        boolean hasKey = !tr.path("key_env").isMissingNode();
        boolean hasPwd = !tr.path("password_env").isMissingNode();
        if (hasKey || hasPwd) return List.of();
        return List.of(new ValidationError("spec.transport", Severity.ERROR,
            "SFTP transport requires key_env or password_env",
            List.of("Use `lm secret set <NAME> ...` then reference via key_env: <NAME>")));
    }

    private static List<ValidationError> moveNeedsAnArchivePath(JsonNode spec, String afterProc) {
        if (!"move".equals(afterProc) || !spec.path("archive_path").isMissingNode()) return List.of();
        return List.of(new ValidationError("spec.archive_path", Severity.ERROR,
            "archive_path is required when after_process=move",
            List.of("Set archive_path: /path/to/archive/")));
    }

    /**
     * The local scheduled reader (row-level glob cursor) has no per-file completion tracking:
     * archive/delete/move never execute there — files would silently stay in place. Remote
     * transports honor it; support waits for a per-file local cursor.
     */
    private static List<ValidationError> localCannotRelocate(String tKind, String afterProc) {
        if (!"local".equals(tKind) || !List.of("archive", "delete", "move").contains(afterProc)) {
            return List.of();
        }
        return List.of(new ValidationError("spec.after_process", Severity.ERROR,
            "after_process: " + afterProc + " is not executed on local connector runs — "
                + "files stay in place (honored by one-shot lm csv ingest and by sftp/s3)",
            List.of("Set after_process: none for a local connector, "
                + "or move the files with an sftp/s3 transport")));
    }

    /**
     * Transport hooks and max_file_size run only on the one-shot lm csv ingest path; scheduled
     * connector runs never invoke them — refuse rather than silently ignore.
     */
    private static List<ValidationError> oneShotOnlyTransportKeys(JsonNode tr) {
        List<ValidationError> errs = new ArrayList<>();
        for (String key : List.of("file_filter", "pre_process", "post_process", "max_file_size")) {
            if (!tr.path(key).isMissingNode()) {
                errs.add(new ValidationError("spec.transport." + key, Severity.ERROR,
                    key + " runs only on one-shot lm csv ingest — "
                        + "scheduled connector runs ignore it",
                    List.of("Drop it from the connector manifest, "
                        + "or ingest this source via lm csv ingest")));
            }
        }
        return errs;
    }

    /** C5: headerless rows need names — the run used to fail where validate passed clean. */
    private static List<ValidationError> headerlessNeedsColumns(JsonNode spec) {
        JsonNode parser = spec.path("parser");
        boolean headerless = parser.path("has_header").isBoolean()
                && !parser.path("has_header").asBoolean();
        boolean noColumns = parser.path("columns").isMissingNode()
                || parser.path("columns").isEmpty();
        if (!headerless || !noColumns) return List.of();
        return List.of(new ValidationError("spec.parser.columns", Severity.ERROR,
            "columns is required when has_header: false — headerless rows need names",
            List.of("Add parser.columns: [code, name, ...] in file order")));
    }

    private static List<ValidationError> scheduleCronIsValid(JsonNode spec) {
        JsonNode sched = spec.path("schedule");
        if (sched.isMissingNode() || sched.isNull()) return List.of();
        return CronField.validCron().apply(sched)
            .map(msg -> List.of(new ValidationError("spec.schedule", Severity.ERROR, msg,
                List.of("Example: schedule: \"0 6 * * *\" (daily at 06:00 UTC)"))))
            .orElseGet(List::of);
    }

    private static List<ValidationError> windowValid(JsonNode spec) {
        JsonNode window = spec.path("window");
        if (window.isMissingNode() || window.isNull()) return List.of();
        return WindowField.validWindow().apply(window)
            .map(msg -> List.of(new ValidationError("spec.window", Severity.ERROR, msg,
                List.of("Example: window: \"22:00-06:00\" (UTC; scheduled runs are skipped outside it)"))))
            .orElseGet(List::of);
    }

    /** The declared row hooks, or nothing — three refusals below start with this same question. */
    private static JsonNode rowHooksOf(JsonNode spec) {
        JsonNode row = spec.path("mapping").path("row");
        return row.isArray() ? row : MissingNode.getInstance();
    }

    /**
     * Connector runs are chunked transactions with a fresh mapping state per chunk: the
     * aggregator's cross-chunk buffer would be silently dropped at every chunk boundary.
     *
     * <p>Dead knobs (tribunal B1/B2) chunk_size and table_prefix left their ConfigDefs and the
     * blocks are CLOSED (C3) — the generic unknown-key WARNING covers them, no duplicate here.
     */
    private static List<ValidationError> aggregatorRefused(JsonNode spec) {
        JsonNode rowHooks = rowHooksOf(spec);
        List<ValidationError> errs = new ArrayList<>();
        for (int i = 0; i < rowHooks.size(); i++) {
            if (rowHooks.get(i).has("aggregator")) {
                errs.add(new ValidationError("spec.mapping.row[" + i + "].aggregator",
                    Severity.ERROR,
                    "aggregator is not supported on csv-file connector runs — each chunk "
                        + "commits separately, buffered rows would be silently lost at chunk "
                        + "boundaries",
                    List.of("Pre-aggregate in the source (e.g. a grouped export), "
                        + "or drop the aggregator hook")));
            }
        }
        return errs;
    }

    /**
     * The csv door for map-columns' own rules — the one that did not exist. jdbc had two doors on
     * these rules and csv had none, which is backwards: jdbc's rename is a narrow config key the
     * hub assembles, csv's is written BY HAND in spec.mapping. A bad rename was accepted by
     * `lm apply`, scheduled, and killed the run on its first row.
     *
     * <p>The rule is {@code MapColumnsRules}, shared with both jdbc doors and with the hook. This
     * one accumulates, so an integrator sees every problem in the block at once rather than the
     * first — which is the whole point of refusing before the run instead of during it. The hook
     * still refuses at run time as a backstop: the template mapping path reaches it without
     * passing any connector validator.
     */
    private static List<ValidationError> mapColumnsRefused(JsonNode spec) {
        JsonNode rowHooks = rowHooksOf(spec);
        List<ValidationError> errs = new ArrayList<>();
        for (int i = 0; i < rowHooks.size(); i++) {
            JsonNode params = rowHooks.get(i).path("map-columns");
            if (params.isMissingNode()) continue;
            String base = "spec.mapping.row[" + i + "].map-columns";
            List<MapColumnsRules.Violation> violations = new ArrayList<>();
            violations.addAll(MapColumnsRules.checkRename(params.path("rename")));
            violations.addAll(MapColumnsRules.checkDrop(params.path("drop")));
            for (MapColumnsRules.Violation v : violations) {
                // The path goes in the path field; lm prints it on its own line above the
                // message, so repeating it in the text reads as a stutter.
                errs.add(new ValidationError(base + "." + v.path(), Severity.ERROR, v.reason(),
                    List.of("Example: map-columns: { rename: { CUST_NM: name }, drop: [flag] }")));
            }
        }
        return errs;
    }

    /**
     * split-row fans one source row into N fragments; a validate-row placed after it would drop
     * failing fragments one at a time instead of once per source row, so the mapping tier can
     * record more drops than source rows — corrupting {@code records_out} into a negative count
     * (mapping-drop-accounting design, "Composition rule"). The engine also refuses this
     * defensively (belt-and-braces for hooks the validator doesn't know about), but refusing here
     * catches it at validate/apply time instead of mid-run.
     */
    private static List<ValidationError> validateRowAfterSplitRowRefused(JsonNode spec) {
        JsonNode rowHooks = rowHooksOf(spec);
        int splitAt = -1;
        for (int i = 0; i < rowHooks.size(); i++) {
            if (rowHooks.get(i).has("split-row")) {
                splitAt = i;
                break;
            }
        }
        if (splitAt < 0) return List.of();
        List<ValidationError> errs = new ArrayList<>();
        for (int i = splitAt + 1; i < rowHooks.size(); i++) {
            if (rowHooks.get(i).has("validate-row")) {
                errs.add(new ValidationError("spec.mapping.row[" + i + "].validate-row",
                    Severity.ERROR,
                    "validate-row after split-row is not supported — validation would drop "
                        + "fanned fragments and corrupt the run arithmetic. Validate before "
                        + "splitting.",
                    List.of("Validate before splitting, or drop the validate-row hook")));
            }
        }
        return errs;
    }
}
