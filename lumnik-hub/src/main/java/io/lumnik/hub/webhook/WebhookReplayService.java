package io.lumnik.hub.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.InternalServerErrorException;

/**
 * Re-send a delivery that is already on record, and record the attempt.
 *
 * <p>Extracted so the tenant-in-URL endpoint and its platform sibling run the SAME code: two
 * addresses for one act. A replay never overwrites the original row — the history of what was
 * attempted is the point.
 *
 * <p>{@code NEVER}: the send is a POST to the customer's endpoint, up to the delivery service's
 * timeouts, and a caller's transaction would hold its pooled connection for all of it — and see
 * the attempt, recorded in a short transaction of its own, commit apart from its own work.
 */
@ApplicationScoped
public class WebhookReplayService {

    @Inject WebhookDeliveryService deliveryService;
    @Inject WebhookDeliveryRepository deliveryRepo;
    @Inject ObjectMapper mapper;

    @Transactional(Transactional.TxType.NEVER)
    public WebhookDeliveryService.DeliveryResult replay(WebhookEndpoint ep, WebhookDelivery original) {
        JsonNode payloadNode = original.getPayload();
        String payloadBody;
        try { payloadBody = mapper.writeValueAsString(payloadNode); }
        catch (Exception ex) { throw new InternalServerErrorException(ex); }

        var r = deliveryService.deliver(ep, original.getEventType(), original.getEventId(), payloadBody);

        WebhookDelivery rec = new WebhookDelivery();
        rec.setTenantId(original.getTenantId());
        rec.setEndpointId(ep.getId());
        rec.setEventType(original.getEventType());
        // Carried from the original, not re-derived: a replay-of-a-replay must keep the same
        // outbox event id all the way down the chain, so every attempt correlates to the same event.
        rec.setEventId(original.getEventId());
        rec.setPayload(payloadNode);
        rec.setHttpStatus(r.httpStatus());
        rec.setResponseBody(r.responseBody());
        rec.setError(r.error());
        rec.setDurationMs(r.durationMs());
        // Without this, `succeeded` keeps its false default even on a 200 — the retry sweep's
        // `succeeded = FALSE AND attempts < 3` window re-claims a SUCCESSFUL replay and
        // re-delivers it to the third party up to twice more.
        rec.setSucceeded(r.ok());
        QuarkusTransaction.requiringNew().run(() -> deliveryRepo.persist(rec));
        return r;
    }
}
