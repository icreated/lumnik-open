package io.lumnik.hub.notify;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.api.TenantWide;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;

/**
 * What the hub did with the e-mails its rules asked for (#1902): the twin of {@code PlatformDeliveryResource}, which
 * answers the same question for webhooks. A separate door because the two kinds of delivery have their own ids: a
 * replay addressed by a bare number must know which table it means.
 *
 * <p>A row carries the rule's recipient, the event, how it stands and the kind of failure — never the mail, whose
 * body and subject can quote the event's payload. Tenant from the request context, like the rules door.
 */
@TenantWide
@Path("/api/platform/notification-deliveries")
@Produces(MediaType.APPLICATION_JSON)
@RequiresPermission({"lm_admin", "lm_superadmin"})
public class NotificationDeliveryResource {

    @Inject NotificationDeliveries deliveries;
    @Inject MailReplayService replays;

    public record DeliveryRow(Long id, Long ruleId, String recipient, String eventType, String status, int attempts,
                              String error, Instant at) {}

    /** The new state of the delivery a replay has just attempted. */
    public record Replayed(Long id, String status) {}

    @GET
    @Transactional
    public List<DeliveryRow> list(@QueryParam("limit") @DefaultValue("200") int limit) {
        return deliveries.list(RequireTenant.current(), Math.min(Math.max(limit, 1), 500)).stream()
            .map(d -> new DeliveryRow(d.id(), d.ruleId(), d.recipient(), d.eventType(), d.status(), d.attempts(),
                d.error(), d.at()))
            .toList();
    }

    /** Re-sends one mail that was given up. Another tenant's delivery is a 404, as for a webhook's. */
    @POST @Path("/{id}/replay")
    public Replayed replay(@PathParam("id") Long id) {
        return new Replayed(id, replays.replay(RequireTenant.current(), id));
    }
}
