package io.lumnik.hub.workflow;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowConditionsTest {

    private final WorkflowConditions c = new WorkflowConditions(Logger.getLogger(WorkflowConditionsTest.class), "test");

    @Test
    void a_condition_is_news_once_then_again_when_it_changes() {
        assertThat(c.holds(1, "wf", WorkflowConditions.UNREADABLE, "3")).isTrue();
        assertThat(c.holds(1, "wf", WorkflowConditions.UNREADABLE, "3")).isFalse();
        assertThat(c.holds(1, "wf", WorkflowConditions.UNREADABLE, "4")).isTrue();
    }

    @Test
    void two_conditions_of_one_workflow_never_hide_each_other() {
        assertThat(c.holds(1, "wf", WorkflowConditions.UNREADABLE, "3")).isTrue();
        assertThat(c.holds(1, "wf", WorkflowConditions.AMBIGUOUS, "1")).isTrue();
        assertThat(c.cleared(1, "wf", WorkflowConditions.AMBIGUOUS)).isTrue();
        assertThat(c.holds(1, "wf", WorkflowConditions.UNREADABLE, "3")).as("still held, not news").isFalse();
    }

    @Test
    void tenants_and_workflows_are_apart() {
        assertThat(c.holds(1, "wf", WorkflowConditions.ORDER, "x")).isTrue();
        assertThat(c.holds(2, "wf", WorkflowConditions.ORDER, "x")).isTrue();
        assertThat(c.holds(1, "other", WorkflowConditions.ORDER, "x")).isTrue();
        assertThat(c.cleared(1, "never", WorkflowConditions.ORDER)).isFalse();
    }

    /** Releasing what a workflow stopped citing touches no other column it cites, workflow, tenant or condition. */
    @Test
    void releasing_the_uncited_touches_nothing_else() {
        c.warnOnce(1, "wf", WorkflowConditions.context("owner"), "x");
        c.warnOnce(1, "wf", WorkflowConditions.context("laboratory"), "x");
        c.warnOnce(1, "wf-2", WorkflowConditions.context("owner"), "x");
        c.warnOnce(11, "wf", WorkflowConditions.context("owner"), "x");
        c.holds(1, "wf", WorkflowConditions.ORDER, "x");
        c.releaseUncited(1, "wf", List.of("laboratory"));
        assertThat(c.cleared(1, "wf", WorkflowConditions.context("owner"))).as("released").isFalse();
        assertThat(c.cleared(1, "wf", WorkflowConditions.context("laboratory"))).as("still cited").isTrue();
        assertThat(c.cleared(1, "wf-2", WorkflowConditions.context("owner"))).isTrue();
        assertThat(c.cleared(11, "wf", WorkflowConditions.context("owner"))).isTrue();
        assertThat(c.cleared(1, "wf", WorkflowConditions.ORDER)).isTrue();
    }

    /** What a tick did not sweep is forgotten, whatever its conditions; what it swept keeps every one of them. */
    @Test
    void forgetting_the_unswept_keeps_exactly_the_swept() {
        c.holds(1, "wf", WorkflowConditions.ORDER, "x");
        c.warnOnce(1, "wf", WorkflowConditions.context("a:b"), "x");   // a source column may hold a colon
        c.holds(11, "wf", WorkflowConditions.ORDER, "x");
        c.holds(1, "wf-2", WorkflowConditions.ORDER, "x");
        c.forgetUnswept(Set.of(WorkflowConditions.workflowOf(1, "wf")));
        assertThat(c.cleared(1, "wf", WorkflowConditions.ORDER)).as("swept").isTrue();
        assertThat(c.cleared(1, "wf", WorkflowConditions.context("a:b"))).as("swept").isTrue();
        assertThat(c.cleared(11, "wf", WorkflowConditions.ORDER)).as("another tenant, not swept").isFalse();
        assertThat(c.cleared(1, "wf-2", WorkflowConditions.ORDER)).as("another workflow, not swept").isFalse();
    }
}
