package io.lumnik.hub.screen;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

@Path("/api/platform/screens")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Screen Registry")
public class ScreenResource {

    @Inject ScreenRegistry registry;

    @GET
    @Path("/{moduleId}/{screenId}")
    @Operation(summary = "Get screen definition with labels resolved to the requested language")
    public ResolvedScreen get(
        @PathParam("moduleId") String moduleId,
        @PathParam("screenId") String screenId,
        @QueryParam("lang") @DefaultValue("en") String lang
    ) {
        ScreenDefinition def = registry.find(moduleId, screenId)
            .orElseThrow(() -> new NotFoundException("Screen not found: " + moduleId + "/" + screenId));
        return resolve(def, lang);
    }

    private ResolvedScreen resolve(ScreenDefinition def, String lang) {
        List<ResolvedColumn> columns = def.columns() == null ? List.of() :
            def.columns().stream()
                .map(c -> new ResolvedColumn(
                    c.field(), label(c.label(), lang), c.type(),
                    c.width(), c.sortable(), c.filterable(), c.enumId(), c.format()
                ))
                .toList();

        List<ResolvedSection> sections = def.sections() == null ? List.of() :
            def.sections().stream()
                .map(s -> new ResolvedSection(
                    s.id(), label(s.label(), lang),
                    s.fields() == null ? List.of() :
                        s.fields().stream()
                            .map(f -> new ResolvedField(
                                f.field(), label(f.label(), lang), f.type(),
                                f.required(), f.readonly(), f.hiddenWhenNew(),
                                f.maxLength(), f.rows(), f.enumId(), f.defaultValue()
                            ))
                            .toList()
                ))
                .toList();

        return new ResolvedScreen(
            def.id(), def.type(), label(def.title(), lang),
            def.entity(), def.api(), columns, sections, def.actions()
        );
    }

    private String label(Map<String, String> map, String lang) {
        if (map == null) return "";
        return map.getOrDefault(lang, map.getOrDefault("en", ""));
    }

    // ── Response DTOs ────────────────────────────────────────────────────────

    public record ResolvedScreen(
        String id,
        String type,
        String title,
        String entity,
        ScreenDefinition.ScreenApi api,
        List<ResolvedColumn> columns,
        List<ResolvedSection> sections,
        ScreenDefinition.ScreenActions actions
    ) {}

    public record ResolvedColumn(
        String field, String label, String type,
        Integer width, boolean sortable, boolean filterable,
        String enumId, String format
    ) {}

    public record ResolvedSection(
        String id, String label, List<ResolvedField> fields
    ) {}

    public record ResolvedField(
        String field, String label, String type,
        boolean required, boolean readonly, boolean hiddenWhenNew,
        Integer maxLength, Integer rows, String enumId, String defaultValue
    ) {}
}
