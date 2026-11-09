package io.lumnik.hub.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.DeadLetterReplayer;
import io.lumnik.hub.event.EntityFilters;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.BatchProcessor;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.quarkus.mailer.Mail;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Outbox consumer "email": notification_rule-driven, own cursor — a webhook outage can no longer starve emails.
 *
 * <p>The batch RECORDS (#1848): one {@code notification_delivery} per (rule, event), rendered and leased, in the event's
 * own transaction. Once the batch has committed, with no transaction held (#900), each is sent from its row and its
 * outcome written. An SMTP failure is that delivery's, retried by the sweep up to three attempts, and touches none of its
 * neighbours; a render failure is recorded as a delivery given up. A crash between the commit and the send loses
 * nothing: the leased rows are sent by the sweep once the lease has run out.
 *
 * <p>At-least-once: a send is followed by the write of its outcome, and when that write cannot land (the hub stops, the
 * database is away) the delivery is sent again once its lease has run out. A rare duplicate is the price of never losing
 * a mail; only the outcome of the current lease holder is ever written.
 */
@ApplicationScoped
public class EmailConsumer implements DeadLetterReplayer {

    static final String CONSUMER_NAME = "email";
    private static final Logger LOG = Logger.getLogger(EmailConsumer.class);
    // Safe template token (kebab-case, project file convention): no slashes/dots, so a
    // DB-sourced rule.template can't traverse out of the email/ template root.
    static final Pattern SAFE_TEMPLATE = Pattern.compile("[a-z0-9-]+"); // shared with NotificationRuleResource — one truth
    /** The templates the hub ships, as the rule door names them when it refuses another (#1916). A template added to a build is accepted without being listed. */
    static final List<String> SHIPPED_TEMPLATES = List.of("event-notification", "decision-alert");

    /** The Qute id of a template by name — one spelling, for the render and for the rule door. */
    static String templateId(String name) { return "email/" + name + ".html"; }

    /** Deliveries retried per sweep. */
    private static final int RETRY_BATCH_SIZE = 100;
    /** How long a sweep owns a delivery it claimed: the time to send one mail, many times over. */
    static final int LEASE_SECONDS = 60;
    private static final String SET_SYSTEM_TENANT = "SET LOCAL app.current_tenant = '0'";

    @Inject OutboxSweeper sweeper;
    @Inject NotificationRuleRepository rules;
    @Inject NotificationDeliveries deliveries;
    @Inject MailTransport transport;
    @Inject Engine qute;
    @Inject ObjectMapper json;
    @Inject EntityManager em;

    @Scheduled(every = "5s", concurrentExecution = SKIP)
    void tick() {
        TenantContext.runAsSystem(() -> sweeper.drain(CONSUMER_NAME, 100, this::batchProcessor));
    }

    @Scheduled(every = "30s", concurrentExecution = SKIP)
    void retryTick() {
        TenantContext.runAsSystem(this::retryBatch);
    }

    /** A delivery the batch recorded, and the lease the batch gave it. */
    private record Pending(long id, OffsetDateTime leasedUntil, Mail mail) {}

    /**
     * The processor of one outbox batch. A tenant's enabled rules are read at its first event in
     * the batch and reused for the rest of it, never for the next one: a rule created, changed or
     * disabled is seen from the next batch on, so at most the rest of the current batch is late.
     */
    public BatchProcessor batchProcessor() {
        Map<Long, List<NotificationRule>> enabledByTenant = new HashMap<>();
        List<Pending> pending = new ArrayList<>();
        return new BatchProcessor() {
            @Override public void accept(EventOutboxRow row) {
                record(row, enabledByTenant, pending);
            }

            @Override public void afterCommit() {
                sendPending(pending);
            }
        };
    }

    @Override
    public String consumer() {
        return CONSUMER_NAME;
    }

    /**
     * Records the event's deliveries again (#1837). A rule that already has a delivery for the event is skipped (the
     * unit is the rule and the event, not the address), so a replay never creates a second delivery; the new ones are leased, not sent
     * here, and the retry sweep sends them once the lease has run out. A replay that recorded nothing redid nothing,
     * and says so: the letter stays open.
     */
    @Override
    public void replay(EventOutboxRow event, String targetKey) {
        if (record(event, new HashMap<>(), new ArrayList<>()) == 0)
            throw new IllegalStateException("no rule is left to mail for event " + event.getId());
    }

    /** @return how many deliveries were newly recorded, the ones given up included */
    private int record(EventOutboxRow row, Map<Long, List<NotificationRule>> enabledByTenant, List<Pending> pending) {
        // The news of a mail given up travels by the event log, not by mail: a rule on it would loop while the relay is down.
        if (NotificationDeliveries.GIVEN_UP_EVENT.equals(row.getEventType())) return 0;
        // entity narrows rules with an entity_filter; entity-less events reach every matching rule.
        List<NotificationRule> matching = enabledByTenant.computeIfAbsent(row.getTenantId(), rules::findEnabled).stream()
            .filter(r -> r.matches(row.getEventType()) && EntityFilters.passes(r.getEntityFilter(), row.getEntity()))
            .toList();
        // Microseconds: the precision PostgreSQL stores, so extendClaim can compare it exactly.
        OffsetDateTime leasedUntil =
            OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(LEASE_SECONDS).truncatedTo(ChronoUnit.MICROS);
        String subject = MailSubject.of(row.getEventType(), row.getPayload());
        int recorded = 0;
        for (NotificationRule rule : matching) {
            Rendered r = render(rule, row);
            if (r.failure() != null)
                LOG.warnf("notification_rule %d, event %d: %s — recorded as a delivery given up", rule.getId(), row.getId(), r.failure());
            Long id = deliveries.record(row.getTenantId(), rule.getId(), row.getId(), row.getEventType(),
                rule.getRecipient(), subject, r.html(), leasedUntil, r.failure());
            if (id == null) continue; // this rule already has its delivery for the event
            recorded++;
            if (r.failure() == null) pending.add(new Pending(id, leasedUntil, Mail.withHtml(rule.getRecipient(), subject, r.html())));
        }
        return recorded;
    }

    /** A rule's mail for an event: the body, or the kind of failure that kept it from being rendered. */
    record Rendered(String html, String failure) {}

    Rendered render(NotificationRule rule, EventOutboxRow row) {
        String template = rule.getTemplate();
        if (template == null || !SAFE_TEMPLATE.matcher(template).matches()) return new Rendered("", NotificationDeliveries.TEMPLATE_FAILURE + "unsafe name");
        Template tpl = qute.getTemplate(templateId(template));
        if (tpl == null) return new Rendered("", NotificationDeliveries.TEMPLATE_FAILURE + "unknown");
        try {
            // payload = the live Map, navigable in Qute ({payload.entity_key},
            // {#for f in payload.fields}); payloadJson = the serialized fallback.
            return new Rendered(tpl.data("eventType", row.getEventType())
                .data("source", row.getSource())
                .data("entity", row.getEntity() == null ? "—" : row.getEntity())
                .data("occurredAt", row.getOccurredAt().toString())
                .data("payload", row.getPayload())
                .data("payloadJson", renderPayload(row.getPayload()))
                .render(), null);
        } catch (RuntimeException e) {
            return new Rendered("", NotificationDeliveries.RENDER_FAILURE + e.getClass().getSimpleName());
        }
    }

    /**
     * Sends what the committed batch recorded, with no transaction open. A row whose event rolled back (a failed
     * event costs only itself, #943) no longer exists, and one the retry sweep took over is no longer ours: both fail
     * {@code extendClaim} and are skipped.
     */
    private void sendPending(List<Pending> pending) {
        for (Pending p : pending) {
            try {
                Instant lease = inNewTransaction(() -> deliveries.extendClaim(p.id(), p.leasedUntil(), LEASE_SECONDS));
                if (lease != null) send(p.id(), lease, p.mail());
                else LOG.debugf("Delivery %d no longer ours (event rolled back, or the retry sweep took it) — skipped", p.id());
            } catch (RuntimeException e) {
                // Left leased and unattempted: the retry sweep sends it when the lease expires.
                LOG.errorf(e, "Delivery %d failed before its outcome was recorded", p.id());
            }
        }
    }

    /**
     * Per-delivery retry: the failed row is the retry unit, never the event, so a recipient already served is not mailed
     * again. Deliberately not {@code @Transactional}: each row is claimed in a short transaction, sent with nothing
     * held, and its outcome written in a second one.
     */
    void retryBatch() {
        long afterId = 0;
        for (int i = 0; i < RETRY_BATCH_SIZE; i++) {
            long after = afterId;
            NotificationDeliveries.Claimed c = inNewTransaction(() -> deliveries.claimNext(after, LEASE_SECONDS));
            if (c == null) return;
            afterId = c.id(); // one attempt per row per sweep
            send(c.id(), c.lease(), Mail.withHtml(c.recipient(), c.subject(), c.body()));
        }
    }

    /** One attempt: the outcome is written whatever the mail server said — the kind of failure, never its message. */
    void send(long id, Instant lease, Mail mail) {
        String failure = null;
        try {
            transport.send(mail);
        } catch (RuntimeException e) {
            failure = MailFailure.describe(e);
            LOG.warnf("Email delivery %d failed (%s) — the attempt is counted", id, failure);
        }
        String outcome = failure;
        if (!inNewTransaction(() -> deliveries.finish(id, lease, outcome)))
            LOG.warnf("Delivery %d: the lease ran out during the send and another sweep owns it now — its outcome is not written", id);
    }

    <T> T inNewTransaction(Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.createNativeQuery(SET_SYSTEM_TENANT).executeUpdate();
            return work.get();
        });
    }

    /** Render the jsonb payload as JSON for the email body; never fail an event on a
     *  serialization hiccup — fall back to the Map's toString and log. */
    private String renderPayload(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Payload JSON render failed — falling back to toString");
            return String.valueOf(payload);
        }
    }
}
