package io.lumnik.hub.webhook;

import io.lumnik.identity.Require;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.event.EventRegistry;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/tenants/{tid}/integrations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
public class IntegrationResource {

    @Inject WebhookEndpointRepository repo;
    @Inject SecretCipher cipher;
    @Inject WebhookDeliveryRepository deliveryRepo;
    @Inject WebhookDeliveryService deliveryService;
    @Inject WebhookReplayService replayService;
    @Inject EventRegistry events;
    @Inject ObjectMapper mapper;

    // ── CRUD ────────────────────────────────────────────────────────────────

    @GET
    @Transactional
    public List<IntegrationDto> list(@PathParam("tid") Long tid) {
        requireTenant(tid);
        return repo.listLiveForTenant(tid).stream().map(IntegrationDto::from).toList();
    }

    @GET @Path("/{id}")
    @Transactional
    public IntegrationDto one(@PathParam("tid") Long tid, @PathParam("id") Long id) {
        requireTenant(tid);
        return IntegrationDto.from(Require.owned(repo, id, tid, "Integration"));
    }

    @POST
    @Transactional
    public Response create(@PathParam("tid") Long tid, IntegrationCreate in) {
        requireTenant(tid);
        WebhookEndpoint e = new WebhookEndpoint();
        e.setTenantId(tid);
        e.setName(in.name());
        e.setTargetUrl(in.targetUrl());
        e.setPlatform(in.platform() == null ? "generic" : in.platform());
        e.setAuthMode(in.authMode() == null ? AuthMode.HMAC : in.authMode());
        if (in.secret() != null && !in.secret().isBlank())
            e.setSecretEncrypted(cipher.encrypt(in.secret()));
        e.setEventTypes(in.eventTypes());
        e.setEnabled(in.enabled() == null || in.enabled());
        repo.persist(e);
        return Response.status(201).entity(IntegrationDto.from(e)).build();
    }

    @PUT @Path("/{id}")
    @Transactional
    public IntegrationDto update(@PathParam("tid") Long tid, @PathParam("id") Long id,
                                 IntegrationUpdate in) {
        requireTenant(tid);
        WebhookEndpoint e = Require.owned(repo, id, tid, "Integration");
        if (in.name()       != null) e.setName(in.name());
        if (in.targetUrl()  != null) e.setTargetUrl(in.targetUrl());
        if (in.platform()   != null) e.setPlatform(in.platform());
        if (in.authMode()   != null) e.setAuthMode(in.authMode());
        if (in.eventTypes() != null) e.setEventTypes(in.eventTypes());
        if (in.enabled()    != null) e.setEnabled(in.enabled());
        if (in.secret() != null && !in.secret().isBlank())
            e.setSecretEncrypted(cipher.encrypt(in.secret()));
        return IntegrationDto.from(e);
    }

    @DELETE @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("tid") Long tid, @PathParam("id") Long id) {
        requireTenant(tid);
        WebhookEndpoint e = Require.owned(repo, id, tid, "Integration");
        e.softDelete();
        return Response.noContent().build();
    }

    // ── Test / Deliveries / Replay ──────────────────────────────────────────

    public record TestRequest(String eventType) {}

    public record TestResponse(boolean ok, Integer httpStatus, String responseBody,
                               String error, int durationMs) {}

    @POST @Path("/{id}/test")
    public TestResponse test(@PathParam("tid") Long tid, @PathParam("id") Long id,
                             TestRequest body) {
        requireTenant(tid);
        // /test DELIVERS — deliveryService.deliver below is a real POST — so it is a send door and
        // obeys the same liveness rule as the retry and the two replays. Scoped to the tenant IN
        // THE QUERY, not by findLiveById: the id is a path parameter, and a bare by-id lookup would
        // let one tenant fire a test webhook at ANOTHER tenant's endpoint URL. And read INSIDE a
        // transaction of its own: the tenant is bound with SET LOCAL, which is transaction-scoped,
        // so this lookup answered 404 on every RLS-enforced hub until it was. Its own, and not the
        // method's, so the delivery below — up to the HttpClient's timeouts — holds no connection.
        WebhookEndpoint e = QuarkusTransaction.requiringNew().call(() -> repo.findLiveForTenant(id, tid));
        if (e == null) throw new NotFoundException("endpoint " + id + " not found");

        var descriptor = events.find(body.eventType())
            .orElseThrow(() -> new BadRequestException("Unknown event type: " + body.eventType()));

        Map<String, Object> sample = new LinkedHashMap<>();
        descriptor.payload().forEach((k, v) -> sample.put(k, sampleValueFor(v)));

        String payload;
        try { payload = mapper.writeValueAsString(sample); }
        catch (Exception ex) { throw new InternalServerErrorException(ex); }

        var r = deliveryService.deliver(e, body.eventType(), payload);
        return new TestResponse(r.ok(), r.httpStatus(), r.responseBody(), r.error(), r.durationMs());
    }

    private static Object sampleValueFor(String type) {
        return switch (type) {
            case "long", "int", "integer" -> 0;
            case "map", "object" -> Map.of();
            case "list", "array" -> List.of();
            case "boolean" -> false;
            default -> "sample";
        };
    }

    @GET @Path("/{id}/deliveries")
    @Transactional
    public List<DeliveryRow> deliveries(@PathParam("tid") Long tid, @PathParam("id") Long id,
                                        @QueryParam("limit") @DefaultValue("50") int limit,
                                        @QueryParam("status") String status) {
        requireTenant(tid);
        if (repo.countForTenant(id, tid) == 0) throw new NotFoundException("endpoint " + id + " not found");
        var list = "failed".equals(status)
            ? deliveryRepo.recentFailedForEndpoint(id, limit)
            : deliveryRepo.recentForEndpoint(id, limit);
        return list.stream().map(DeliveryRow::from).toList();
    }

    public record DeliveryRow(Long id, String eventType, Integer httpStatus, String error,
                              Integer durationMs, OffsetDateTime attemptedAt) {
        public static DeliveryRow from(WebhookDelivery d) {
            return new DeliveryRow(d.getId(), d.getEventType(), d.getHttpStatus(),
                                   d.getError(), d.getDurationMs(), d.getAttemptedAt());
        }
    }

    @POST @Path("/{id}/deliveries/{did}/replay")
    @Transactional
    public TestResponse replay(@PathParam("tid") Long tid, @PathParam("id") Long id,
                               @PathParam("did") Long did) {
        requireTenant(tid);
        // A deleted integration receives nothing more, by any door.
        WebhookEndpoint e = repo.findLiveForTenant(id, tid);
        if (e == null) throw new NotFoundException("endpoint " + id + " not found");
        WebhookDelivery d = deliveryRepo.findForTenant(did, tid);
        if (d == null || !d.getEndpointId().equals(id)) throw new NotFoundException("delivery " + did + " not found");

        var r = replayService.replay(e, d);
        return new TestResponse(r.ok(), r.httpStatus(), r.responseBody(), r.error(), r.durationMs());
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private void requireTenant(Long tid) {
        Long cur = TenantContextFilter.current();
        if (cur == null || !cur.equals(tid)) throw new ForbiddenException("Tenant mismatch");
    }
}
