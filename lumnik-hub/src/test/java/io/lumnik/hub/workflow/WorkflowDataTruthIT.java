package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

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

    /** Anything the probe cannot do — a statement timeout on a 50M-row table, a dropped column,
     *  a revoked grant — is a degraded REPORT, never a refusal: "nothing ever blocks". */
    @Test
    @Transactional
    void an_unrunnable_probe_degrades_the_report_instead_of_throwing() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS connector.t_wf_truth_it "
                + "(id BIGSERIAL PRIMARY KEY, tenant_id BIGINT NOT NULL, statut TEXT)");
        }

        // the table exists, the column does not (42703) — not the one modelled case
        WorkflowDataTruth.Report r = truth.check(TENANT, graph("connector.t_wf_truth_it", "no_such_col"));

        assertThat(r.checkUnavailable()).isTrue();
        assertThat(r.unavailableReason()).isNotBlank();
        assertThat(r.tableMissing()).isFalse();
        assertThat(r.undeclared()).isEmpty();
    }

    /**
     * A typo'd {@code spec.on.key} (`nu` for `num`) passes the identifier grammar and applies with
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
}
