package io.lumnik.hub.validator.csv;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.csv.CsvFileReader;
import io.lumnik.hub.connector.csv.CsvFullRefresh;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.json.JsonNodes;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ConnectorValidator;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationError.Severity;
import io.lumnik.hub.validator.common.CronField;
import io.lumnik.hub.validator.common.MappingBlockValidator;
import io.lumnik.hub.validator.common.TargetBlockValidator;
import io.lumnik.hub.validator.common.WindowField;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@ConnectorTypeId("csv-file")
public class CsvFileConnectorValidator implements ConnectorValidator {

    private static final String TARGET = "target";

    @Inject MappingBlockValidator mappings;

    @Override public String kind() { return "csv-file"; }

    @Override
    public ConfigDef configDef() {
        return new ConfigDef()
            .requireNested("transport", Importance.HIGH, "Transport block",
                TransportBlockValidator.configDef())
            // Optional, for target's reason. Every parser key either carries a default or is needed
            // only in a case the defaults rule out: delimiter and encoding are sniffed per file when
            // absent, has_header and bom_strip default true, null_values is seeded — and `columns`,
            // the one key with no default, is required solely when has_header is false, which its own
            // default of true excludes. So the block could only ever be written to say nothing (#543).
            .optionalNested("parser", Importance.HIGH, "CSV parser config",
                ParserBlockValidator.configDef())
            .optional("schedule", Type.STRING, Importance.MEDIUM,
                "Cron expression for recurring ingestion", null)
            .requireEnum("after_process", Importance.MEDIUM,
                "What to do after a file is processed",
                "archive", "delete", "move", "none")
            .optional("archive_path", Type.STRING, Importance.LOW,
                "Destination for `move` action", null)
                .requiredWhen("after_process", "move")
                .hint("Set archive_path: /path/to/archive/")
            // Two keys the root of a csv spec really carries, declared so closing does not accuse
            // them. `mapping` is consumed by CsvFileConnectorType.buildSpec (and shipped manifests carry it); its
            // keys and hooks are MappingBlockValidator's to check.
            // `window` is read by the SHARED apply preamble (ApplyUpsert); declared and checked
            // through WindowField, which owns both wordings for all four connector types.
            .optional("mapping", Type.NESTED, Importance.MEDIUM,
                "Dataset/row/cell transformer hooks — see the transformers page", null)
            .optional("window", Type.STRING, Importance.MEDIUM, WindowField.DOC, null)
            // #1195: one local file is the table, and a run that read it whole removes what it no
            // longer holds — at most this share of the table, unless lifted.
            .optionalNested("refresh", Importance.MEDIUM, "Full refresh of one local file: the removal guard",
                new ConfigDef().optionalCustom("max_removed", Type.STRING, Importance.MEDIUM,
                    "Largest share of the table one run may remove: a percentage (30% by default), or off",
                    CsvFullRefresh::maxRemovedProblem).closed())
            // Optional: its only key (`indexes`) is optional too, so requiring the block made every
            // csv manifest carry an empty `target: {}` that the runtime reads nothing from (#543).
            .optionalNested(TARGET, Importance.HIGH, "Target persistence",
                TargetBlockValidator.configDef())
            .closed();
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
        errs.addAll(localCannotRelocate(tKind, afterProc));
        errs.addAll(linesConflicts(spec));
        errs.addAll(scheduleCronIsValid(spec));
        WindowField.check(spec.path("window"), errs);
        errs.addAll(aggregatorRefused(spec));
        errs.addAll(refreshWithoutAState(spec));
        errs.addAll(mappings.validate(spec.path("mapping"), "spec.mapping"));
        errs.addAll(mappings.columnsThatDoNotExist(spec.path("mapping"), "spec.mapping",
            sourceColumns(spec.path("parser")), indexReaders(spec)));
        return errs;
    }

    /** The target's indexes read the row the mapping ends with: path → column. */
    private static Map<String, String> indexReaders(JsonNode spec) {
        Map<String, String> readers = new LinkedHashMap<>();
        JsonNode indexes = spec.path(TARGET).path("indexes");
        if (!indexes.isArray()) return readers;   // another shape is TargetBlockValidator's refusal, not a crash here
        for (int i = 0; i < indexes.size(); i++) {
            JsonNode indexed = indexes.get(i).path("columns");
            if (!indexed.isArray()) continue;                   // TargetBlockValidator's refusal, as above
            for (int j = 0; j < indexed.size(); j++) {
                readers.put("spec.target.indexes[" + i + "].columns[" + j + "]", indexed.get(j).asText());
            }
        }
        return readers;
    }

    /**
     * {@code refresh} only means something where the source is a state: one local file. A glob is a
     * drop folder and a remote transport keeps a watermark over the files it took — both only ever
     * add rows, so the block would be read by nothing. A warning, not a refusal: the manifest is not
     * wrong, it says something this source cannot do.
     */
    private static List<ValidationError> refreshWithoutAState(JsonNode spec) {
        if (spec.path("refresh").isMissingNode()) return List.of();
        JsonNode tr = spec.path("transport");
        // The runtime's default for an absent kind (resolveTransport), so the two never disagree.
        if (CsvFullRefresh.isOneLocalFile(tr.path("kind").asText("local"), tr.path("path").asText(""))) return List.of();
        return List.of(new ValidationError("spec.refresh", Severity.WARNING,
            "refresh is read only for one local file (transport kind: local, a path without * or ?): "
                + "this source is a drop folder and never removes rows, so the block does nothing here",
            List.of("Remove the refresh block, or point transport.path at the one file that holds the table")));
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
                + "files stay in place (sftp and s3 connectors honor it)",
            List.of("Set after_process: none for a local connector, "
                + "or move the files with an sftp/s3 transport")));
    }

    /**
     * #1415: {@code lines: true} reads one line as one row, one column, unsplit and unquoted — a delimiter, a header
     * or a second column would each say the file is something else.
     */
    private static List<ValidationError> linesConflicts(JsonNode spec) {
        JsonNode parser = spec.path("parser");
        if (!parser.path("lines").asBoolean(false)) {
            // The unit separator is how the reader knows line mode: declared by hand it would switch quoting off.
            return String.valueOf(CsvFileReader.Config.LINES).equals(parser.path("delimiter").asText(null))
                ? List.of(new ValidationError("spec.parser.delimiter", Severity.ERROR,
                    "the unit separator (\\u001F) is reserved for lines: true",
                    List.of("Use lines: true to read one line as one row, or another delimiter")))
                : List.of();
        }
        List<ValidationError> errs = new ArrayList<>();
        if (!JsonNodes.blankOrNull(parser.path("delimiter"))) {
            errs.add(linesRefuses("delimiter", "lines: true splits nothing — a line is one value"));
        }
        if (!JsonNodes.blankOrNull(parser.path("has_header"))) {
            errs.add(linesRefuses("has_header", "lines: true has no header — the first line is data, like every other"));
        }
        if (parser.path("columns").size() > 1) {
            errs.add(linesRefuses("columns", "lines: true gives one column — name it with columns: [<name>]"));
        }
        return errs;
    }

    private static ValidationError linesRefuses(String key, String message) {
        return new ValidationError("spec.parser." + key, Severity.ERROR, message,
            List.of("Remove spec.parser." + key + ", or drop lines: true to read the file as CSV"));
    }

    private static List<ValidationError> scheduleCronIsValid(JsonNode spec) {
        List<ValidationError> errs = new ArrayList<>();
        CronField.check(spec.path("schedule"), errs,
            "Example: schedule: \"0 6 * * *\" (daily at 06:00 UTC)");
        return errs;
    }

    /** The columns a known source hands the mapping, or null when they are only known once the file is read. */
    private static Set<String> sourceColumns(JsonNode parser) {
        if (parser.path("lines").asBoolean(false)) return new HashSet<>(Set.of("line"));
        if (!headerless(parser) || !parser.path("columns").isArray() || parser.path("columns").isEmpty()) return null;
        Set<String> columns = new HashSet<>();
        parser.path("columns").forEach(c -> columns.add(c.asText()));
        return columns;
    }

    /** {@code has_header: false}: the parser names the columns itself, from {@code columns}. */
    private static boolean headerless(JsonNode parser) {
        return parser.path("has_header").isBoolean() && !parser.path("has_header").asBoolean();
    }

    /**
     * Connector runs are chunked transactions with a fresh mapping state per chunk: the
     * aggregator's cross-chunk buffer would be silently dropped at every chunk boundary.
     *
     * <p>Dead knobs (tribunal B1/B2) chunk_size and table_prefix left their ConfigDefs and the
     * blocks are CLOSED (C3) — the generic unknown-key WARNING covers them, no duplicate here.
     */
    private static List<ValidationError> aggregatorRefused(JsonNode spec) {
        return MappingBlockValidator.hookRefused(spec.path("mapping"), "spec.mapping", "row", "aggregator",
            "aggregator is not supported on csv-file connector runs — each chunk "
                + "commits separately, buffered rows would be silently lost at chunk "
                + "boundaries",
            "Pre-aggregate in the source (e.g. a grouped export), or drop the aggregator hook");
    }

}
