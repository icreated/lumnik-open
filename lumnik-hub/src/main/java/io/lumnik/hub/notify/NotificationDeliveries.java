package io.lumnik.hub.notify;

import io.lumnik.hub.event.EventOutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The rows of {@code core.notification_delivery} (#1848): one mail per rule and event, recorded by the outbox batch and
 * sent from its row. The claim and lease statements are the twin of {@code WebhookDeliveryRepository}'s: a change to the
 * retry rules is made in both. Every method runs in the caller's transaction and, for the sweep's, under the system tenant.
 */
@ApplicationScoped
public class NotificationDeliveries {

    /** A delivery as the retry sweep claims it: what is needed to send it, detached from the claim's transaction. */
    record Claimed(long id, String recipient, String subject, String body, Instant lease) {}

    /** Why a mail was given up before it was ever sent: the prefix of {@code error}, which holds a kind and never a message. */
    static final String TEMPLATE_FAILURE = "template: ";
    static final String RENDER_FAILURE = "render: ";

    /** The event a delivery given up is told by (#1906); the email consumer never mails it: a mail does not tell about mails. */
    static final String GIVEN_UP_EVENT = "notification.mail.given-up";

    /** The words the integrator reads in the status column; the replay switches on the same ones. */
    static final String SENT = "sent";
    static final String RETRYING = "retrying";
    static final String GAVE_UP_SEND = "given up: send";
    static final String GAVE_UP_RENDER = "given up: render";

    /** A delivery as the integrator reads it: who, which event, how it stands. Never the mail itself. */
    record Listed(long id, long ruleId, long eventId, String recipient, String eventType, int attempts, boolean succeeded,
                  String error, Instant at) {
        String status() {
            if (succeeded) return SENT;
            if (attempts < 3) return RETRYING;
            boolean render = error != null && (error.startsWith(TEMPLATE_FAILURE) || error.startsWith(RENDER_FAILURE));
            return render ? GAVE_UP_RENDER : GAVE_UP_SEND;
        }
    }

    /** The columns {@link #listed} reads, in its order. */
    private static final String LISTED = """
        SELECT id, rule_id, event_id, recipient, event_type, attempts, succeeded, error, COALESCE(attempted_at, created_at)
          FROM core.notification_delivery
        """;

    @Inject EntityManager em;

    /**
     * Records one delivery. Pending ({@code attempts = 0}, leased until {@code leasedUntil}) when {@code failure} is
     * null; given up at once ({@code attempts = 3}, no lease) when it is not — a render cannot succeed on a retry.
     *
     * @return the new delivery's id, or {@code null} when this rule already has a delivery for the event
     */
    Long record(long tenantId, long ruleId, long eventId, String eventType, String recipient, String subject,
                String body, OffsetDateTime leasedUntil, String failure) {
        List<?> id = em.createNativeQuery("""
            INSERT INTO core.notification_delivery
                (tenant_id, rule_id, event_id, event_type, recipient, subject, body, attempts, error, claimed_until)
            VALUES (:tenant, :rule, :event, :type, :recipient, :subject, :body,
                    CASE WHEN CAST(:failure AS text) IS NULL THEN 0 ELSE 3 END, :failure,
                    CASE WHEN CAST(:failure AS text) IS NULL THEN CAST(:lease AS timestamptz) END)
            ON CONFLICT (rule_id, event_id) DO NOTHING
            RETURNING id
            """)
            .setParameter("tenant", tenantId)
            .setParameter("rule", ruleId)
            .setParameter("event", eventId)
            .setParameter("type", eventType)
            .setParameter("recipient", recipient)
            .setParameter("subject", subject)
            .setParameter("body", body)
            .setParameter("failure", failure)
            .setParameter("lease", leasedUntil)
            .getResultList();
        if (id.isEmpty()) return null;
        long created = ((Number) id.get(0)).longValue();
        if (failure != null) announceGivenUp(tenantId, created, ruleId, eventType, failure);
        return created;
    }

    /**
     * Tells the event log that a delivery is given up (#1906): a road that does not depend on the mail, so a webhook can
     * carry it. Written in the transaction that gives the delivery up; the failure is a kind, never the mail.
     */
    private void announceGivenUp(long tenantId, long deliveryId, long ruleId, String eventType, String failure) {
        em.persist(EventOutboxRow.of(tenantId, GIVEN_UP_EVENT, Map.of(
            "delivery_id", deliveryId, "rule_id", ruleId, "event_type", eventType, "failure", failure)));
    }

    /**
     * Claims the next unresolved delivery with an id above {@code afterId} for {@code leaseSeconds}, or returns
     * {@code null}. The lease is a column, so it outlives this transaction: the send happens with nothing held, and an
     * expired lease is reclaimable — a sweep that dies mid-send must not strand its row. {@code SKIP LOCKED} keeps two
     * sweeps (two nodes) from taking the same row. {@code afterId} gives one attempt per row per sweep.
     */
    Claimed claimNext(long afterId, int leaseSeconds) {
        List<?> claimed = em.createNativeQuery("""
            UPDATE core.notification_delivery
               SET claimed_until = now() + (:lease * INTERVAL '1 second')
             WHERE id = (
                 SELECT id FROM core.notification_delivery
                  WHERE id > :after
                    AND succeeded = FALSE AND attempts < 3
                    AND (claimed_until IS NULL OR claimed_until < now())
                  ORDER BY id
                  LIMIT 1
                  FOR UPDATE SKIP LOCKED)
            RETURNING id, recipient, subject, body, claimed_until
            """)
            .setParameter("lease", leaseSeconds)
            .setParameter("after", afterId)
            .getResultList();
        if (claimed.isEmpty()) return null;
        Object[] c = (Object[]) claimed.get(0);
        return new Claimed(((Number) c[0]).longValue(), (String) c[1], (String) c[2], (String) c[3], (Instant) c[4]);
    }

    /**
     * Renews the lease of a delivery the batch just recorded, if it is still exactly as the batch left it: never
     * attempted and leased until {@code leasedUntil}. A different lease means the retry sweep took it; no row means the
     * event rolled back. Either way the caller does not send it.
     *
     * @return the renewed lease, the token {@link #finish} asks for, or {@code null} when the delivery is not the caller's
     */
    Instant extendClaim(long id, OffsetDateTime leasedUntil, int leaseSeconds) {
        List<?> renewed = em.createNativeQuery("""
            UPDATE core.notification_delivery
               SET claimed_until = now() + (:lease * INTERVAL '1 second')
             WHERE id = :id AND attempts = 0 AND claimed_until = :expected
            RETURNING claimed_until
            """)
            .setParameter("lease", leaseSeconds)
            .setParameter("id", id)
            .setParameter("expected", leasedUntil)
            .getResultList();
        return renewed.isEmpty() ? null : (Instant) renewed.get(0);
    }

    /**
     * Writes the outcome of one attempt and releases the lease — only while {@code lease} is still the delivery's: a
     * sender whose lease ran out and was taken over must not overwrite the new owner's outcome. {@code failure} is a
     * kind, never a message.
     *
     * @return whether the outcome was written
     */
    boolean finish(long id, Instant lease, String failure) {
        List<?> written = em.createNativeQuery("""
            UPDATE core.notification_delivery
               SET attempts = attempts + 1, attempted_at = now(), succeeded = (CAST(:failure AS text) IS NULL),
                   error = :failure, claimed_until = NULL
             WHERE id = :id AND claimed_until = :lease
            RETURNING tenant_id, rule_id, event_type, attempts
            """)
            .setParameter("id", id)
            .setParameter("lease", lease)
            .setParameter("failure", failure)
            .getResultList();
        if (written.isEmpty()) return false;
        Object[] row = (Object[]) written.get(0);
        // Told when the delivery BECOMES given up: a replay's fourth attempt adds nothing.
        if (failure != null && ((Number) row[3]).intValue() == 3)
            announceGivenUp(((Number) row[0]).longValue(), id, ((Number) row[1]).longValue(), (String) row[2], failure);
        return true;
    }

    /**
     * The tenant's deliveries, given-up ones first, then those still being retried, then those sent; newest first in
     * each. The tenant is named in the statement: the row-level policy is no reason to leave a door open.
     */
    List<Listed> list(long tenantId, int limit) {
        List<?> rows = em.createNativeQuery(LISTED + """
             WHERE tenant_id = :tenant
             ORDER BY CASE WHEN succeeded THEN 2 WHEN attempts >= 3 THEN 0 ELSE 1 END,
                      COALESCE(attempted_at, created_at) DESC, id DESC
             LIMIT :limit
            """)
            .setParameter("tenant", tenantId)
            .setParameter("limit", limit)
            .getResultList();
        return rows.stream().map(r -> listed((Object[]) r)).toList();
    }

    /** One delivery of the tenant, or {@code null}: another tenant's is not one. */
    Listed find(long tenantId, long id) {
        List<?> rows = em.createNativeQuery(LISTED + """
             WHERE tenant_id = :tenant AND id = :id
            """)
            .setParameter("tenant", tenantId)
            .setParameter("id", id)
            .getResultList();
        return rows.isEmpty() ? null : listed((Object[]) rows.get(0));
    }

    private static Listed listed(Object[] c) {
        return new Listed(((Number) c[0]).longValue(), ((Number) c[1]).longValue(), ((Number) c[2]).longValue(),
            (String) c[3], (String) c[4], ((Number) c[5]).intValue(), (Boolean) c[6], (String) c[7], (Instant) c[8]);
    }

    /**
     * Takes a delivery that was given up for {@code leaseSeconds}, to send it once more — and, when {@code body} is not
     * null, with the body a render has just produced. Only the delivery's own row is touched: a rule and an event never
     * get a second one. An unexpired lease means a replay (or a sweep) holds it, and the answer is {@code null}.
     */
    Claimed lease(long tenantId, long id, int leaseSeconds, String body) {
        List<?> taken = em.createNativeQuery("""
            UPDATE core.notification_delivery
               SET body = COALESCE(CAST(:body AS text), body),
                   claimed_until = now() + (:lease * INTERVAL '1 second')
             WHERE id = :id AND tenant_id = :tenant AND succeeded = FALSE AND attempts >= 3
               AND (claimed_until IS NULL OR claimed_until < now())
            RETURNING id, recipient, subject, body, claimed_until
            """)
            .setParameter("body", body)
            .setParameter("lease", leaseSeconds)
            .setParameter("id", id)
            .setParameter("tenant", tenantId)
            .getResultList();
        if (taken.isEmpty()) return null;
        Object[] c = (Object[]) taken.get(0);
        return new Claimed(((Number) c[0]).longValue(), (String) c[1], (String) c[2], (String) c[3], (Instant) c[4]);
    }

    /** The outbox event a delivery was recorded for, or {@code null} once the event log has purged it. */
    EventOutboxRow findEvent(long tenantId, long eventId) {
        return em.createQuery("FROM EventOutboxRow e WHERE e.id = ?1 AND e.tenantId = ?2", EventOutboxRow.class)
            .setParameter(1, eventId).setParameter(2, tenantId)
            .getResultStream().findFirst().orElse(null);
    }
}
