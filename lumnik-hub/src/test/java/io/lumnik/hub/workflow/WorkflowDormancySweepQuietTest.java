package io.lumnik.hub.workflow;

import io.lumnik.hub.testsupport.LogProbe;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A declaration the schema no longer matches is said once, not every fifteen minutes for ever.
 *
 * <p>The entities campaign of 2026-09-12 deleted a connector under a live kind:Workflow, taking
 * {@code connector.t_orders} with it. The sweep has logged
 * {@code dormancy sweep failed for tenant 1 workflow order-lifecycle — skipped} at ERROR on every
 * tick since, and there is no delete for a workflow to make it stop (#492).
 *
 * <p><b>Two conditions, not one.</b> {@code WorkflowDataTruth}'s own javadoc names the second while
 * describing the first: a {@code spec.on.key} that points at a column the table does not have
 * "makes the dormancy sweep fail 42703 on every tick, in silence, forever". A dropped table (42P01)
 * and a dropped or typo'd column (42703) are the same family — the declaration outran the data —
 * and they get the same volume.
 *
 * <p>Everything else stays loud. A statement timeout, a revoked grant, a full disk are transient or
 * operational: an operator is meant to see those on every tick, and going quiet about them would be
 * this fix causing the next outage to pass unnoticed.
 */
class WorkflowDormancySweepQuietTest {

    private static final long TENANT = 1L;

    // JUnit 5's default PER_METHOD lifecycle gives a fresh instance per test, so these fields carry
    // exactly the isolation `stoodDown` needs — no @BeforeEach, no repetition in five bodies.
    private final WorkflowDormancySweep sweep = new WorkflowDormancySweep();
    private final WorkflowGraph g = graph();

    private static WorkflowGraph graph() {
        return WorkflowGraph.from(new WorkflowManifestParser().parse("""
            apiVersion: apps.lumnik.io/v1
            kind: Workflow
            metadata:
              name: order-lifecycle
            spec:
              scope: magasin
              provenance: declared
              on:
                table: connector.t_orders
                column: statut
                key: numero
              dormancy:
                after: 30d
              states:
                - code: BROUILLON
                  initial: true
                - code: TERMINE
                  terminal: true
              transitions:
                - { from: [BROUILLON], to: TERMINE }
            """));
    }

    /** The shape latestStatePerKey throws: a RuntimeException carrying the SQLException as cause. */
    private static RuntimeException wrapped(String sqlState, String message) {
        SQLException sql = new SQLException(message, sqlState);
        return new RuntimeException("WorkflowDormancySweep.latestStatePerKey: " + message, sql);
    }

    /**
     * Exactly one line, and it IS the stand-down.
     *
     * <p>{@code hasSize(1)} alone is a false positive waiting to happen: {@code LogProbe.capture}
     * records every level, so if a SQLSTATE stopped being a stand-down condition the ERROR branch
     * would emit one line too and the count would still hold — the assertion passing on a line from
     * the other branch entirely. Three rows had that shape; rather than fix them one at a time, the
     * question "is it the right line?" now lives in one place a fourth row cannot forget to ask.
     */
    private static void assertStoodDown(List<LogProbe.Line> lines, String sqlState) {
        assertThat(lines).as("one stand-down line, and only one: %s", lines).hasSize(1);
        assertThat(lines.get(0).message())
            .as("and it must be the stand-down, not an error that merely also counts as one")
            .contains("standing down").contains(sqlState);
    }

    @Test
    void a_dropped_table_is_reported_once_and_then_stood_down_from() {

        List<LogProbe.Line> first = LogProbe.capture(WorkflowDormancySweep.class,
            () -> sweep.noteSweepFailed(TENANT, g, wrapped("42P01", "relation \"connector.t_orders\" does not exist")));
        List<LogProbe.Line> later = LogProbe.capture(WorkflowDormancySweep.class, () -> {
            sweep.noteSweepFailed(TENANT, g, wrapped("42P01", "relation \"connector.t_orders\" does not exist"));
            sweep.noteSweepFailed(TENANT, g, wrapped("42P01", "relation \"connector.t_orders\" does not exist"));
        });

        assertStoodDown(first, "42P01");
        assertThat(first.get(0).message())
            .as("and it must name the workflow, what is missing, and what a human can do")
            .contains("order-lifecycle").contains("connector.t_orders");
        assertThat(later).as("every later tick is silent while the condition stands: %s", later).isEmpty();
    }

    @Test
    void a_dropped_or_mistyped_column_is_the_same_family() {
        // spec.on.key naming a column the table does not have applies with a clean 200 and then
        // fails 42703 on every tick — named in WorkflowDataTruth's javadoc, unfixed until now.

        List<LogProbe.Line> first = LogProbe.capture(WorkflowDormancySweep.class,
            () -> sweep.noteSweepFailed(TENANT, g, wrapped("42703", "column \"numero\" does not exist")));
        List<LogProbe.Line> later = LogProbe.capture(WorkflowDormancySweep.class,
            () -> sweep.noteSweepFailed(TENANT, g, wrapped("42703", "column \"numero\" does not exist")));

        assertStoodDown(first, "42703");
        assertThat(later).isEmpty();
    }

    @Test
    void every_other_failure_stays_loud_on_every_tick() {

        List<LogProbe.Line> lines = LogProbe.capture(WorkflowDormancySweep.class, () -> {
            sweep.noteSweepFailed(TENANT, g, wrapped("57014", "canceling statement due to statement timeout"));
            sweep.noteSweepFailed(TENANT, g, wrapped("57014", "canceling statement due to statement timeout"));
            sweep.noteSweepFailed(TENANT, g, new RuntimeException("the pool is exhausted"));
        });

        assertThat(lines)
            .as("a timeout, a revoked grant, a dead pool: three ticks, three lines — going quiet "
                + "about those would make this fix the cause of the next unnoticed outage: %s", lines)
            .hasSize(3);
    }

    @Test
    void the_condition_clearing_is_itself_one_line_and_re_arms_the_warning() {
        // Re-ingesting the source recreates the table. The sweep must resume by itself, say so
        // once, and be able to speak again if the table goes away a second time — a stand-down
        // that never lifts is the silence this replaced, one restart later.
        List<LogProbe.Line> lost = LogProbe.capture(WorkflowDormancySweep.class,
            () -> sweep.noteSweepFailed(TENANT, g, wrapped("42P01", "does not exist")));
        assertStoodDown(lost, "42P01");   // the premise: there must be a stand-down to lift

        List<LogProbe.Line> resumed = LogProbe.capture(WorkflowDormancySweep.class,
            () -> sweep.noteSweepSucceeded(TENANT, g));
        List<LogProbe.Line> quietAgain = LogProbe.capture(WorkflowDormancySweep.class,
            () -> sweep.noteSweepSucceeded(TENANT, g));
        List<LogProbe.Line> secondLoss = LogProbe.capture(WorkflowDormancySweep.class,
            () -> sweep.noteSweepFailed(TENANT, g, wrapped("42P01", "does not exist")));

        assertThat(resumed).as("the recovery is worth exactly one line: %s", resumed).hasSize(1);
        assertThat(resumed.get(0).message()).contains("order-lifecycle");
        assertThat(quietAgain).as("an ordinary successful tick says nothing").isEmpty();
        assertStoodDown(secondLoss, "42P01");   // and the warning is armed again
    }

    @Test
    void two_tenants_of_the_same_workflow_name_are_two_conditions() {
        // The same uncaptured-premise hole the changed-reason row had, found by asking who else
        // does this: if 42P01 stopped standing down, tenant 1 would register nothing, tenant 2
        // would log an ERROR instead — and LogProbe.capture takes every level, so a bare
        // hasSize(1) passed on the wrong line entirely — assertStoodDown now asks for both.
        List<LogProbe.Line> first = LogProbe.capture(WorkflowDormancySweep.class,
            () -> sweep.noteSweepFailed(1L, g, wrapped("42P01", "does not exist")));
        assertStoodDown(first, "42P01");   // the premise: tenant 1 must actually stand down

        List<LogProbe.Line> other = LogProbe.capture(WorkflowDormancySweep.class,
            () -> sweep.noteSweepFailed(2L, g, wrapped("42P01", "does not exist")));

        assertStoodDown(other, "42P01");
        assertThat(other.get(0).message())
            .as("tenant 2's table going missing is not tenant 1's news")
            .contains("tenant 2");
    }

    @Test
    void a_condition_that_changes_is_news_again() {
        // The table came back, so the sweep got past it — and then found spec.on.key naming a
        // column that is not there. A different reason, and the operator has not been told it:
        // the stand-down must not swallow it as "already said".
        //
        // Pinned because the code's own comment claims it, and a claim that only exists in prose
        // is one edit away from being false. The earlier wording said the reason was part of the
        // KEY; it is the map VALUE, and it is the comparison against that previous value — not the
        // key — that makes this row pass (Copilot round 2, suppressed comment).
        // The setup is captured and ASSERTED, not merely performed. Uncaptured, it would leak a
        // WARN into the test log — and, worse, this row would still pass if 42P01 stopped being a
        // stand-down condition at all: nothing would be registered, the 42703 below would be a
        // FIRST reason rather than a changed one, and both assertions would hold for the wrong
        // reason. Verified: dropping 42P01 from the family leaves this row green without it.
        List<LogProbe.Line> firstReason = LogProbe.capture(WorkflowDormancySweep.class,
            () -> sweep.noteSweepFailed(TENANT, g, wrapped("42P01", "relation does not exist")));
        assertStoodDown(firstReason, "42P01");   // the premise: a first reason must be standing

        List<LogProbe.Line> changed = LogProbe.capture(WorkflowDormancySweep.class,
            () -> sweep.noteSweepFailed(TENANT, g, wrapped("42703", "column \"numero\" does not exist")));
        List<LogProbe.Line> repeated = LogProbe.capture(WorkflowDormancySweep.class,
            () -> sweep.noteSweepFailed(TENANT, g, wrapped("42703", "column \"numero\" does not exist")));

        assertStoodDown(changed, "42703");   // a new reason is new news
        assertThat(repeated).as("and then it is quiet again, on the new reason").isEmpty();
    }
}
