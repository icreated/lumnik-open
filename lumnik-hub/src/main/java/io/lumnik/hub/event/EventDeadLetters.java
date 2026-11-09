package io.lumnik.hub.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What an outbox consumer failed on, kept (#1837). The sweeper never lets a failure hold a cursor, so it cannot
 * retry; it leaves the failure here instead, in the transaction that moves the cursor, for an integrator to see and
 * replay. A consumer that fails on one of several targets of an event (a Process among the Processes of a table)
 * records that target, so the replay redoes it alone.
 *
 * <p>Every method runs in the caller's transaction: a record whose own write fails fails the caller, which is what
 * keeps the cursor from moving past a failure nobody kept.
 */
@ApplicationScoped
public class EventDeadLetters {

    /** The consumer's whole handling of the event: it failed before it knew its targets. */
    public static final String WHOLE_EVENT = "";

    /** A page of the list: the most recent letters, enough to see a failing consumer without paging. */
    static final int LIST_LIMIT = 200;

    @Inject EntityManager em;
    @Inject Instance<DeadLetterReplayer> replayers;
    @Inject ObjectMapper json;

    /** One letter, as the integrator sees it: what failed, on which event, and whether it was redone. */
    public record Letter(long id, String consumer, long eventId, String eventType, String entity, String entityKey,
                         String target, String error, Instant failedAt, Instant replayedAt) {}

    /** What a replay came to; the consumer is the letter's, {@code null} when there is none. */
    public enum Status { DONE, NOT_FOUND, ALREADY_REPLAYED, NO_REPLAY }

    public record Replay(Status status, String consumer) {}

    /**
     * Keeps {@code eventId}'s failure for {@code consumer}. The envelope is copied from the outbox row by the
     * statement itself, so a replay after the outbox's retention still has the event; a failure that comes again
     * reopens the row.
     */
    public void record(String consumer, long eventId, String targetKey, Throwable cause) {
        em.createNativeQuery("""
            INSERT INTO core.event_dead_letter (consumer_name, event_id, tenant_id, target_key, error, envelope)
            SELECT :consumer, o.id, o.tenant_id, :target, :failure, to_jsonb(o) FROM core.event_outbox o WHERE o.id = :id
            ON CONFLICT (consumer_name, event_id, target_key)
            DO UPDATE SET error = EXCLUDED.error, failed_at = now(), replayed_at = NULL
            """)
          .setParameter("consumer", consumer)
          .setParameter("id", eventId)
          .setParameter("target", targetKey)
          .setParameter("failure", describe(cause))
          .executeUpdate();
    }

    /**
     * What is kept of a failure: the SQLSTATE when a database said no, else the exception's class — never its
     * message, which can quote a row (#1090).
     */
    static String describe(Throwable cause) {
        String state = Jdbc.sqlStateOf(cause);
        return state != null ? "SQLSTATE " + state : cause.getClass().getSimpleName();
    }

    /**
     * The caller's tenant's letters, most recent first — filtered here as well as by row-level security, which the
     * database role of a test or a migration does not enforce.
     *
     * @param consumer only this consumer's, or {@code null} for all
     * @param open only the ones not yet replayed
     */
    @SuppressWarnings("unchecked")
    public List<Letter> list(String consumer, boolean open) {
        List<Object[]> rows = em.createNativeQuery("""
            SELECT id, consumer_name, event_id, envelope->>'event_type', envelope->>'entity', envelope->>'entity_key',
                   target_key, error, failed_at, replayed_at
            FROM core.event_dead_letter
            WHERE tenant_id = :tenant AND (CAST(:consumer AS text) IS NULL OR consumer_name = :consumer) AND (NOT :open OR replayed_at IS NULL)
            ORDER BY failed_at DESC, id DESC
            """)
          .setParameter("tenant", CurrentTenantHolder.get())
          .setParameter("consumer", consumer)
          .setParameter("open", open)
          .setMaxResults(LIST_LIMIT)
          .getResultList();
        return rows.stream().map(r -> new Letter(((Number) r[0]).longValue(), (String) r[1], ((Number) r[2]).longValue(),
            (String) r[3], (String) r[4], (String) r[5], (String) r[6], (String) r[7],
            (Instant) r[8], (Instant) r[9])).toList();
    }

    /**
     * Redoes what letter {@code id} kept and closes it. A letter replayed is not replayed again, whatever the consumer
     * would make of it; a replay that fails leaves the letter open and says so to the caller.
     */
    public Replay replay(long id) {
        // A transaction of its own: a replay that fails rolls back with it, and the caller can say so — a catch around
        // a joined transaction would come too late (it commits, or fails, after the method).
        Long tenant = CurrentTenantHolder.get();
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object[]> found = em.createNativeQuery(
                    "SELECT consumer_name, replayed_at IS NOT NULL FROM core.event_dead_letter WHERE id = :id AND tenant_id = :tenant")
                .setParameter("id", id).setParameter("tenant", tenant).getResultList();
            if (found.isEmpty()) return new Replay(Status.NOT_FOUND, null);
            String consumer = (String) found.get(0)[0];
            if ((Boolean) found.get(0)[1]) return new Replay(Status.ALREADY_REPLAYED, consumer);
            Optional<DeadLetterReplayer> replayer = replayers.stream().filter(r -> r.consumer().equals(consumer)).findFirst();
            if (replayer.isEmpty()) return new Replay(Status.NO_REPLAY, consumer);

            // Claimed by the statement that closes it: of two replays of one letter, one gets the row back. The replayer
            // runs in this transaction, so a replay that fails gives the claim back with everything else.
            @SuppressWarnings("unchecked")
            List<Object[]> claimed = em.createNativeQuery("""
                    UPDATE core.event_dead_letter SET replayed_at = now()
                    WHERE id = :id AND tenant_id = :tenant AND replayed_at IS NULL
                    RETURNING target_key, CAST(envelope AS text)
                    """)
                .setParameter("id", id).setParameter("tenant", tenant).getResultList();
            if (claimed.isEmpty()) return new Replay(Status.ALREADY_REPLAYED, consumer);
            replayer.get().replay(EventOutboxRow.restored(envelopeOf((String) claimed.get(0)[1])), (String) claimed.get(0)[0]);
            return new Replay(Status.DONE, consumer);
        });
    }

    private Map<String, Object> envelopeOf(String text) {
        try {
            return json.readValue(text, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a dead letter's envelope is not JSON", e);
        }
    }
}
