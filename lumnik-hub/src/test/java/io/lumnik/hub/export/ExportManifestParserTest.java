package io.lumnik.hub.export;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportManifestParserTest {

    private final ExportManifestParser parser = new ExportManifestParser();

    private static final String VALID = """
        apiVersion: apps.lumnik.io/v1
        kind: Export
        metadata:
          name: customers-nightly
        spec:
          scope: sales
          source:
            entity: AcmeCustomer
          columns: [code, name, city, email]
          order: [code]
          format:
            kind: csv
            delimiter: ";"
            encoding: UTF-8
            header: true
          destination:
            kind: sftp
            host: exchange.example.com
            port: 22
            user: lumnik
            path: /out/customers/customers-${date}.csv
            key_env: CUSTOMER_EXPORT_SFTP_KEY
            host_key_fingerprint: "SHA256:AAAA"
          mode: snapshot
          schedule: "0 2 * * *"
        """;

    @Test
    void aValidManifestParsesAndPreservesDeclaredOrder() {
        ExportManifest m = parser.parse(VALID);
        assertThat(m.name()).isEqualTo("customers-nightly");
        assertThat(m.scope()).isEqualTo("sales");
        assertThat(m.entity()).isEqualTo("AcmeCustomer");
        assertThat(m.columns()).containsExactly("code", "name", "city", "email");
        assertThat(m.order()).containsExactly("code");
        assertThat(m.format().kind()).isEqualTo("csv");
        assertThat(m.format().delimiter()).isEqualTo(';');
        assertThat(m.format().encoding()).isEqualTo("UTF-8");
        assertThat(m.format().header()).isTrue();
        assertThat(m.destination()).isInstanceOf(ExportManifest.Destination.Sftp.class);
        assertThat(m.mode()).isEqualTo("snapshot");
        assertThat(m.schedule()).isEqualTo("0 2 * * *");
    }

    @Test
    void unknownKeysAreReportedNotRefusedByTheParser() {
        JsonNode root = parser.readTree(VALID.replace("mode: snapshot", "mode: snapshot\n  bogus: 1"));
        assertThat(parser.unknownKeys(root)).containsExactly("spec.bogus");
    }

    @Test
    void wrongApiVersionIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("apps.lumnik.io/v1", "apps.lumnik.io/v2")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("apiVersion must be 'apps.lumnik.io/v1'");
    }

    @Test
    void wrongKindIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("kind: Export", "kind: View")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("kind must be 'Export'");
    }

    @Test
    void missingNameIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("name: customers-nightly", "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("metadata.name is required");
    }

    @Test
    void reservedInternalColumnIsRefusedByName() {
        assertThatThrownBy(() -> parser.parse(
            VALID.replace("columns: [code, name, city, email]", "columns: [code, _entity_id]")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'_entity_id' is an internal column and cannot be exported");
    }

    @Test
    void theConflictHandleColumnIsRefusedByName() {
        assertThatThrownBy(() -> parser.parse(
            VALID.replace("columns: [code, name, city, email]", "columns: [code, _handle]")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'_handle' is an internal column and cannot be exported");
    }

    @Test
    void duplicateColumnIsRefused() {
        assertThatThrownBy(() -> parser.parse(
            VALID.replace("columns: [code, name, city, email]", "columns: [code, code]")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate column 'code'");
    }

    /**
     * EntityViewCompiler quotes every canonical column via SqlIdentifiers.quoteIdent — its own
     * class javadoc states the principle directly: "Quoting is the escaping defence, NOT a
     * character allowlist — an allowlist wrongly rejects valid Postgres identifiers (e.g. names
     * containing '-')". A canonical column named "customer-id" is a legitimate, real column an
     * entity can declare and the compiled view can serve; the parser must not refuse it on shape
     * alone — only the apply door's cross-check against the entity's actual canonicalColumns()
     * (plus the explicit reserved-internal-column block, still enforced above) decides whether a
     * declared column is real.
     */
    @Test
    void aHyphenatedColumnNameIsNotRefusedByShapeAlone() {
        ExportManifest m = parser.parse(
            VALID.replace("columns: [code, name, city, email]", "columns: [code, customer-id]"));
        assertThat(m.columns()).containsExactly("code", "customer-id");
    }

    @Test
    void orderEntryAbsentFromColumnsIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("order: [code]", "order: [zzz]")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'zzz' must also appear in spec.columns");
    }

    @Test
    void unsupportedFormatKindIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("kind: csv", "kind: tsv")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported format kind 'tsv'");
    }

    @Test
    void unsupportedEncodingIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("encoding: UTF-8", "encoding: ISO-8859-1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.format.encoding must be 'UTF-8'");
    }

    @Test
    void quoteCharDelimiterIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("delimiter: \";\"", "delimiter: '\"'")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be '\"'");
    }

    @Test
    void lineBreakDelimiterIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("delimiter: \";\"", "delimiter: \"\\n\"")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiCharDelimiterIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("delimiter: \";\"", "delimiter: \"::\"")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be exactly one character");
    }

    @Test
    void blankHostKeyFingerprintIsRefused() {
        assertThatThrownBy(() -> parser.parse(
            VALID.replace("host_key_fingerprint: \"SHA256:AAAA\"", "host_key_fingerprint: \"\"")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.destination.host_key_fingerprint is required");
    }

    @Test
    void outOfRangePortIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("port: 22", "port: 70000")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("port must be between 1 and 65535");
    }

    @Test
    void neitherKeyEnvNorPasswordEnvIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("key_env: CUSTOMER_EXPORT_SFTP_KEY", "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SFTP transport requires key_env or password_env");
    }

    @Test
    void bothKeyEnvAndPasswordEnvIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace(
            "key_env: CUSTOMER_EXPORT_SFTP_KEY",
            "key_env: CUSTOMER_EXPORT_SFTP_KEY\n    password_env: CUSTOMER_EXPORT_SFTP_PW")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("specify only one of key_env or password_env");
    }

    @Test
    void blankKeyEnvIsRefused() {
        assertThatThrownBy(() -> parser.parse(
            VALID.replace("key_env: CUSTOMER_EXPORT_SFTP_KEY", "key_env: \"\"")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("key_env must not be blank");
    }

    private static final String S3_SPEC = """
        spec:
          scope: sales
          source:
            entity: AcmeCustomer
          columns: [code, name]
          order: [code]
          format:
            kind: csv
          destination:
            kind: s3
            bucket: partner-exchange
            filename: customers-${timestamp}.csv
            access_key_env: ""
          mode: snapshot
        """;

    @Test
    void explicitlyBlankAccessKeyEnvOnS3IsRefused() {
        assertThatThrownBy(() -> parser.parse(
            "apiVersion: apps.lumnik.io/v1\nkind: Export\nmetadata:\n  name: s3-export\n" + S3_SPEC))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("access_key_env must not be blank when given");
    }

    @Test
    void s3RegionDefaultsMatchIngestion() {
        String yaml = "apiVersion: apps.lumnik.io/v1\nkind: Export\nmetadata:\n  name: s3-export\n"
            + S3_SPEC.replace("    access_key_env: \"\"\n", "");
        ExportManifest m = parser.parse(yaml);
        ExportManifest.Destination.S3 s3 = (ExportManifest.Destination.S3) m.destination();
        assertThat(s3.region()).isEqualTo("eu-west-3");
        assertThat(s3.accessKeyEnv()).isEqualTo("AWS_ACCESS_KEY_ID");
        assertThat(s3.secretKeyEnv()).isEqualTo("AWS_SECRET_ACCESS_KEY");
    }

    private static String s3Manifest(String destinationExtra) {
        return "apiVersion: apps.lumnik.io/v1\nkind: Export\nmetadata:\n  name: s3-export\n"
            + S3_SPEC.replace("    access_key_env: \"\"\n", destinationExtra);
    }

    /**
     * The cross-kind half of the surface, which {@code unknownKeys} structurally cannot see: its
     * allowed-key set is one flat surface over both destination kinds, so an SFTP-only name on an
     * s3 destination passes it and {@code parseS3} then never reads the field. The sharp case is
     * {@code key_env}: accepted silently, ignored, and the manifest falls back to
     * {@code AWS_ACCESS_KEY_ID} — a different credential than the one the integrator wrote.
     */
    @Test
    void anSftpOnlyFieldOnAnS3DestinationIsRefusedByName() {
        assertThat(parser.unknownKeys(parser.readTree(s3Manifest("    key_env: MY_S3_KEY\n"))))
            .as("the flat key surface accepts it — which is exactly why parseS3 has to refuse it")
            .isEmpty();

        assertThatThrownBy(() -> parser.parse(s3Manifest("    key_env: MY_S3_KEY\n")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'key_env' is not a field of an s3 destination");
    }

    @Test
    void anS3OnlyFieldOnAnSftpDestinationIsRefusedByName() {
        String yaml = VALID.replace("    host: exchange.example.com",
            "    host: exchange.example.com\n    bucket: partner-exchange");

        assertThat(parser.unknownKeys(parser.readTree(yaml))).isEmpty();

        assertThatThrownBy(() -> parser.parse(yaml))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'bucket' is not a field of an sftp destination");
    }

    /** The negative control: each kind's OWN fields stay accepted, so the refusal above is not a wall. */
    @Test
    void eachKindStillAcceptsItsOwnFields() {
        assertThat(parser.parse(VALID).destination())
            .isInstanceOf(ExportManifest.Destination.Sftp.class);
        assertThat(parser.parse(s3Manifest("    region: eu-west-1\n")).destination())
            .isInstanceOf(ExportManifest.Destination.S3.class);
    }

    /** prefix is the other half of the final object key, so it carries filename's token rule. */
    @Test
    void aBadSubstitutionTokenInS3PrefixIsRefused() {
        assertThatThrownBy(() -> parser.parse(s3Manifest("    prefix: out/${bogus}/\n")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.destination.prefix: unrecognized substitution token");
    }

    @Test
    void unrecognizedClosedTokenIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("${date}", "${bogus}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unrecognized substitution token '${bogus}'");
    }

    @Test
    void unterminatedTokenIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("${date}", "${dat")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unterminated '${'");
    }

    @Test
    void unsupportedModeIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("mode: snapshot", "mode: incremental")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported mode 'incremental'");
    }

    @Test
    void invalidCronSyntaxIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("\"0 2 * * *\"", "\"not a cron\"")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.schedule:");
    }

    @Test
    void absentScheduleIsFine() {
        ExportManifest m = parser.parse(VALID.replace("  schedule: \"0 2 * * *\"\n", ""));
        assertThat(m.schedule()).isNull();
    }

    // ---- strict typing: a mapping/scalar/boolean coerced into the wrong shape must be refused,
    // not silently accepted via Jackson's own asText()/asBoolean()/asInt() coercion. kind:Export
    // is the one manifest kind that promises strict validation, unlike its lenient siblings. ----

    @Test
    void columnsAsAMappingIsRefusedNotSilentlyIteratedAsAList() {
        // JsonNode's iterator() walks an ObjectNode's field VALUES the same way it walks an
        // ArrayNode's elements — without an explicit isArray() check, {x: code} silently becomes
        // the one-element list [code].
        assertThatThrownBy(() -> parser.parse(
            VALID.replace("columns: [code, name, city, email]", "columns: {x: code}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.columns must be a list");
    }

    @Test
    void nonTextualColumnEntryIsRefused() {
        assertThatThrownBy(() -> parser.parse(
            VALID.replace("columns: [code, name, city, email]", "columns: [code, 123]")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.columns must contain only strings");
    }

    @Test
    void nonBooleanHeaderIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("header: true", "header: nonsense")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.format.header must be a boolean");
    }

    @Test
    void quotedPortIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("port: 22", "port: \"22\"")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.destination.port must be an integer");
    }

    @Test
    void decimalPortIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("port: 22", "port: 22.5")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.destination.port must be an integer");
    }

    @Test
    void portOverflowingIntRangeIsRefused() {
        // 4294967318 == 2^32 + 22. A bare isIntegralNumber() check lets this through as a LongNode,
        // and JsonNode.asInt() truncates it to 22 — an in-range "valid" port that was never the
        // value declared. Must be refused before the 1..65535 range check ever sees a truncated int.
        assertThatThrownBy(() -> parser.parse(VALID.replace("port: 22", "port: 4294967318")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.destination.port must be an integer");
    }

    @Test
    void negativePortIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("port: 22", "port: -1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("port must be between 1 and 65535");
    }

    @Test
    void nonTextualNameIsRefused() {
        assertThatThrownBy(() -> parser.parse(
            VALID.replace("name: customers-nightly", "name: 123")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("metadata.name must be a string");
    }

    @Test
    void nonTextualScopeIsRefused() {
        assertThatThrownBy(() -> parser.parse(VALID.replace("scope: sales", "scope: [sales]")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("spec.scope must be a string");
    }

    // ---- an empty/null/non-object YAML document must be refused cleanly, not turn into a null
    // JsonNode that later code (ExportApplyResource.refuseInlineCredentials, in particular)
    // dereferences and crashes on with a 500. ----

    @Test
    void emptyDocumentIsRefused() {
        assertThatThrownBy(() -> parser.parse(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void explicitNullDocumentIsRefused() {
        assertThatThrownBy(() -> parser.parse("null"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rootArrayIsRefused() {
        assertThatThrownBy(() -> parser.parse("- one\n- two\n"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rootScalarIsRefused() {
        assertThatThrownBy(() -> parser.parse("just a string"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
