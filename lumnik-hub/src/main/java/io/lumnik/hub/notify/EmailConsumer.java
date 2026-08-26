package io.lumnik.hub.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.regex.Pattern;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Outbox consumer "email": notification_rule-driven, own cursor — a webhook
 * outage can no longer starve emails. Best-effort v1: mailer failure logs
 * and the cursor advances.
 */
@ApplicationScoped
public class EmailConsumer {

    static final String CONSUMER_NAME = "email";
    private static final Logger LOG = Logger.getLogger(EmailConsumer.class);
    // Safe template token (kebab-case, project file convention): no slashes/dots, so a
    // DB-sourced rule.template can't traverse out of the email/ template root.
    static final Pattern SAFE_TEMPLATE = Pattern.compile("[a-z0-9-]+"); // shared with NotificationRuleResource — one truth

    @Inject OutboxSweeper sweeper;
    @Inject NotificationRuleRepository rules;
    @Inject Mailer mailer;
    @Inject Engine qute;
    @Inject ObjectMapper json;

    @Scheduled(every = "5s", concurrentExecution = SKIP)
    void tick() {
        TenantContext.runAsSystem(() -> sweeper.sweep(CONSUMER_NAME, 100, this::process));
    }

    public void process(EventOutboxRow row) {
        for (NotificationRule rule : rules.findMatching(row.getTenantId(), row.getEventType(), row.getEntity())) {
            String template = rule.getTemplate();
            if (template == null || !SAFE_TEMPLATE.matcher(template).matches()) {
                LOG.warnf("notification_rule for %s: unsafe/invalid template name '%s' — skipped",
                          rule.getRecipient(), template);
                continue;
            }
            Template tpl = qute.getTemplate("email/" + template + ".html");
            if (tpl == null) {
                LOG.warnf("notification_rule for %s: unknown template '%s' — skipped",
                          rule.getRecipient(), rule.getTemplate());
                continue;
            }
            String html;
            try {
                // payload = the live Map, navigable in Qute ({payload.entity_key},
                // {#for f in payload.fields}); payloadJson = the serialized fallback.
                html = tpl.data("eventType", row.getEventType())
                    .data("source", row.getSource())
                    .data("entity", row.getEntity() == null ? "—" : row.getEntity())
                    .data("occurredAt", row.getOccurredAt().toString())
                    .data("payload", row.getPayload())
                    .data("payloadJson", renderPayload(row.getPayload()))
                    .render();
                mailer.send(Mail.withHtml(rule.getRecipient(),
                        "lumnik event: " + row.getEventType(), html));
            } catch (RuntimeException e) {
                // Best-effort v1: log and continue to remaining rules; the sweeper advances the
                // cursor regardless (it catches at the event level). Per-rule isolation means a
                // broken mailer doesn't starve other recipients of the same event.
                LOG.errorf(e, "Email send failed for rule recipient %s event %s — skipped",
                           rule.getRecipient(), row.getEventType());
            }
        }
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
