package io.lumnik.hub.webhook;

import io.lumnik.identity.KnownKeysOnly;
import io.lumnik.identity.Require;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.api.TenantWide;
import io.lumnik.hub.event.EventRegistry;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.resteasy.reactive.jackson.CustomDeserialization;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@TenantWide
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
    @CustomDeserialization(KnownKeysOnly.class)
    public Response create(@PathParam("tid") Long tid, IntegrationCreate in) {
        requireTenant(tid);
        requireNameFree(tid, in.name(), null);
        WebhookEndpoint e = new WebhookEndpoint();
        e.setTenantId(tid);
        e.setName(in.name());
        e.setTargetUrl(in.targetUrl());
        e.setPlatform(in.platform() == null ? "generic" : in.platform());
        e.setAuthMode(in.authMode() == null ? AuthMode.HMAC : in.authMode());
        if (in.secret() != null && !in.secret().isBlank())
            e.setSecretEncrypted(cipher.encrypt(in.secret()));
        requireSecretForSigning(e);
        e.setEventTypes(in.eventTypes());
        e.setEnabled(in.enabled() == null || in.enabled());
        repo.persist(e);
        return Response.status(201).entity(IntegrationDto.from(e)).build();
    }

    @PUT @Path("/{id}")
    @Transactional
    @CustomDeserialization(KnownKeysOnly.class)
    public IntegrationDto update(@PathParam("tid") Long tid, @PathParam("id") Long id,
                                 IntegrationUpdate in) {
        requireTenant(tid);
        WebhookEndpoint e = Require.owned(repo, id, tid, "Integration");
        requireNameFree(tid, in.name(), e.getId());
        if (in.name()       != null) e.setName(in.name());
        if (in.targetUrl()  != null) e.setTargetUrl(in.targetUrl());
        if (in.platform()   != null) e.setPlatform(in.platform());
        if (in.authMode()   != null) e.setAuthMode(in.authMode());
        if (in.eventTypes() != null) e.setEventTypes(in.eventTypes());
        if (in.enabled()    != null) e.setEnabled(in.enabled());
        if (in.secret() != null && !in.secret().isBlank())
            e.setSecretEncrypted(cipher.encrypt(in.secret()));
        requireSecretForSigning(e);
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

    /**
     * One live integration per name, said by the door instead of by the database.
     *
     * <p>Both write doors reached uq_webhook_endpoint_name with no check at all: creating a second
     * integration under a name already taken, or renaming one onto another's name, came back as an
     * anonymous 500. The constraint is the guard of last resort — under READ COMMITTED it is what
     * keeps two concurrent creates from coexisting — but it cannot name the integration that
     * already holds the name, and 500 tells the caller nothing about what to do next.
     *
     * <p>Throws rather than returns: {@code update} answers with a domain type, so a returned
     * Response has nowhere to go there. Same channel {@link io.lumnik.hub.api.NotFoundExceptionMapper}
     * describes for 404. A null name changes nothing (PATCH semantics), and {@code selfId} lets a
     * row keep its own name on update.
     *
     * <p>The body carries the HOLDER's id, not only the name: the name is what the caller just
     * submitted, so echoing it alone says no more than "taken". The id is the handle every other
     * door here takes — {@code DELETE /{id}}, {@code PUT /{id}} — so the refusal ends the caller's
     * question instead of sending them to {@code list} to ask it again.
     */
    private void requireNameFree(Long tid, String name, Long selfId) {
        if (name == null) return;
        WebhookEndpoint holder = repo.findLiveByName(tid, name);
        if (holder == null || holder.getId().equals(selfId)) return;
        throw new ClientErrorException(ApiErrors.conflict(
            "an integration named " + name + " already exists (id " + holder.getId() + ")"));
    }

    /**
     * HMAC signs every delivery with the secret, BEARER sends it as the token; without one each
     * delivery fails (WebhookDeliveryService) while the registration itself had answered 201
     * (#1239). Thrown before the persist on create and inside the transaction on update, so
     * nothing is stored.
     */
    private static void requireSecretForSigning(WebhookEndpoint e) {
        if (e.getAuthMode() == AuthMode.NONE || e.getSecretEncrypted() != null) return;
        throw new BadRequestException(ApiErrors.badRequest(
            e.getAuthMode() + " mode needs a secret (lm integration add: --secret-stdin)"));
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

        // No outbox row backs a synthetic test event, so no X-Lumnik-Event-Id header is sent.
        var r = deliveryService.deliver(e, body.eventType(), null, payload);
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
    public TestResponse replay(@PathParam("tid") Long tid, @PathParam("id") Long id,
                               @PathParam("did") Long did) {
        requireTenant(tid);
        // Read in a transaction of its own, for the same two reasons as /test above: the tenant's
        // SET LOCAL binding needs one, and the send in replay() must not hold it.
        record Found(WebhookEndpoint endpoint, WebhookDelivery delivery) {}
        Found f = QuarkusTransaction.requiringNew().call(() -> {
            // A deleted integration receives nothing more, by any door.
            WebhookEndpoint e = repo.findLiveForTenant(id, tid);
            if (e == null) throw new NotFoundException("endpoint " + id + " not found");
            WebhookDelivery d = deliveryRepo.findForTenant(did, tid);
            if (d == null || !d.getEndpointId().equals(id)) throw new NotFoundException("delivery " + did + " not found");
            return new Found(e, d);
        });

        var r = replayService.replay(f.endpoint(), f.delivery());
        return new TestResponse(r.ok(), r.httpStatus(), r.responseBody(), r.error(), r.durationMs());
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private void requireTenant(Long tid) {
        Long cur = TenantContextFilter.current();
        if (cur == null || !cur.equals(tid)) throw new ForbiddenException("Tenant mismatch");
    }
}
