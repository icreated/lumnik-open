package io.lumnik.hub.notify;

import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.event.EventOutboxRow;
import io.quarkus.mailer.Mail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;

/**
 * Sends one given-up mail again (#1902), from the delivery that already exists: a rule never gets a second delivery
 * for an event, and a mail that is sent is never sent twice. A mail given up on send is sent as it was recorded; one
 * given up on render is rendered again from its event and its rule first, since a fixed template is the usual reason
 * to ask. Not {@code @Transactional}: each step opens its own, so the send holds none (#900).
 */
@ApplicationScoped
public class MailReplayService {

    @Inject NotificationDeliveries deliveries;
    @Inject NotificationRuleRepository rules;
    @Inject EmailConsumer consumer;

    /** @return the delivery's status once the new attempt is written */
    public String replay(long tenantId, long id) {
        NotificationDeliveries.Listed found = consumer.inNewTransaction(() -> deliveries.find(tenantId, id));
        if (found == null) throw new NotFoundException("delivery " + id + " not found");
        String status = found.status();
        if (status.equals(NotificationDeliveries.SENT))
            throw refused("this mail was sent; it is not sent twice");
        if (status.equals(NotificationDeliveries.RETRYING))
            throw refused("this mail is still being retried (" + found.attempts() + " of 3 attempts): its outcome is not known yet");

        String body = status.equals(NotificationDeliveries.GAVE_UP_RENDER) ? rendered(tenantId, found) : null;
        NotificationDeliveries.Claimed taken = consumer.inNewTransaction(
            () -> deliveries.lease(tenantId, id, EmailConsumer.LEASE_SECONDS, body));
        if (taken == null) throw refused("another replay of this mail is under way");

        consumer.send(taken.id(), taken.lease(), Mail.withHtml(taken.recipient(), taken.subject(), taken.body()));
        return consumer.inNewTransaction(() -> deliveries.find(tenantId, id)).status();
    }

    /** The mail a render failure never produced, from the event and the rule as they are now. */
    private String rendered(long tenantId, NotificationDeliveries.Listed found) {
        record Inputs(EventOutboxRow event, NotificationRule rule) {}
        Inputs in = consumer.inNewTransaction(
            () -> new Inputs(deliveries.findEvent(tenantId, found.eventId()), rules.findById(found.ruleId())));
        if (in.event() == null) throw refused("the event of this mail is gone (the event log is purged after its retention): it cannot be rendered again");
        if (in.rule() == null || !in.rule().getTenantId().equals(tenantId)) throw refused("the rule of this mail was removed: it cannot be rendered again");
        EmailConsumer.Rendered r = consumer.render(in.rule(), in.event());
        if (r.failure() != null) throw refused("this mail still cannot be rendered (" + r.failure() + "): nothing was sent");
        return r.html();
    }

    private static ClientErrorException refused(String why) {
        return new ClientErrorException(ApiErrors.conflict(why));
    }
}
