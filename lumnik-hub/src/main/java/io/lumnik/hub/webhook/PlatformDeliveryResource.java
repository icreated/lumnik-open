package io.lumnik.hub.webhook;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the substrate sent out, and whether it arrived — flattened across every integration.
 *
 * <p>The per-integration history lives under {@code /api/tenants/{tid}/integrations/{id}/deliveries};
 * this one answers the question that one cannot: "what did NOT arrive?", without having to know
 * which integration to look at first. Tenant comes from the request context, never a parameter.
 */
@Path("/api/platform/deliveries")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission({"lm_admin", "lm_superadmin"})
public class PlatformDeliveryResource {

    @Inject WebhookDeliveryRepository deliveries;
    @Inject WebhookEndpointRepository endpoints;
    @Inject WebhookReplayService replayService;

    /** One delivery, carrying its integration's identity: flattened, a delivery without its
     *  integration says nothing, and the replay needs the id nowhere else on this row. */
    public record OutputRow(Long id, Long integrationId, String integration, String eventType,
                            Integer httpStatus, String error, Integer durationMs,
                            OffsetDateTime attemptedAt, boolean succeeded) {}

    @GET
    @Transactional
    public List<OutputRow> list(@QueryParam("limit") @DefaultValue("200") int limit) {
        List<WebhookDelivery> rows =
            deliveries.recentForTenant(RequireTenant.current(), Math.min(Math.max(limit, 1), 500));
        Map<Long, String> names = namesFor(rows);
        return rows.stream()
            .map(d -> new OutputRow(d.getId(), d.getEndpointId(),
                names.getOrDefault(d.getEndpointId(), "(deleted)"), d.getEventType(),
                d.getHttpStatus(), d.getError(), d.getDurationMs(), d.getAttemptedAt(),
                d.isSucceeded()))
            .toList();
    }

    /** One extra query for the whole page, never one per row. An integration deleted after its
     *  deliveries were recorded leaves rows with no name — shown as "(deleted)" rather than hidden. */
    private Map<Long, String> namesFor(List<WebhookDelivery> rows) {
        List<Long> ids = rows.stream().map(WebhookDelivery::getEndpointId).distinct().toList();
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) return names;
        // Live only: an unfiltered find resolved deleted integrations too, so the "(deleted)"
        // this javadoc promises never appeared — and since replay refuses a deleted endpoint, the
        // row showed a live-looking name beside a 404 with nothing to explain it.
        for (WebhookEndpoint e : endpoints.find("id in ?1 AND archivedAt IS NULL AND deletedAt IS NULL", ids).list()) {
            names.put(e.getId(), e.getName());
        }
        return names;
    }

    /** Replay one delivery, addressed by the delivery itself — the screen has it in hand, and the
     *  integration id would be redundant. A delivery belonging to another tenant is a 404, not a
     *  403: existence is not something to confirm across a tenant boundary. */
    @POST @Path("/{id}/replay")
    @Transactional
    public WebhookDeliveryService.DeliveryResult replay(@PathParam("id") Long id) {
        WebhookDelivery d = deliveries.findById(id);
        // getTenantId() is a Long: compare with equals, never ==, or two identical ids outside
        // the Integer cache would compare false and hide a tenant's own delivery from them.
        if (d == null || !java.util.Objects.equals(d.getTenantId(), RequireTenant.current()))
            throw new NotFoundException();
        // Deleting an integration closes this door too — see WebhookEndpointRepository.findLiveById.
        WebhookEndpoint e = endpoints.findLiveById(d.getEndpointId());
        if (e == null) throw new NotFoundException();
        return replayService.replay(e, d);
    }
}
