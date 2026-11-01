package io.lumnik.hub.workflow;

import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a sweep has already said about a workflow, one entry per (tenant, workflow, condition) (#1588). A table gone,
 * a refused order column, unreadable keys and a missing context column can hold at once; one entry per workflow
 * would hide one behind another, or announce a resumption too early. In memory: after a restart, a condition still
 * true is said once more, the right amount of noise for a state that holds.
 */
final class WorkflowConditions {

    /** The table or a declared column is gone (SQLSTATE 42P01 / 42703). */
    static final String DECLARATION = "declaration";
    /** {@code spec.on.order} is missing or cannot order. */
    static final String ORDER = "order";
    /** Keys with an invalid or null order value. */
    static final String UNREADABLE = "unreadable";
    /** Keys whose latest versions disagree. */
    static final String AMBIGUOUS = "ambiguous";
    /** {@code spec.due.column} is missing or is not a date. */
    static final String DUE_COLUMN = "due-column";

    /** Why an {@link #UNREADABLE} key is not watched, and what repairs it. */
    static final String UNREADABLE_WHY =
        "a version has no valid spec.on.order value — correct or remove the malformed source rows";
    /** Why an {@link #AMBIGUOUS} key is not watched. */
    static final String AMBIGUOUS_WHY = "its latest versions disagree";

    private final Map<String, String> held = new ConcurrentHashMap<>();
    private final Logger log;
    private final String sweep;

    /** {@code sweep} names who speaks: "dormancy", "due". */
    WorkflowConditions(Logger log, String sweep) {
        this.log = log;
        this.sweep = sweep;
    }

    /** Records that {@code condition} holds with {@code value}; true when that is news (new, or changed). */
    boolean holds(long tenant, String workflow, String condition, String value) {
        return !value.equals(held.put(key(tenant, workflow, condition), value));
    }

    /** Records that {@code condition} no longer holds; true when it did, which is worth one line. */
    boolean cleared(long tenant, String workflow, String condition) {
        return held.remove(key(tenant, workflow, condition)) != null;
    }

    /** A condition that stops the workflow: said once, and again only when what it says changes. */
    void standDown(long tenant, String workflow, String condition, String why, String remedy) {
        if (holds(tenant, workflow, condition, why))
            log.warnf("%s sweep standing down for tenant %d workflow %s: %s. %s", sweep, tenant, workflow, why, remedy);
    }

    /** A cited or declared column the relation lost (#1588): one condition per column. */
    static String context(String column) {
        return "context:" + column;
    }

    /**
     * Only a column the declaration cites or declares now may hold a context condition: one it stopped citing is
     * released, even when another took its place, so cited again while still missing it is said again instead of
     * found "already said" by a declaration that never heard it (#1588).
     */
    void releaseUncited(long tenant, String workflow, Collection<String> wanted) {
        String prefix = key(tenant, workflow, context(""));
        for (String k : held.keySet()) {
            if (!k.startsWith(prefix)) continue;
            String column = k.substring(prefix.length());
            if (!wanted.contains(column)) resumed(tenant, workflow, context(column), column + " is no longer cited");
        }
    }

    /**
     * The workflow goes on but something is missing from what it says, as a column a decision cites: said once, as a
     * warning that does not stand anything down.
     */
    void warnOnce(long tenant, String workflow, String condition, String what) {
        if (holds(tenant, workflow, condition, what))
            log.warnf("%s sweep: tenant %d workflow %s %s", sweep, tenant, workflow, what);
    }

    /** The end of a condition: one line, only when it was held. */
    void resumed(long tenant, String workflow, String condition, String what) {
        if (cleared(tenant, workflow, condition))
            log.infof("%s sweep resumed for tenant %d workflow %s — %s", sweep, tenant, workflow, what);
    }

    /**
     * Documents a sweep could not read, said as one line per workflow and condition, again only when their number
     * changes, and once more when none is left (#1588).
     */
    void noteKeys(long tenant, String workflow, String condition, KeySample keys, String why) {
        if (keys.isEmpty()) {
            if (cleared(tenant, workflow, condition))
                log.infof("%s: every document of tenant %d workflow %s is readable again (%s)",
                    sweep, tenant, workflow, condition);
            return;
        }
        if (holds(tenant, workflow, condition, String.valueOf(keys.count())))
            log.warnf("%s: %d documents of tenant %d workflow %s are not watched: %s (e.g. %s)",
                sweep, keys.count(), tenant, workflow, why, keys.shown());
    }

    /**
     * {@code spec.on.order} is not declared, or no longer: what only an order could hold is released, so an order
     * put back later says its conditions again instead of finding them "already said" (#1588).
     */
    void releaseOrder(long tenant, String workflow) {
        resumed(tenant, workflow, ORDER, "spec.on.order is no longer declared");
        noteKeys(tenant, workflow, UNREADABLE, new KeySample(), UNREADABLE_WHY);
        noteKeys(tenant, workflow, AMBIGUOUS, new KeySample(), AMBIGUOUS_WHY);
    }

    /** One workflow of one tenant, as a sweep names what it swept. */
    static String workflowOf(long tenant, String workflow) {
        return tenant + ":" + workflow;
    }

    /**
     * Forgets every workflow this tick did not sweep — deleted, disabled, or no longer watched by this sweep — so,
     * watched again later, a condition still true is news again instead of found "already said" (#1588). Silent:
     * nothing is left to tell about a workflow nobody watches.
     */
    void forgetUnswept(Set<String> swept) {
        // a tenant is a number and a workflow name kebab-case: the second ':' ends the workflow, whatever follows
        held.keySet().removeIf(k -> !swept.contains(k.substring(0, k.indexOf(':', k.indexOf(':') + 1))));
    }

    private static String key(long tenant, String workflow, String condition) {
        return workflowOf(tenant, workflow) + ":" + condition;
    }
}
