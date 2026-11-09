package io.lumnik.hub.event;

/**
 * A consumer that can redo what a dead letter kept (#1837). The replay runs in the integrator's request, under the
 * letter's tenant, and must be safe to repeat: a consumer whose work is not (it would serve a recipient twice) does
 * not implement this, and its letters are seen, not replayed.
 */
public interface DeadLetterReplayer {

    /** The consumer name the sweeper keeps its cursor and its letters under. */
    String consumer();

    /**
     * @param event the envelope as it was first swept
     * @param targetKey what failed ({@link EventDeadLetters#WHOLE_EVENT} for the whole event, else the consumer's own key)
     */
    void replay(EventOutboxRow event, String targetKey);
}
