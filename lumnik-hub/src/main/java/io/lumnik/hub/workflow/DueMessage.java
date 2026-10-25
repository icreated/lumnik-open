package io.lumnik.hub.workflow;

import io.lumnik.hub.process.MessageTemplate;

import java.util.Map;
import java.util.Set;

/**
 * The sentence an overdue decision carries (#1448). A workflow may declare its own in
 * {@code spec.due.message}, in its métier's language, over what the sweep knows; without one the
 * sentence is English. Either way it says what the data says — a date passed in a state — never
 * why. Same {@code {placeholder}} grammar as a dormancy or Process message.
 */
public final class DueMessage {

    /** What a declared message may name; anything else is refused at apply. */
    public static final Set<String> PLACEHOLDERS =
        Set.of("doc_key", "state", "state_label", "due", "workflow");

    static final String DEFAULT = "{doc_key}: due {due}, still in \"{state}\" — declared due date in {workflow}";

    private DueMessage() {}

    /** The sentence for {@code docKey}, overdue in {@code state} since {@code due}, in {@code g}'s message or the default. */
    static String render(WorkflowGraph g, String docKey, String state, String due) {
        String template = g.due().message();
        return MessageTemplate.render(template != null ? template : DEFAULT, Map.of(
            "doc_key", docKey, "state", state, "state_label", g.labelOf(state), "due", due, "workflow", g.name()));
    }
}
