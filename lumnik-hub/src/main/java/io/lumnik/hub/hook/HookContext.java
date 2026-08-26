package io.lumnik.hub.hook;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public interface HookContext {
    Long tenantId();
    Long userId();
    UserContext user();
    String module();

    /** Cancel the operation. Throws HookCancellation. */
    void fail(String code, String message);
    void fail(String code, String message, Map<String, Object> details);

    /** Fire a domain event via OutboxPublisher (async post-commit). */
    void event(String type, Object payload);

    /** Read-only entity lookup. */
    <E> Optional<E> lookup(Class<E> type, Long id);

    /** Structured log with hookId + tenantId injected. */
    void log(String message);
    void log(String message, Map<String, Object> fields);

    String hookId();
    Instant timestamp();
}
