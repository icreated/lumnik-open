package io.lumnik.hub.screen;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/platform/enums")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Screen Registry")
public class EnumResource {

    @Inject EnumRegistry registry;

    @GET
    @Path("/{moduleId}/{enumId}")
    @Operation(summary = "Get enum values with labels resolved to the requested language")
    public List<ResolvedEnumValue> get(
        @PathParam("moduleId") String moduleId,
        @PathParam("enumId") String enumId,
        @QueryParam("lang") @DefaultValue("en") String lang
    ) {
        EnumDefinition def = registry.find(moduleId, enumId)
            .orElseThrow(() -> new NotFoundException("Enum not found: " + moduleId + "/" + enumId));

        return def.values() == null ? List.of() :
            def.values().stream()
                .map(v -> new ResolvedEnumValue(
                    v.value(),
                    v.label() == null ? v.value() : v.label().getOrDefault(lang, v.label().getOrDefault("en", v.value())),
                    v.color()
                ))
                .toList();
    }

    public record ResolvedEnumValue(String value, String label, String color) {}
}
