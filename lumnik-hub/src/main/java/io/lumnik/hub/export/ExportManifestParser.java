package io.lumnik.hub.export;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.export.run.ArtifactName;
import io.lumnik.hub.manifest.ManifestKeys;
import io.lumnik.hub.manifest.ManifestSurface;
import io.lumnik.hub.validator.common.CronField;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Parses a kind:Export YAML manifest into an ExportManifest. Structural + syntactic validation
 *  only — cross-manifest checks against the referenced entity live at the apply door
 *  (ExportApplyResource), which is the only place EntityRegistry state is available. */
public class ExportManifestParser {

    private static final String METADATA = "metadata";
    private static final Set<String> ROOT_KEYS = Set.of("apiVersion", "kind", METADATA, "spec");
    private static final Set<String> METADATA_KEYS = Set.of("name");
    private static final Set<String> SPEC_KEYS =
        Set.of("scope", "source", "columns", "order", "format", "destination", "mode", "schedule");
    private static final Set<String> SOURCE_KEYS = Set.of("entity");
    private static final Set<String> FORMAT_KEYS = Set.of("kind", "delimiter", "encoding", "header");

    /** SFTP's own fields — never read by parseS3, so an s3 destination carrying one is refused. */
    private static final List<String> SFTP_ONLY_KEYS = List.of(
        "host", "port", "user", "path", "key_env", "password_env", "host_key_fingerprint");

    /** S3's own fields — never read by parseSftp, so an sftp destination carrying one is refused. */
    private static final List<String> S3_ONLY_KEYS = List.of(
        "bucket", "prefix", "region", "endpoint", "filename", "access_key_env", "secret_key_env");

    /**
     * One flat surface over both destination kinds, deliberately: {@code unknownKeys} answers
     * "is this name part of the kind:Export contract at all", which is a different question from
     * "does it belong to THIS destination kind". The second question is asked by
     * {@link #refuseForeignFields}, inside the kind's own parse method, where the kind is known —
     * splitting the surface itself would need a per-kind {@code ManifestSurface} for one check.
     */
    private static final Set<String> DESTINATION_KEYS = destinationKeys();

    private static Set<String> destinationKeys() {
        Set<String> keys = new HashSet<>();
        keys.add("kind");
        keys.addAll(SFTP_ONLY_KEYS);
        keys.addAll(S3_ONLY_KEYS);
        return Set.copyOf(keys);
    }

    private static final ManifestSurface SURFACE = ManifestSurface.builder()
        .group("", "root", ROOT_KEYS)
        .group(METADATA, "metadata", METADATA_KEYS)
        .group("spec", "spec", SPEC_KEYS)
        .group("spec.source", "spec.source", SOURCE_KEYS)
        .group("spec.format", "spec.format", FORMAT_KEYS)
        .group("spec.destination", "spec.destination", DESTINATION_KEYS)
        .build();

    private static final Set<String> RESERVED_COLUMNS = Set.of("_conflicts", "_entity_id", "_resolution", "_handle");

    /** field name -> the *_env name a caller should use instead, in the order checked. Never added
     *  to DESTINATION_KEYS — these names are refused, not accepted. */
    private static final Map<String, String> INLINE_CREDENTIAL_FIELDS = new LinkedHashMap<>();
    static {
        INLINE_CREDENTIAL_FIELDS.put("password", "password_env");
        INLINE_CREDENTIAL_FIELDS.put("private_key", "key_env");
        INLINE_CREDENTIAL_FIELDS.put("access_key", "access_key_env");
        INLINE_CREDENTIAL_FIELDS.put("secret_key", "secret_key_env");
    }

    public List<String> unknownKeys(JsonNode root) {
        return SURFACE.unknownKeys(root);
    }

    public static String honoredSurface() {
        return SURFACE.honoredSurface();
    }

    /** Must run BEFORE unknownKeys() is consulted by the door: none of these four names are ever
     *  added to DESTINATION_KEYS, so the generic unknown-key refusal would reach and refuse
     *  spec.destination.password first, as an ordinary "unrecognized key" — before this specific,
     *  actionable redirect ever had a chance to fire. */
    public Optional<Response> refuseInlineCredentials(JsonNode root) {
        JsonNode destination = root.path("spec").path("destination");
        if (!destination.isObject()) return Optional.empty();
        for (Map.Entry<String, String> e : INLINE_CREDENTIAL_FIELDS.entrySet()) {
            if (destination.has(e.getKey())) {
                return Optional.of(ApiErrors.badRequest("'" + e.getKey() + "' is not accepted — "
                    + "store the credential via `lm secret set` and reference it as `"
                    + e.getValue() + "`"));
            }
        }
        return Optional.empty();
    }

    /** The document root, refused unless it is a YAML mapping. Parse it once and hand the same
     *  tree to {@link #parse(JsonNode)} and to whatever else reads the manifest. */
    public JsonNode readTree(String yaml) {
        JsonNode root;
        try {
            root = SURFACE.readTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid kind:Export manifest: " + e.getMessage(), e);
        }
        // Jackson's readTree returns a literal null for genuinely empty input, and a NullNode /
        // ArrayNode / scalar node for a document that parses cleanly but isn't a mapping. Refusing
        // both here — rather than letting a later field read stumble into either — is what keeps
        // ExportApplyResource.refuseInlineCredentials (which dereferences root.path(...) before
        // parse() ever runs) from crashing with a 500 instead of answering a clean 400.
        //
        // ViewManifestParser/AppManifestParser/WorkflowManifestParser/ProcessManifestParser share
        // this same exposure through ManifestSurface.readTree(String) — they've just never been fed
        // a document malformed enough to trigger it. Fixed here only, deliberately: this parser's
        // own door is the one that dereferences root a second time before parse()'s own checks run,
        // and moving the guard into the shared ManifestSurface scaffold would change behavior for
        // four sibling kinds outside this ticket's scope. Worth doing as its own follow-up.
        if (root == null || !root.isObject())
            throw new IllegalArgumentException(
                "invalid kind:Export manifest: expected a YAML mapping (object) at the document root");
        return root;
    }

    public ExportManifest parse(String yaml) {
        return parse(readTree(yaml));
    }

    public ExportManifest parse(JsonNode root) {
        String apiVersion = requireText(root, "apiVersion", "apiVersion");
        if (!"apps.lumnik.io/v1".equals(apiVersion))
            throw new IllegalArgumentException("apiVersion must be 'apps.lumnik.io/v1'");
        if (!"Export".equals(requireText(root, "kind", "kind")))
            throw new IllegalArgumentException("kind must be 'Export'");

        JsonNode meta = root.path(METADATA);
        JsonNode spec = root.path("spec");

        String name = requireText(meta, "name", "metadata.name");

        String scopeRaw = requireText(spec, "scope", "spec.scope");
        String scope = scopeRaw.strip();
        ScopeGrants.nameProblem(scope)
            .ifPresent(problem -> { throw new IllegalArgumentException("spec.scope: " + problem); });

        JsonNode source = spec.path("source");
        String entity = requireText(source, "entity", "spec.source.entity");

        List<String> columns = readStringList(spec, "columns", "spec.columns");
        if (columns.isEmpty())
            throw new IllegalArgumentException("spec.columns must declare at least one column");
        // No character-class check here: EntityViewCompiler quotes every canonical column via
        // SqlIdentifiers.quoteIdent, whose own class javadoc states the principle directly —
        // "Quoting is the escaping defence, NOT a character allowlist... an allowlist wrongly
        // rejects valid Postgres identifiers (e.g. names containing '-')". A real entity can
        // legitimately declare a canonical column shaped like "customer-id"; only the apply
        // door's cross-check against that entity's actual canonicalColumns() — not this parser,
        // which has no entity to check against — can tell a real column from an invented one.
        Set<String> seenColumns = new HashSet<>();
        for (String col : columns) {
            if (RESERVED_COLUMNS.contains(col))
                throw new IllegalArgumentException(
                    "spec.columns: '" + col + "' is an internal column and cannot be exported");
            if (!seenColumns.add(col))
                throw new IllegalArgumentException("spec.columns: duplicate column '" + col + "'");
        }

        List<String> order = readStringList(spec, "order", "spec.order");
        if (order.isEmpty())
            throw new IllegalArgumentException("spec.order must declare at least one column");
        Set<String> seenOrder = new HashSet<>();
        for (String col : order) {
            if (!seenOrder.add(col))
                throw new IllegalArgumentException("spec.order: duplicate column '" + col + "'");
            if (!columns.contains(col))
                throw new IllegalArgumentException(
                    "spec.order: '" + col + "' must also appear in spec.columns");
        }

        ExportManifest.Format format = parseFormat(spec.path("format"));
        ExportManifest.Destination destination = parseDestination(spec.path("destination"));

        String mode = requireText(spec, "mode", "spec.mode");
        if (!"snapshot".equals(mode))
            throw new IllegalArgumentException("unsupported mode '" + mode + "' — only 'snapshot' is supported");

        String schedule = optionalText(spec, "schedule", "spec.schedule");
        if (schedule != null) {
            String scheduleValue = schedule;
            CronField.validCron(scheduleValue)
                .ifPresent(problem -> { throw new IllegalArgumentException("spec.schedule: " + problem); });
        }

        return new ExportManifest(name, scope, entity, columns, order, format, destination, mode, schedule);
    }

    /**
     * Strict scalar reads. Unlike {@link ManifestKeys#text}/{@link ManifestKeys#requiredText}
     * (shared with the lenient sibling parsers — View/App/Workflow), these refuse a
     * present-but-non-textual node instead of coercing it through Jackson's own
     * {@code asText()}. kind:Export is the one manifest kind that promises strict validation
     * (see the strictly-enforced {@code apiVersion} above), so {@code metadata.name: 123} or
     * {@code spec.mode: [snapshot]} must be refused, not silently stringified.
     */
    private static String requireText(JsonNode node, String field, String path) {
        return ManifestKeys.strictRequiredText(node, field, path);
    }

    /** {@link #requireText}, but {@code null} when the field is genuinely absent. */
    private static String optionalText(JsonNode node, String field, String path) {
        return ManifestKeys.strictText(node, field, path);
    }

    private static ExportManifest.Format parseFormat(JsonNode format) {
        String kind = requireText(format, "kind", "spec.format.kind");
        if (!"csv".equals(kind))
            throw new IllegalArgumentException("unsupported format kind '" + kind + "' — only 'csv' is supported");

        String delimiterRaw = optionalText(format, "delimiter", "spec.format.delimiter");
        char delimiter = requireValidDelimiter(delimiterRaw);

        String encoding = optionalText(format, "encoding", "spec.format.encoding");
        if (encoding == null) encoding = ExportManifest.Format.DEFAULT.encoding();
        if (!"UTF-8".equals(encoding))
            throw new IllegalArgumentException("spec.format.encoding must be 'UTF-8': '" + encoding + "'");

        JsonNode headerNode = format.path("header");
        boolean header;
        if (headerNode.isMissingNode() || headerNode.isNull()) {
            header = ExportManifest.Format.DEFAULT.header();
        } else if (headerNode.isBoolean()) {
            header = headerNode.asBoolean();
        } else {
            throw new IllegalArgumentException("spec.format.header must be a boolean");
        }

        return new ExportManifest.Format(kind, delimiter, encoding, header);
    }

    private static char requireValidDelimiter(String raw) {
        if (raw == null) return ExportManifest.Format.DEFAULT.delimiter();
        if (raw.length() != 1)
            throw new IllegalArgumentException(
                "spec.format.delimiter must be exactly one character: '" + raw + "'");
        char c = raw.charAt(0);
        if (c == '"' || c == '\r' || c == '\n' || c == '\0')
            throw new IllegalArgumentException(
                "spec.format.delimiter must not be '\"', a line break, or NUL: '" + raw + "'");
        return c;
    }

    private static ExportManifest.Destination parseDestination(JsonNode d) {
        String kind = requireText(d, "kind", "spec.destination.kind");
        return switch (kind) {
            case "sftp" -> parseSftp(d);
            case "s3" -> parseS3(d);
            default -> throw new IllegalArgumentException(
                "spec.destination.kind must be 'sftp' or 's3': '" + kind + "'");
        };
    }

    /**
     * Refuses a field belonging to the OTHER destination kind, by name.
     *
     * <p>{@code unknownKeys} cannot see this: its surface is one flat set over both kinds, so
     * {@code key_env} on an {@code s3} destination passes it cleanly and {@code parseS3} simply
     * never reads the field. The caller would get a 200 and a manifest silently falling back to
     * {@code AWS_ACCESS_KEY_ID} — the deferred-failure shape this parser exists to close off, and
     * the sharpest one, because it substitutes a different credential than the one the integrator
     * was looking at when they wrote the line. Runs FIRST in each kind's parse method, so the
     * mis-placed field is what a caller reads about rather than the required field it displaced.
     */
    private static void refuseForeignFields(JsonNode d, String kind, List<String> foreignKeys) {
        for (String key : foreignKeys) {
            if (d.has(key))
                throw new IllegalArgumentException("spec.destination: '" + key
                    + "' is not a field of an " + kind + " destination");
        }
    }

    private static ExportManifest.Destination.Sftp parseSftp(JsonNode d) {
        refuseForeignFields(d, "sftp", S3_ONLY_KEYS);
        String host = requireText(d, "host", "spec.destination.host");
        String user = requireText(d, "user", "spec.destination.user");
        String path = requireText(d, "path", "spec.destination.path");
        refuseBadSubstitutionTokens(path, "spec.destination.path");
        String fingerprint = requireText(
            d, "host_key_fingerprint", "spec.destination.host_key_fingerprint");

        JsonNode portNode = d.path("port");
        int port;
        if (portNode.isMissingNode() || portNode.isNull()) {
            port = 22;
        } else if (portNode.isIntegralNumber() && portNode.canConvertToInt()) {
            // isIntegralNumber() alone isn't enough: a LongNode/BigIntegerNode outside int range
            // (e.g. 4294967318 == 2^32 + 22) is still "integral", and asInt() on it truncates
            // rather than refusing — silently turning an out-of-range value into an in-range one.
            // canConvertToInt() is what actually guarantees asInt() below is lossless.
            port = portNode.asInt();
        } else {
            throw new IllegalArgumentException("spec.destination.port must be an integer");
        }
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("spec.destination.port must be between 1 and 65535: " + port);

        String keyEnv = optionalText(d, "key_env", "spec.destination.key_env");
        String passwordEnv = optionalText(d, "password_env", "spec.destination.password_env");
        if (keyEnv != null && keyEnv.isBlank())
            throw new IllegalArgumentException("spec.destination.key_env must not be blank");
        if (passwordEnv != null && passwordEnv.isBlank())
            throw new IllegalArgumentException("spec.destination.password_env must not be blank");
        boolean hasKey = keyEnv != null && !keyEnv.isBlank();
        boolean hasPassword = passwordEnv != null && !passwordEnv.isBlank();
        if (hasKey && hasPassword)
            throw new IllegalArgumentException("specify only one of key_env or password_env, not both");
        if (!hasKey && !hasPassword)
            throw new IllegalArgumentException("SFTP transport requires key_env or password_env");

        return new ExportManifest.Destination.Sftp(host, port, user, path, keyEnv, passwordEnv, fingerprint);
    }

    private static ExportManifest.Destination.S3 parseS3(JsonNode d) {
        refuseForeignFields(d, "s3", SFTP_ONLY_KEYS);
        String bucket = requireText(d, "bucket", "spec.destination.bucket");
        String filename = requireText(d, "filename", "spec.destination.filename");
        refuseBadSubstitutionTokens(filename, "spec.destination.filename");

        String prefix = optionalText(d, "prefix", "spec.destination.prefix");
        if (prefix == null) prefix = "";
        // Same token rule as filename: prefix is the other half of the final object key, so a
        // broken ${...} in it is the same deferred failure, one field over.
        refuseBadSubstitutionTokens(prefix, "spec.destination.prefix");
        String region = optionalText(d, "region", "spec.destination.region");
        if (region == null) region = "eu-west-3";
        String endpoint = optionalText(d, "endpoint", "spec.destination.endpoint");
        String accessKeyEnv = readOptionalNonBlank(d, "access_key_env", "AWS_ACCESS_KEY_ID");
        String secretKeyEnv = readOptionalNonBlank(d, "secret_key_env", "AWS_SECRET_ACCESS_KEY");

        return new ExportManifest.Destination.S3(
            bucket, prefix, region, endpoint, filename, accessKeyEnv, secretKeyEnv);
    }

    private static String readOptionalNonBlank(JsonNode node, String field, String defaultValue) {
        String v = optionalText(node, field, "spec.destination." + field);
        if (v == null) return defaultValue;
        if (v.isBlank())
            throw new IllegalArgumentException(
                "spec.destination." + field + " must not be blank when given");
        return v;
    }

    /** The artifact-name token rule is {@link ArtifactName}'s; this only names the field. */
    private static void refuseBadSubstitutionTokens(String value, String fieldPath) {
        ArtifactName.problem(value).ifPresent(problem -> {
            throw new IllegalArgumentException(fieldPath + ": " + problem);
        });
    }

    /**
     * A strict string list: the field must be a JSON/YAML array (not a mapping — {@code
     * JsonNode.iterator()} walks an ObjectNode's field VALUES the exact same way it walks an
     * ArrayNode's elements, so {@code columns: {x: code}} would otherwise silently become the
     * one-element list {@code [code]}), and every element must be textual.
     */
    private static List<String> readStringList(JsonNode node, String field, String path) {
        JsonNode arr = node.path(field);
        if (arr.isMissingNode() || arr.isNull()) return List.of();
        if (!arr.isArray())
            throw new IllegalArgumentException(path + " must be a list");
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (!n.isTextual())
                throw new IllegalArgumentException(path + " must contain only strings");
            out.add(n.asText());
        }
        return out;
    }
}
