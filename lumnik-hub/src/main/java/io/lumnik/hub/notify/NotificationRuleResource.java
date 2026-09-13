package io.lumnik.hub.notify;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.event.RowEventGate;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * The write path notification_rule never had (chantier C3): EmailConsumer has swept this
 * table since V24, but no surface could fill it. Every field is validated at the door, entity_filter
 * included: it is compared with {@code equals} against an event's entity, which for
 * {@code data.row.changed} is the table FQN {@code DynamicTableWriter} stages, so a filter naming
 * anything else silences the rule for ever — and, because {@link RowEventGate} reads the same
 * column, keeps the event from ever being staged (#504). That check speaks only for the event
 * family it knows: other families stamp their own entity (a {@code decision.dormant.*} carries the
 * workflow's watched table, which may be a fused view), so the pattern decides whether it applies.
 *
 * <p>Tenant-implicit (the InboxResource pattern — lm carries no numeric tenant), lm_admin (a rule
 * is a tenant-admin subscription, like a webhook). The tenant is read FIRST on every door, so a
 * malformed body cannot be answered before the 401, and the tenant filter is explicit on every
 * read/write — belt and braces over RLS.
 */
@Path("/api/notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
@ApplicationScoped
public class NotificationRuleResource {

    @Inject NotificationRuleRepository repo;
    @Inject DynamicTableRegistry tables;

    public record RuleDto(Long id, String eventPattern, String entityFilter,
                          String recipient, String template, boolean enabled) {
        static RuleDto from(NotificationRule r) {
            return new RuleDto(r.getId(), r.getEventPattern(), r.getEntityFilter(),
                r.getRecipient(), r.getTemplate(), r.isEnabled());
        }
    }

    public record RuleCreate(String eventPattern, String entityFilter,
                             String recipient, String template, Boolean enabled) {}

    @GET
    @Transactional
    public List<RuleDto> list() {
        return repo.list("tenantId = ?1 ORDER BY id", RequireTenant.current())
                   .stream().map(RuleDto::from).toList();
    }

    @POST
    @Transactional
    public Response create(RuleCreate in) {
        // Tenant FIRST: a 400 on a malformed body must not be answerable by someone the 401 has
        // not yet turned away. Same order as the schema-card door and both process write doors.
        long tenant = RequireTenant.current();
        if (in == null || in.eventPattern() == null || in.eventPattern().isBlank())
            return bad("eventPattern is required (an exact type, or a prefix ending in .*)");
        if (in.recipient() == null || in.recipient().isBlank())
            return bad("recipient is required");
        String template = in.template() == null || in.template().isBlank()
            ? "event-notification" : in.template();
        // Same guard as the consumer (one truth): a template name is a kebab-case token,
        // never a path — refused at the door instead of skipped at delivery time.
        if (!EmailConsumer.SAFE_TEMPLATE.matcher(template).matches())
            return bad("template must match [a-z0-9-]+ (a template name, not a path)");
        String entityFilter = blankToNull(in.entityFilter());
        // Only for data.row.changed is the entity a table a connector's row writer fills. Other
        // families stamp their own: a decision.dormant.* carries the workflow's watched table,
        // which may legitimately be a fused view. So the strict check speaks only when the
        // pattern actually reaches the row event — RowEventGate owns that predicate, and reads
        // this same column with it.
        if (entityFilter != null && RowEventGate.matchesRowChanged(in.eventPattern().strip())
                && !tables.emitsRowEvents(tenant, entityFilter))
            return bad("entityFilter '" + entityFilter + "' names no table that emits"
                + " data.row.changed — that event's entity is the full name of the table a"
                + " connector's row writer fills (connector.t_<name>). Run the connector once, or"
                + " leave entityFilter out to match every entity");

        NotificationRule r = new NotificationRule();
        r.setTenantId(tenant);
        r.setEventPattern(in.eventPattern().strip());
        r.setEntityFilter(entityFilter);
        r.setRecipient(in.recipient().strip());
        r.setTemplate(template);
        r.setEnabled(in.enabled() == null || in.enabled());
        repo.persist(r);
        return Response.status(201).entity(RuleDto.from(r)).build();
    }

    @DELETE @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        long deleted = repo.delete("id = ?1 AND tenantId = ?2", id, RequireTenant.current());
        return deleted == 0 ? ApiErrors.notFound("notification rule " + id + " not found") : Response.noContent().build();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static Response bad(String message) {
        return Response.status(400).entity(Map.of("error", message)).build();
    }
}
