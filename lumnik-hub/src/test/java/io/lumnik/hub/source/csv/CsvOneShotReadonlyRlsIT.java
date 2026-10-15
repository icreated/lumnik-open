package io.lumnik.hub.source.csv;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The grants a one-shot puts on its own table, under the role that has to use them.
 *
 * <p>Ask-to-SQL executes as {@code lumnik_readonly} — that role is SqlGuard layer 1, the physical
 * half of the honesty line. {@link io.lumnik.hub.connector.mapping.writer.HybridJsonbWriter}
 * grants it {@code SELECT} on each {@code ext.*} table it creates, and stamps the same
 * {@code tenant_isolation} policy the {@code connector.t_*} tables carry. Nothing checked either.
 *
 * <p>Which matters more than an untested path usually does, because this one is <b>built to be
 * silent</b>: the grants ride {@code RlsDdl.guarded(...)}, whose block ends
 * {@code EXCEPTION WHEN OTHERS THEN NULL}. A grant that fails takes the ingest down with it in no
 * way at all — the rows land, the run completes, the card is built, and the ask discovers in
 * production that it cannot read the table it was just told about. This class is the observation
 * that path was missing. (The one-shot spec asked for it in as many words: "VERIFY IN IMPL with a
 * red-first test … If the grant is missing, that test is the finding.")
 *
 * <p>Two tenants ingest the same one-shot name, so both land in ONE {@code ext.grantprobe} table
 * separated by nothing but {@code tenant_id} and the policy over it — the arrangement in which a
 * missing policy is indistinguishable from a working one until someone looks.
 *
 * <p>Read on a hand-rolled transaction ({@code BEGIN} / {@code SET LOCAL} / {@code COMMIT}, the
 * {@code ConnectorRunRlsIT} idiom) rather than through {@code TenantRlsScope}: {@code SET LOCAL}
 * is transaction-scoped, so a read needs its own transaction for the binding to bite. That still
 * holds under {@link RlsEnforcedProfile} specifically: its {@code getConfigOverrides()} re-enables
 * JTA enlistment for the readonly datasource, but only for callers that actually open a JTA
 * transaction — {@link #labelsAs} never does (it drives {@code BEGIN}/{@code COMMIT} on the raw
 * connection itself), so that fix changes nothing here either way.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CsvOneShotReadonlyRlsIT {

    private static final String NAME = "grantprobe";
    private static final String TABLE = "ext." + NAME;

    @Inject CsvIngestService service;

    @Inject
    @DataSource("readonly")
    AgroalDataSource readonlyDs;

    @BeforeAll
    void twoTenantsIngestTheSameOneShot() throws Exception {
        ingest(1L, "code,label\nT1-A,alpha\nT1-B,beta\n");
        ingest(2L, "code,label\nT2-A,gamma\n");
    }

    private void ingest(long tenantId, String csv) throws Exception {
        Path file = Files.createTempFile(NAME + "-" + tenantId, ".csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        file.toFile().deleteOnExit();

        CsvIngestForm form = new CsvIngestForm();
        form.file = file.toFile();
        form.name = NAME;
        form.scope = "grantprobe";

        try {
            QuarkusTransaction.requiringNew().call(() ->
                TenantContext.callAsSystem(() -> service.execute(form, tenantId)));
        } finally {
            // execute() sets the tenant ThreadLocal; callAsSystem restores only SYSTEM_MODE.
            CurrentTenantHolder.clear();
        }
    }

    /**
     * Read as {@code lumnik_readonly} with the tenant pinned for the statement's own transaction.
     * A {@code null} tenant means the GUC is never set — the unbound case.
     */
    private List<String> labelsAs(Long tenantId) {
        List<String> labels = new ArrayList<>();
        try (Connection c = readonlyDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("BEGIN");
            if (tenantId != null) {
                st.execute("SET LOCAL app.current_tenant = '" + tenantId + "'");
            }
            try (ResultSet rs = st.executeQuery("SELECT label FROM " + TABLE + " ORDER BY label")) {
                while (rs.next()) labels.add(rs.getString(1));
            }
            st.execute("COMMIT");
        } catch (Exception e) {
            // Report, do not attribute: a missing GRANT (42501) and a policy that raises on an
            // unbound tenant (22P02) both arrive here, and each mutation below produced one of
            // them. A probe that names the wrong cause sends its next reader to the wrong place.
            throw new IllegalStateException(
                "reading " + TABLE + " as lumnik_readonly failed: " + e.getMessage()
                + " — candidates: the readonly GRANT (whose DDL failure RlsDdl.guarded swallows), "
                + "or the tenant_isolation policy raising instead of hiding.", e);
        }
        return labels;
    }

    @Test
    void the_readonly_role_can_read_the_table_a_one_shot_created() {
        // The grant, alone. A permission failure surfaces from labelsAs as an IllegalStateException
        // carrying Postgres' own words, rather than as an opaque assertion on an empty list.
        assertThat(labelsAs(1L))
            .as("lumnik_readonly is the role ask-to-SQL executes as — without this GRANT the "
                + "one-shot's table is askable in the card and unreadable in fact")
            .isNotEmpty();
    }

    @Test
    void each_tenant_sees_only_its_own_rows_in_the_shared_table() {
        assertThat(labelsAs(1L))
            .as("tenant 1's two rows, and none of tenant 2's — one table, separated by the policy")
            .containsExactly("alpha", "beta");
        assertThat(labelsAs(2L))
            .as("tenant 2's single row")
            .containsExactly("gamma");
    }

    @Test
    void an_unbound_read_sees_nothing_rather_than_failing() {
        // Fail closed, never fail loud: the policy's NULLIF turns an unset GUC into NULL, and
        // tenant_id = NULL is never true. An empty GUC must not raise 22P02 either — a query that
        // errors and a query that legitimately returns nothing must not look alike to a caller.
        assertThat(labelsAs(null))
            .as("no tenant bound: no rows, no error")
            .isEmpty();
    }
}
