package io.lumnik.hub.testsupport;

/**
 * A minimal {@code kind: Export} manifest over an SFTP destination, for tests that only need a
 * name, a scope, a source entity and a column list. Shared by {@code ExportApplyResourceIT},
 * {@code ExportRegistryResourceIT}, {@code ExportManifestTenantIsolationIT} and
 * {@code ScopeRefusalOnTheWireIT} — same {@code format}/{@code destination}/{@code mode} block,
 * same four arguments — since the four would otherwise drift apart the day a destination field
 * changes.
 */
public final class ExportManifestFixture {

    private ExportManifestFixture() {}

    /** {@code order} is fixed to {@code [code]}: every caller's {@code columns} includes it. */
    public static String sftpManifest(String name, String scope, String entity, String columns) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Export
            metadata:
              name: %s
            spec:
              scope: %s
              source:
                entity: %s
              columns: [%s]
              order: [code]
              format:
                kind: csv
              destination:
                kind: sftp
                host: exchange.example.com
                user: lumnik
                path: /out/export.csv
                key_env: TEST_SFTP_KEY
                host_key_fingerprint: "SHA256:AAAA"
              mode: snapshot
            """.formatted(name, scope, entity, columns);
    }
}
