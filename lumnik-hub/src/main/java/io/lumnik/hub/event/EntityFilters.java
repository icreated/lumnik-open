package io.lumnik.hub.event;

/**
 * Shared entity-scope contract for subscriptions (webhook_endpoint, notification_rule).
 * A subscription's entity_filter narrows only events that carry an entity; entity-less
 * events (e.g. connector.run.completed) reach every subscriber. A null filter = all entities.
 * One home so the webhook arm, the email arm, and the emission gate never drift apart.
 */
public final class EntityFilters {

    private EntityFilters() {}

    public static boolean passes(String filter, String entity) {
        return filter == null || entity == null || filter.equals(entity);
    }
}
