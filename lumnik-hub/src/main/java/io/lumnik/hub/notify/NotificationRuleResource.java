package io.lumnik.hub.notify;

import io.lumnik.common.security.RequiresPermission;
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
 * table since V24, but no surface could fill it. Tenant-implicit (the InboxResource
 * pattern — lm carries no numeric tenant), lm_admin (a rule is a tenant-admin
 * subscription, like a webhook). The tenant filter is explicit on every read/write —
 * belt and braces over RLS.
 */
@Path("/api/notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
@ApplicationScoped
public class NotificationRuleResource {

    @Inject NotificationRuleRepository repo;

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
    public List<RuleDto> list() {
        return repo.list("tenantId = ?1 ORDER BY id", RequireTenant.current())
                   .stream().map(RuleDto::from).toList();
    }

    @POST
    @Transactional
    public Response create(RuleCreate in) {
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

        NotificationRule r = new NotificationRule();
        r.setTenantId(RequireTenant.current());
        r.setEventPattern(in.eventPattern().strip());
        r.setEntityFilter(in.entityFilter());
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
        return deleted == 0 ? Response.status(404).build() : Response.noContent().build();
    }

    private static Response bad(String message) {
        return Response.status(400).entity(Map.of("error", message)).build();
    }
}
