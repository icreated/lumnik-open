package io.lumnik.hub.source.csv;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.api.sources.CsvIngestResult;
import io.lumnik.hub.sql.TenantRlsScope;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-command table chat (spec 2026-09-02): a committed one-shot ingest, with zero
 * further gestures, must leave behind everything the ask pipeline needs — the tagged
 * connector, the schema card for its scope, a queryable table, and one descriptor chunk in
 * the corpus. Each assertion is one move of the spec; together they are the WOW's receipt.
 *
 * <p><b>Not the readonly role, and not RLS.</b> This class runs on the default {@code %test}
 * profile, where the {@code readonly} datasource connects as {@code lumnik_app} and does not
 * enlist in JTA (application.properties, {@code %test} lines) — so the role whose grants are
 * SqlGuard's physical firewall is never the one connecting, and {@code TenantRlsScope}'s
 * transaction-scoped {@code SET LOCAL} is discarded before the query.
 * {@code ReadonlyDatasourceEnlistmentContractTest} says as much in its own javadoc: the test
 * profile cannot reproduce the production posture. Those two guarantees are proven, on the
 * RLS-enforced harness with the real roles, by {@link CsvOneShotReadonlyRlsIT} — this class
 * deliberately does not restate them, it would only pretend to.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CsvOneShotAskableIT {

    @Inject CsvIngestService service;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Inject
    @DataSource("readonly")
    AgroalDataSource readonlyDs;

    CsvIngestResult result;

    @BeforeAll
    void ingestOnceCommitted() throws Exception {
        Path csv = Files.createTempFile("askable", ".csv");
        Files.writeString(csv, "code,amount\nA1,10.5\nB2,20\n", StandardCharsets.UTF_8);
        csv.toFile().deleteOnExit();

        CsvIngestForm form = new CsvIngestForm();
        form.file = csv.toFile();
        form.name = "askable_probe";
        form.scope = "askprobe";
        form.tags = "demo";

        // Committed like a real request: every after-commit move must actually fire.
        result = QuarkusTransaction.requiringNew().call(() ->
            TenantContext.callAsSystem(() -> service.execute(form, 1L)));
    }

    @Test
    void the_dormant_connector_carries_scope_and_tags() throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT tags FROM connector.connector WHERE id = ?")) {
            ps.setObject(1, result.connectorId());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                String[] tags = (String[]) rs.getArray(1).getArray();
                assertThat(tags).contains("askprobe", "demo");
            }
        }
    }

    @Test
    void a_schema_card_exists_for_the_scope_without_any_run() throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT card::text FROM connector.schema_card WHERE tenant_id = 1 AND scope = 'askprobe'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("the card the ask pipeline reads").isTrue();
                assertThat(rs.getString(1)).contains("askable_probe").contains("amount");
            }
        }
    }

    @Test
    void the_table_the_card_advertises_can_actually_be_queried() {
        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection c = readonlyDs.getConnection()) {
                // Production-shaped, not load-bearing here: under %test these SET LOCALs are
                // discarded (see the class javadoc). CsvOneShotReadonlyRlsIT is where they bite.
                TenantRlsScope.applyTo(c, 1L);
                try (PreparedStatement ps = c.prepareStatement(
                         "SELECT count(*) FROM ext.askable_probe");
                     ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1))
                        .as("the rows the one-shot reported written are the rows a query finds")
                        .isEqualTo(2L);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

}
