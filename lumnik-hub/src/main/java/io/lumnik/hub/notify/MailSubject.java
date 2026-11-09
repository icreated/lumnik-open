package io.lumnik.hub.notify;

import io.lumnik.common.text.Text;

import java.util.Map;

/** The subject of an event's mail: a decision's own sentence, else the event type. */
final class MailSubject {

    static final int MAX_LENGTH = 100;
    private static final String DECISION_PREFIX = "decision.";

    private MailSubject() {}

    /** A {@code decision.*} event with a {@code message} is subjected by it (one line, cut to {@link #MAX_LENGTH}); any other event by its type. */
    static String of(String eventType, Map<String, Object> payload) {
        if (eventType.startsWith(DECISION_PREFIX) && payload != null && payload.get("message") instanceof String message) {
            String line = message.strip().replaceAll("\\s+", " ");
            if (Text.present(line)) return Text.clip(line, MAX_LENGTH);
        }
        return "lumnik event: " + eventType;
    }
}
