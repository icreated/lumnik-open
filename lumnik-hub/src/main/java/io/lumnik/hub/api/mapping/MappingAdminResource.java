package io.lumnik.hub.api.mapping;

import io.lumnik.hub.connector.mapping.MappingResolverImpl;
import io.lumnik.hub.connector.mapping.MappingTemplateRegistry;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.lumnik.hub.tenant.RequireTenant;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/platform/mappings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class MappingAdminResource {

  @Inject MappingTemplateRegistry registry;
  @Inject MappingResolverImpl resolver;

  @GET
  @Transactional
  public List<Map<String, Object>> list() {
    List<Map<String, Object>> out = new ArrayList<>();
    long t = RequireTenant.current();
    for (String id : registry.allTemplateIds()) {
      try {
        MappingSpec spec = resolver.resolve(id, t);
        out.add(Map.of(
            "id", id,
            "version", spec.version(),
            "target", spec.target() == null ? "" : spec.target()
        ));
      } catch (Exception e) {
        // skip broken templates in listing
      }
    }
    return out;
  }

  @GET
  @Path("{id}")
  @Transactional
  public Response get(@PathParam("id") String id,
                       @QueryParam("raw") boolean raw) {
    long t = RequireTenant.current();
    if (raw) {
      String yaml = registry.rawYaml(id);
      if (yaml == null) return Response.status(404).build();
      return Response.ok(Map.of("yaml", yaml)).build();
    }
    try {
      MappingSpec spec = resolver.resolve(id, t);
      Map<String, Object> body = new HashMap<>();
      body.put("id", spec.id());
      body.put("version", spec.version());
      body.put("target", spec.target());
      body.put("mode", spec.mode().name().toLowerCase().replace("_", "-"));
      body.put("dataset", spec.dataset().size());
      body.put("row", spec.row().size());
      body.put("cell", spec.cell().size());
      return Response.ok(body).build();
    } catch (Exception e) {
      return Response.status(404).entity(Map.of("error", e.getMessage())).build();
    }
  }

  @PUT
  @Path("{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Transactional
  public Response apply(@PathParam("id") String id,
                         Map<String, Object> body) {
    long t = RequireTenant.current();
    String yaml = (String) body.get("yaml");
    if (yaml == null || yaml.isBlank()) {
      return Response.status(400).entity(Map.of("error", "missing 'yaml' in body")).build();
    }
    resolver.saveOverride(t, id, yaml);
    return Response.ok(Map.of("status", "applied", "id", id)).build();
  }
}
