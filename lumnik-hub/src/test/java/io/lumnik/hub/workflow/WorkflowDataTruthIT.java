package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class WorkflowDataTruthIT {

    @Inject WorkflowDataTruth truth;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private static final long TENANT = 7201L;

    private WorkflowGraph graph(String table) {
        return graph(table, "statut");
    }

    private WorkflowGraph graph(String table, String column) {
        return graph(table, column, null);
    }

    private WorkflowGraph graph(String table, String column, String key) {
        return new WorkflowGraph("wf-truth-it", "demo", table, column, "declared",
            key, null,
            List.of(new WorkflowGraph.StateDto("EN_COURS", null, List.of("en cours"), false),
                    new WorkflowGraph.StateDto("TERMINE", null, List.of("fini"), true),
                    new WorkflowGraph.StateDto("ANNULE", null, List.of(), true)),
            "EN_COURS", List.of());
    }

    @Test
    @Transactional
    void reports_undeclared_values_and_unobserved_states() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS connector.t_wf_truth_it "
                + "(id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, statut TEXT)");
            s.execute("DELETE FROM connector.t_wf_truth_it");
            // dirty aliases resolve; 'EN LITIGE' is undeclared; ANNULE never appears
            s.execute("INSERT INTO connector.t_wf_truth_it (tenant_id, statut) VALUES "
                + "(" + TENANT + ", 'En cours'), (" + TENANT + ", 'fini'), "
                + "(" + TENANT + ", 'TERMINE '), (" + TENANT + ", 'EN LITIGE'), "
                + "(" + TENANT + ", 'EN LITIGE')");
        }

        WorkflowDataTruth.Report r = truth.check(TENANT, graph("connector.t_wf_truth_it"));

        assertThat(r.tableMissing()).isFalse();
        assertThat(r.undeclared()).hasSize(1);
        assertThat(r.undeclared().get(0).value()).isEqualTo("EN LITIGE");
        assertThat(r.undeclared().get(0).rows()).isEqualTo(2);
        assertThat(r.unobserved()).containsExactly("ANNULE");
    }

    @Test
    @Transactional
    void absent_table_is_reported_not_thrown() {
        WorkflowDataTruth.Report r = truth.check(TENANT, graph("connector.t_does_not_exist"));
        assertThat(r.tableMissing()).isTrue();
        assertThat(r.checkUnavailable()).isFalse();
        assertThat(r.undeclared()).isEmpty();
        assertThat(r.unobserved()).isEmpty();
    }

    /** NULL/blank rows used to be dropped in silence: a column that is NULL on 90% of rows
     *  reported `undeclared: []`, which reads as "the data agrees with the manifest". */
    @Test
    @Transactional
    void null_and_blank_values_are_counted_not_dropped() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS connector.t_wf_truth_null_it "
                + "(id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, statut TEXT)");
            s.execute("DELETE FROM connector.t_wf_truth_null_it");
            s.execute("INSERT INTO connector.t_wf_truth_null_it (tenant_id, statut) VALUES "
                + "(" + TENANT + ", 'En cours'), (" + TENANT + ", NULL), "
                + "(" + TENANT + ", NULL), (" + TENANT + ", '   ')");
        }

        WorkflowDataTruth.Report r = truth.check(TENANT, graph("connector.t_wf_truth_null_it"));

        assertThat(r.nullOrBlank()).isEqualTo(3L);
        assertThat(r.undeclared()).isEmpty();
    }

    /**
     * Anything the probe cannot do — a statement timeout on a 50M-row table, a dropped column, a
     * revoked grant — is a degraded REPORT, never a refusal: "nothing ever blocks".
     *
     * <p>The second row is what replaced the character grammar. The name is no longer filtered, so
     * quoting is the whole defence, and the SQLSTATE is the claim: **42703 undefined_column, not
     * 42601 syntax error** — the server read the whole hostile string as ONE identifier. Asserting
     * `checkUnavailable` alone would be inert, since bare interpolation reaches that flag too.
     */
    @ParameterizedTest(name = "an unrunnable probe degrades the report: column {0}")
    @ValueSource(strings = {"no_such_col", "statut\"; DROP TABLE connector.t_wf_truth_it; --"})
    @Transactional
    void an_unrunnable_probe_degrades_the_report_instead_of_throwing(String column) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS connector.t_wf_truth_it "
                + "(id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, statut TEXT)");
        }

        // the table exists, the column does not — not the one modelled case
        WorkflowDataTruth.Report r = truth.check(TENANT, graph("connector.t_wf_truth_it", column));

        assertThat(r.checkUnavailable()).isTrue();
        assertThat(r.unavailableReason()).contains("42703");
        assertThat(r.tableMissing()).isFalse();
        assertThat(r.undeclared()).isEmpty();
    }

    /**
     * A typo'd {@code spec.on.key} (`nu` for `num`) is indistinguishable from a column not yet
     * ingested, so it applies with
     * a clean 200 — and then the dormancy sweep's SELECT fails 42703 on every single tick, silently,
     * forever. The report already models "declared but not yet real" ({@code tableMissing}); the
     * missing key column belongs in the same family, surfaced and never refused.
     */
    @Test
    @Transactional
    void a_declared_key_column_that_is_not_in_the_table_is_reported_not_refused() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS connector.t_wf_truth_key_it "
                + "(id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, num TEXT, statut TEXT)");
        }

        WorkflowDataTruth.Report typo =
            truth.check(TENANT, graph("connector.t_wf_truth_key_it", "statut", "nu"));
        assertThat(typo.keyColumnMissing()).isTrue();
        assertThat(typo.tableMissing()).isFalse();
        assertThat(typo.checkUnavailable()).isFalse();

        // the real column, and no key declared at all, are both silent — never a false accusation
        assertThat(truth.check(TENANT, graph("connector.t_wf_truth_key_it", "statut", "num"))
            .keyColumnMissing()).isFalse();
        assertThat(truth.check(TENANT, graph("connector.t_wf_truth_key_it", "statut"))
            .keyColumnMissing()).isFalse();
    }

    /**
     * The dormancy sweep skips a document whose key is NULL or blank, for good. The report counts
     * them — with the sweep's own predicate — so an integrator can see that dormancy covers only part
     * of the table. {@code null} (not {@code 0}) is "not measured": 0 must only ever mean a valid
     * measure found nobody.
     */
    @Test
    @Transactional
    void rows_without_a_usable_key_are_counted_and_not_measuring_is_null_not_zero() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS connector.t_wf_truth_keynull_it "
                + "(id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, num TEXT, statut TEXT)");
            s.execute("DELETE FROM connector.t_wf_truth_keynull_it");
            // two keyed rows, then NULL, '' and whitespace-only keys; one blank-key row has no status
            // either (it belongs to both diagnostics)
            s.execute("INSERT INTO connector.t_wf_truth_keynull_it (tenant_id, num, statut) VALUES "
                + "(" + TENANT + ", 'A1', 'fini'), (" + TENANT + ", 'A2', 'fini'), "
                + "(" + TENANT + ", NULL, 'fini'), (" + TENANT + ", '', 'fini'), "
                + "(" + TENANT + ", '  ', NULL)");
        }
        String table = "connector.t_wf_truth_keynull_it";

        WorkflowDataTruth.Report r = truth.check(TENANT, graph(table, "statut", "num"));
        assertThat(r.keyNullOrBlank()).isEqualTo(3L);
        assertThat(r.nullOrBlank()).isEqualTo(1L); // independent buckets, not meant to be summed

        // measured and clean is 0 — a different statement from "not measured"
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM connector.t_wf_truth_keynull_it WHERE tenant_id = " + TENANT);
            s.execute("INSERT INTO connector.t_wf_truth_keynull_it (tenant_id, num, statut) "
                + "VALUES (" + TENANT + ", 'A1', 'fini')");
        }
        assertThat(truth.check(TENANT, graph(table, "statut", "num")).keyNullOrBlank()).isEqualTo(0L);

        // not measured: no key declared, key column absent, table absent
        assertThat(truth.check(TENANT, graph(table, "statut")).keyNullOrBlank()).isNull();
        WorkflowDataTruth.Report typo = truth.check(TENANT, graph(table, "statut", "nu"));
        assertThat(typo.keyColumnMissing()).isTrue();
        assertThat(typo.checkUnavailable()).isFalse(); // a typo'd key stays a normal answer
        assertThat(typo.keyNullOrBlank()).isNull();
        assertThat(truth.check(TENANT, graph("connector.t_does_not_exist", "statut", "num"))
            .keyNullOrBlank()).isNull();
    }

    /**
     * A table created exactly the way {@code DynamicTableWriter} creates one — quoted, so the names
     * are the source's own (see {@code docs/apps/workflows.md#names-the-source-chose}).
     *
     * <p>What this test adds over the parser's: the CONFRONTATION runs on them. The sharp half is
     * {@code keyColumnMissing}, which the catalog probe answers — and that flag is, by this class's
     * own javadoc, the only signal a typo'd key ever produces, so a false one is worse than none.
     */
    @Test
    @Transactional
    void a_name_the_source_chose_is_confronted_not_refused() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS connector.\"t_WfTruthVerbatim_it\" "
                + "(id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "\"N° Commande\" TEXT, \"Statut Commande\" TEXT)");
            s.execute("DELETE FROM connector.\"t_WfTruthVerbatim_it\"");
            s.execute("INSERT INTO connector.\"t_WfTruthVerbatim_it\" "
                + "(tenant_id, \"N° Commande\", \"Statut Commande\") VALUES "
                + "(" + TENANT + ", 'C-1', 'En cours'), (" + TENANT + ", 'C-2', 'EN LITIGE')");
        }

        WorkflowDataTruth.Report r = truth.check(TENANT,
            graph("connector.t_WfTruthVerbatim_it", "Statut Commande", "N° Commande"));

        assertThat(r.tableMissing()).isFalse();
        assertThat(r.checkUnavailable()).isFalse();
        assertThat(r.keyColumnMissing()).isFalse();
        assertThat(r.undeclared()).extracting(WorkflowDataTruth.ValueCount::value)
            .containsExactly("EN LITIGE");
        assertThat(r.unobserved()).containsExactlyInAnyOrder("TERMINE", "ANNULE");
    }
}
