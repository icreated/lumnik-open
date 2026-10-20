package io.lumnik.hub.connector.runtime;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.ConnectorType;
import io.lumnik.hub.connector.api.types.ChunkResult;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.RunBound;
import io.lumnik.hub.connector.api.types.RunStart;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.ConnectorCredential;
import io.lumnik.hub.connector.domain.ConnectorRun;
import io.lumnik.hub.connector.jdbc.JdbcRunBound;
import io.lumnik.hub.connector.registry.ConnectorTypeRegistry;
import io.lumnik.hub.connector.repository.ConnectorEndpointRepository;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.lumnik.hub.connector.repository.ConnectorCredentialRepository;
import io.lumnik.hub.connector.repository.ConnectorRunRepository;
import io.lumnik.hub.webhook.SecretCipher;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A jdbc run drains what the table held when it started, and no more.
 *
 * <p>Before the run had a target, {@code JdbcQueryBuilder} paged on {@code (keys) > (last) ORDER BY
 * keys LIMIT n} with no ceiling at all: a table receiving rows faster than the hub reads them fed
 * chunks for as long as the inserts continued, so the run ended when the WRITERS paused rather than
 * when the reader had caught up. On a busy table that never happens — the run row stays
 * {@code Running}, and every scheduled slot behind it is skipped by the per-endpoint advisory lock.
 * Exactly the shape kafka had; found by asking who else paged without a ceiling.
 *
 * <p>What this pins is the whole contract in one sequence: measure the target, insert MORE rows,
 * read — and see the rows that arrived after the target left for the next run.
 */
@QuarkusTest
class JdbcBoundedRunIT {

    private static final int ROWS_AT_START = 10;
    private static final int ROWS_ADDED_MID_RUN = 5;

    @Inject ChunkExecutor exec;
    @Inject ConnectorTypeRegistry registry;
    @Inject ConnectorRepository connectors;
    @Inject ConnectorEndpointRepository endpoints;
    @Inject ConnectorRunRepository runs;
    @Inject ConnectorCredentialRepository credentials;
    @Inject SecretCipher cipher;
    @Inject UserTransaction utx;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private String jdbcUrl;
    private static final String DB_USER = "lumnik_app";
    private static final String DB_PASSWORD = "lumnik_app_dev_password";

    @BeforeEach
    void seedSourceTable() throws Exception {
        try (Connection c = adminDs.getConnection()) {
            jdbcUrl = c.getMetaData().getURL();
        }
        execSql(adminDs, "DROP TABLE IF EXISTS public.src_bounded");
        execSql(adminDs, "CREATE TABLE public.src_bounded "
              + "(src_bounded_id bigint primary key, updated timestamptz, label text)");
        // Distinct, ordered watermarks so the keyset order is deterministic rather than tie-broken.
        execSql(adminDs, "INSERT INTO public.src_bounded (src_bounded_id, updated, label) "
              + "SELECT g, now() - ((100 - g) || ' seconds')::interval, 'row-' || g "
              + "FROM generate_series(1, " + ROWS_AT_START + ") g");
        execSql(adminDs, "GRANT SELECT ON public.src_bounded TO " + DB_USER);
    }

    @Test
    void aRunReadsWhatTheTableHeldWhenItStarted_andLeavesTheRestToTheNextRun() {
        TenantContext.runAsSystem(() -> {
            try {
                Fixture f = createFixture(/* credentialed */ false);
                ConnectorType type = registry.find("jdbc-generic");

                // ── the target, measured once, exactly as the engine measures it ──
                // NO inTx: ConnectorEngine.runChunks calls this with no transaction of its own, so
                // that the source probe does not hold a pooled connection. Wrapping it here would
                // hand the credential read an ambient transaction production never gives it — which
                // is how the first version of the test below passed with the defect in place.
                RunStart start = runStartAsTheEngineDoes(f, type, ConnectorCursor.empty());
                RunBound bound = start.bound();
                assertThat(bound)
                        .as("jdbc must declare a target: its runs never ended without one")
                        .isInstanceOf(JdbcRunBound.class);
                assertThat(((JdbcRunBound) bound).values().getLast())
                        .as("the greatest key row the table held: id %d", ROWS_AT_START)
                        .isEqualTo((long) ROWS_AT_START);

                // ── the table keeps receiving WHILE the run is in flight ──
                execSql(adminDs, "INSERT INTO public.src_bounded (src_bounded_id, updated, label) "
                      + "SELECT g, now(), 'late-' || g FROM generate_series("
                      + (ROWS_AT_START + 1) + ", " + (ROWS_AT_START + ROWS_ADDED_MID_RUN) + ") g");

                ChunkResult r1 = inTx(() ->
                        exec.executeChunk(reload(f.runId()), type, f.endpoint(), ConnectorCursor.empty(), bound));
                assertThat(r1).isInstanceOf(ChunkResult.Success.class);
                assertThat(((ChunkResult.Success) r1).processed())
                        .as("the rows that arrived after the target belong to the NEXT run: %s", r1)
                        .isEqualTo(ROWS_AT_START);

                ConnectorCursor after = ((ChunkResult.Success) r1).nextCursor();
                ChunkResult r2 = inTx(() ->
                        exec.executeChunk(reload(f.runId()), type, f.endpoint(), after, bound));
                assertThat(((ChunkResult.Success) r2).processed())
                        .as("and the run ends there — a dry read, which is what makes it terminate")
                        .isZero();
                assertThat(((ChunkResult.Success) r2).endOfStream()).isTrue();

                // ── the next run measures a new target and picks up what was left ──
                RunStart next = runStartAsTheEngineDoes(f, type, after);
                ChunkResult r3 = inTx(() -> exec.executeChunk(
                        reload(f.runId()), type, f.endpoint(), after, next.bound()));
                assertThat(((ChunkResult.Success) r3).processed())
                        .as("nothing was lost: the late rows are read, once, by the run that follows")
                        .isEqualTo(ROWS_ADDED_MID_RUN);

            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void aFirstPassStillReadsTheRowsWhoseWatermarkIsNull() {
        // THE DEFECT THIS PINS, found by review on this arc's own diff. Before the ceiling, a
        // cursorless first run emitted no WHERE at all and Postgres sorts NULL LAST on an ASC
        // order, so rows with no watermark WERE read by that first pass. A row-wise
        // `(wm, pk) <= (?, ?)` with a NULL left operand evaluates to NULL, so adding the ceiling
        // unconditionally excluded them — and every later run has a cursor whose `>` excludes them
        // too, so they would never be ingested by any run: no DLQ entry, no log line, records_in
        // simply smaller. A silent loss introduced by the fix for a silent loss.
        execSql(adminDs, "INSERT INTO public.src_bounded (src_bounded_id, updated, label) "
              + "VALUES (98, NULL, 'no-watermark-a'), (99, NULL, 'no-watermark-b')");

        TenantContext.runAsSystem(() -> {
            try {
                Fixture f = createFixture(/* credentialed */ false);
                ConnectorType type = registry.find("jdbc-generic");

                RunStart start = runStartAsTheEngineDoes(f, type, ConnectorCursor.empty());
                ChunkResult r1 = inTx(() -> exec.executeChunk(
                        reload(f.runId()), type, f.endpoint(), ConnectorCursor.empty(), start.bound()));

                assertThat(((ChunkResult.Success) r1).processed())
                        .as("the ceiling bounds the watermarked rows; it must not silently drop the "
                            + "two that have no watermark, which a first pass has always read")
                        .isEqualTo(ROWS_AT_START + 2);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void theTargetIsStillMeasuredWhenThePasswordComesFromAStoredCredential() {
        // The credentialed path, end to end: the password comes from a stored credential the way a
        // deployment wires one, not from plain config the way a fixture does.
        //
        // WHAT THIS TEST CANNOT SEE, said plainly so nobody trusts it for that: the visibility
        // defect this arc found — a credential read with no ambient transaction on an RLS-enforced
        // datasource — is INVISIBLE in this profile, because the default test profile does not
        // enforce RLS at all. This test passed identically with the defect in place. Its anchor is
        // CredentialResolvedOutsideATransactionRlsIT, on the only harness that can tell.
        TenantContext.runAsSystem(() -> {
            try {
                Fixture f = createFixture(/* credentialed */ true);
                ConnectorType type = registry.find("jdbc-generic");

                // NO inTx: ConnectorEngine.runChunks calls this with no transaction of its own, so
                // that the source probe does not hold a pooled connection. Wrapping it here would
                // hand the credential read an ambient transaction production never gives it — which
                // is how the first version of the test below passed with the defect in place.
                RunStart start = runStartAsTheEngineDoes(f, type, ConnectorCursor.empty());

                assertThat(start.bound())
                        .as("a credentialed connector must still measure its target")
                        .isNotNull();
                assertThat(((JdbcRunBound) start.bound()).values().getLast())
                        .isEqualTo((long) ROWS_AT_START);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    /**
     * Measures the target the way the engine does: outside any transaction. ChunkExecutor opens its
     * own short one for the context it needs and leaves the source probe outside, which is the whole
     * reason the credential read has to be self-sufficient.
     */
    private RunStart runStartAsTheEngineDoes(Fixture f, ConnectorType type, ConnectorCursor cursor)
            throws Exception {
        utx.begin();
        ConnectorRun run = reload(f.runId());
        Connector c = connectors.findById(f.connectorId());
        utx.commit();
        return exec.runStart(run, c, type, f.endpoint(), cursor);
    }

    private record Fixture(UUID runId, UUID connectorId, ConnectorEndpoint endpoint) {}

    /**
     * @param credentialed when true the password is NOT in the config: it comes from a stored
     *                     ConnectorCredential, the way a deployment wires one
     */
    private Fixture createFixture(boolean credentialed) throws Exception {
        utx.begin();
        Connector c = new Connector();
        c.setTenantId(1L);
        c.setName("jdbc-bounded-" + System.nanoTime());
        c.setTypeId("jdbc-generic");
        Map<String, Object> config = new HashMap<>(Map.of(
            "url", jdbcUrl,
            "user", DB_USER,
            "dialect", "postgres"));
        if (credentialed) {
            ConnectorCredential cred = new ConnectorCredential();
            cred.setTenantId(1L);
            cred.setName("jdbc-bounded-cred-" + System.nanoTime());
            cred.setKind("Basic");
            cred.setCiphertext(cipher.encrypt(DB_USER + ":" + DB_PASSWORD));
            cred.setIv(new byte[0]);
            credentials.persist(cred);
            credentials.flush();
            c.setCredentialId(cred.getId());
        } else {
            config.put("password", DB_PASSWORD);
        }
        c.setConfig(config);
        connectors.persist(c);
        connectors.flush();

        ConnectorEndpoint endpoint = new ConnectorEndpoint();
        endpoint.setTenantId(1L);
        endpoint.setConnectorId(c.getId());
        endpoint.setName("src_bounded");
        endpoint.setMethod("GET");
        endpoint.setPath("/src_bounded");
        endpoint.setEnabled(true);
        endpoint.setConfig(Map.of(
            "schema", "public",
            "name", "src_bounded",
            // each case's connector gets its own hub table: a second one is refused the first's (#1199)
            "target", "src_bounded_" + System.nanoTime(),
            "mode", "watermark",
            "watermark_column", "updated",
            "pk_columns", List.of("src_bounded_id")));
        endpoints.persist(endpoint);
        endpoints.flush();

        ConnectorRun run = new ConnectorRun();
        run.setTenantId(1L);
        run.setConnectorId(c.getId());
        run.setEndpointId(endpoint.getId());
        run.setStatus("Running");
        run.setSyncMode("Incremental");
        run.setStartedAt(Instant.now());
        run.setTriggeredBy("MANUAL");
        runs.persist(run);
        runs.flush();
        utx.commit();
        return new Fixture(run.getId(), c.getId(), endpoint);
    }

    private ConnectorRun reload(UUID runId) {
        return runs.findById(runId);
    }

    private <T> T inTx(Callable<T> work) throws Exception {
        utx.begin();
        try {
            T r = work.call();
            utx.commit();
            return r;
        } catch (Exception e) {
            try { utx.rollback(); } catch (Exception ignored) {}
            throw e;
        }
    }
}
