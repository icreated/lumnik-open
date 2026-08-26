package io.lumnik.hub.list;

import io.lumnik.hub.event.EventOutboxService;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Path("/api/tenants/{tid}/lists")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_admin")
public class ListResource {

    private static final String LIST_CODE = "listCode";
    private static final String VALUE = "value";

    @Inject ListDefinitionRepository defs;
    @Inject ListValueRepository values;
    @Inject EventOutboxService outbox;

    public record DefinitionCreate(String code, String name, String description) {}
    public record DefinitionUpdate(String name, String description) {}
    public record ValueCreate(String value, String name, String description, String color, Integer sortOrder) {}
    public record ValueUpdate(String name, String description, String color, Integer sortOrder, Boolean active) {}

    // ── Definitions ────────────────────────────────────────────────────────

    @GET
    public List<ListDefinitionDto> listAll(@PathParam("tid") Long tid) {
        requireTenant(tid);
        return defs.listAll(tid).stream().map(ListDefinitionDto::from).toList();
    }

    @POST @Transactional
    public Response create(@PathParam("tid") Long tid, DefinitionCreate in) {
        requireTenant(tid);
        if (in.code() == null || in.code().isBlank())
            throw new BadRequestException("code required");
        if (defs.byCode(tid, in.code()).isPresent())
            throw new ClientErrorException("Code already used: " + in.code(), Response.Status.CONFLICT);
        ListDefinition d = new ListDefinition();
        d.setTenantId(tid);
        d.setCode(in.code());
        d.setName(in.name());
        d.setDescription(in.description());
        d.setSystemOwned(false);
        defs.persist(d);
        return Response.status(201).entity(ListDefinitionDto.from(d)).build();
    }

    @GET @Path("/{id}")
    public ListDefinitionDto one(@PathParam("tid") Long tid, @PathParam("id") Long id) {
        requireTenant(tid);
        ListDefinition d = defs.findById(id);
        if (d == null || d.isDeleted()) throw new NotFoundException();
        return ListDefinitionDto.from(d);
    }

    @PUT @Path("/{id}") @Transactional
    public ListDefinitionDto update(@PathParam("tid") Long tid, @PathParam("id") Long id,
                                    DefinitionUpdate in) {
        requireTenant(tid);
        ListDefinition d = defs.findById(id);
        if (d == null || d.isDeleted()) throw new NotFoundException();
        if (in.name() != null)        d.setName(in.name());
        if (in.description() != null) d.setDescription(in.description());
        return ListDefinitionDto.from(d);
    }

    @DELETE @Path("/{id}") @Transactional
    public Response archive(@PathParam("tid") Long tid, @PathParam("id") Long id) {
        requireTenant(tid);
        ListDefinition d = defs.findById(id);
        if (d == null || d.isDeleted()) throw new NotFoundException();
        if (d.isSystemOwned())
            throw new ForbiddenException("Cannot delete a system-owned list");
        d.softDelete();
        return Response.noContent().build();
    }

    // ── Values ─────────────────────────────────────────────────────────────

    @GET @Path("/{id}/values")
    public List<ListValueDto> listValues(@PathParam("tid") Long tid, @PathParam("id") Long id) {
        requireTenant(tid);
        return values.listByList(id).stream().map(ListValueDto::from).toList();
    }

    @POST @Path("/{id}/values") @Transactional
    public Response addValue(@PathParam("tid") Long tid, @PathParam("id") Long id, ValueCreate in) {
        requireTenant(tid);
        ListDefinition d = defs.findById(id);
        if (d == null) throw new NotFoundException();
        if (d.isSystemOwned()) throw new ForbiddenException("Cannot add values to system-owned list");
        if (values.byCode(id, in.value()).isPresent())
            throw new ClientErrorException("Value already used: " + in.value(), Response.Status.CONFLICT);

        ListValue v = new ListValue();
        v.setListId(id);
        v.setTenantId(tid);
        v.setValue(in.value());
        v.setName(in.name());
        v.setDescription(in.description());
        v.setColor(in.color());
        v.setSortOrder(in.sortOrder() == null ? 0 : in.sortOrder());
        values.persist(v);
        outbox.publish(ListEvents.VALUE_ADDED,
            Map.of(LIST_CODE, d.getCode(), VALUE, v.getValue(), "name", v.getName()));
        return Response.status(201).entity(ListValueDto.from(v)).build();
    }

    @PUT @Path("/{id}/values/{vid}") @Transactional
    public ListValueDto updateValue(@PathParam("tid") Long tid, @PathParam("id") Long id,
                                    @PathParam("vid") Long vid, ValueUpdate in) {
        requireTenant(tid);
        ListDefinition d = defs.findById(id);
        if (d == null) throw new NotFoundException();
        ListValue v = values.findById(vid);
        if (v == null || !v.getListId().equals(id)) throw new NotFoundException();

        String oldName = v.getName();
        if (in.name() != null)        v.setName(in.name());
        if (in.description() != null) v.setDescription(in.description());
        if (in.color() != null)       v.setColor(in.color());
        if (in.sortOrder() != null)   v.setSortOrder(in.sortOrder());
        if (in.active() != null && !d.isSystemOwned()) v.setActive(in.active());
        v.setUpdatedAt(OffsetDateTime.now());

        if (in.name() != null && !in.name().equals(oldName)) {
            outbox.publish(ListEvents.VALUE_UPDATED,
                Map.of(LIST_CODE, d.getCode(), VALUE, v.getValue(),
                       "oldName", oldName, "newName", v.getName()));
        }
        return ListValueDto.from(v);
    }

    @DELETE @Path("/{id}/values/{vid}") @Transactional
    public Response removeValue(@PathParam("tid") Long tid, @PathParam("id") Long id,
                                @PathParam("vid") Long vid) {
        requireTenant(tid);
        ListDefinition d = defs.findById(id);
        if (d == null) throw new NotFoundException();
        if (d.isSystemOwned()) throw new ForbiddenException("Cannot delete values of a system-owned list");
        ListValue v = values.findById(vid);
        if (v == null || !v.getListId().equals(id)) throw new NotFoundException();
        v.setActive(false);
        v.setUpdatedAt(OffsetDateTime.now());
        outbox.publish(ListEvents.VALUE_REMOVED,
            Map.of(LIST_CODE, d.getCode(), VALUE, v.getValue()));
        return Response.noContent().build();
    }

    private void requireTenant(Long tid) {
        Long cur = TenantContextFilter.current();
        if (cur == null || !cur.equals(tid)) throw new ForbiddenException("Tenant mismatch");
    }
}
