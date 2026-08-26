package io.lumnik.hub.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;

/**
 * Re-send a delivery that is already on record, and record the attempt.
 *
 * <p>Extracted so the tenant-in-URL endpoint and its platform sibling run the SAME code: two
 * addresses for one act. A replay never overwrites the original row — the history of what was
 * attempted is the point.
 */
@ApplicationScoped
public class WebhookReplayService {

    @Inject WebhookDeliveryService deliveryService;
    @Inject WebhookDeliveryRepository deliveryRepo;
    @Inject ObjectMapper mapper;

    public WebhookDeliveryService.DeliveryResult replay(WebhookEndpoint ep, WebhookDelivery original) {
        JsonNode payloadNode = original.getPayload();
        String payloadBody;
        try { payloadBody = mapper.writeValueAsString(payloadNode); }
        catch (Exception ex) { throw new InternalServerErrorException(ex); }

        var r = deliveryService.deliver(ep, original.getEventType(), payloadBody);

        WebhookDelivery rec = new WebhookDelivery();
        rec.setTenantId(original.getTenantId());
        rec.setEndpointId(ep.getId());
        rec.setEventType(original.getEventType());
        rec.setPayload(payloadNode);
        rec.setHttpStatus(r.httpStatus());
        rec.setResponseBody(r.responseBody());
        rec.setError(r.error());
        rec.setDurationMs(r.durationMs());
        // Without this, `succeeded` keeps its false default even on a 200 — the retry sweep's
        // `succeeded = FALSE AND attempts < 3` window re-claims a SUCCESSFUL replay and
        // re-delivers it to the third party up to twice more.
        rec.setSucceeded(r.ok());
        deliveryRepo.persist(rec);
        return r;
    }
}
