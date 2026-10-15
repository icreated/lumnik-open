package io.lumnik.hub.event.consumer;

import io.lumnik.hub.event.EventOutboxRow;

import java.util.function.Consumer;

/**
 * The processor of one outbox batch: {@link #accept} runs inside the batch's transaction;
 * {@link #afterCommit} runs once that transaction has committed, with nothing held — the place for
 * a consumer to call the outside world, having only recorded what is to be done in {@code accept}.
 * {@link OutboxSweeper#drain} calls it; a batch that rolled back never reaches it. Functional: a
 * consumer with nothing to do after the commit is just a lambda over {@code accept}.
 * {@code drain} takes this type, not a bare {@code Consumer}, so a wrapper cannot lose
 * {@code afterCommit} silently.
 */
public interface BatchProcessor extends Consumer<EventOutboxRow> {

    default void afterCommit() {}
}
