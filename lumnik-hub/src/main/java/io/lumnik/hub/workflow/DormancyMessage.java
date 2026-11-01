package io.lumnik.hub.workflow;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * The sentence a dormancy decision carries (#1363). A workflow may declare its own in
 * {@code spec.dormancy.message}, in its business domain's language, over what the sweep knows; without one
 * the sentence is English. Either way it says what lumnik observed — a document unmoved since a
 * day — never what it cannot know ("stuck"). Same {@code {placeholder}} grammar as a Process message.
 */
public final class DormancyMessage {

    /** The reserved names a declared message may use; any other name is a column of the watched relation (#1588). */
    public static final Set<String> PLACEHOLDERS =
        Set.of("doc_key", "state", "state_label", "since", "first_observed_at", "threshold", "workflow");

    static final String DEFAULT = "{doc_key}: observed unmoved in \"{state}\" since {since} "
        + "(first observed {first_observed_at}) — declared threshold {threshold} in {workflow}";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private DormancyMessage() {}

    /** The sentence of {@code obs}'s dormancy, in {@code g}'s declared message or the default, with the version's
     *  cited columns ({@link DecisionContext#render}). */
    static String render(WorkflowGraph g, WorkflowObservationStore.Observation obs, Map<String, Object> context) {
        String template = g.dormancy().message();
        return DecisionContext.render(template != null ? template : DEFAULT, context, Map.of(
            "doc_key", obs.docKey(), "state", obs.state(), "state_label", g.labelOf(obs.state()),
            "since", DAY.format(obs.since()), "first_observed_at", DAY.format(obs.firstObservedAt()),
            "threshold", g.dormancy().after(), "workflow", g.name()));
    }
}
