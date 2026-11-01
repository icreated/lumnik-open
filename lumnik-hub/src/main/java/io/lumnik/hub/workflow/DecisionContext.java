package io.lumnik.hub.workflow;

import io.lumnik.hub.process.MessageTemplate;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The columns a dormancy or due decision carries, read from the very version it is about (#1588). The sentence
 * cites some; {@code fields} declares others that only the payload carries. A reserved placeholder keeps its meaning
 * and wins over a column of the same name in the sentence; that column stays reachable through {@code fields}. Same
 * contract as a Process decision: null renders empty in the sentence and stays null in the payload.
 */
final class DecisionContext {

    private DecisionContext() {}

    /** The cited non-reserved names, then the declared fields, each once, in order of first appearance. */
    static List<String> columns(String message, Set<String> reserved, List<String> fields) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : MessageTemplate.placeholders(message)) if (!reserved.contains(p)) out.add(p);
        out.addAll(fields);
        return List.copyOf(out);
    }

    /** The sentence: the version's columns, then the reserved values over them. */
    static String render(String template, Map<String, Object> context, Map<String, Object> reserved) {
        Map<String, Object> merged = new HashMap<>(context);
        merged.putAll(reserved);
        return MessageTemplate.render(template, merged);
    }

    /** The payload's {@code fields}: every column of the version's context, as a Process sends it. Key order is not
     *  part of the contract (the event log stores the payload as jsonb). */
    static Map<String, Object> fields(Map<String, Object> context) {
        return MessageTemplate.fields(context.keySet(), context);
    }
}
