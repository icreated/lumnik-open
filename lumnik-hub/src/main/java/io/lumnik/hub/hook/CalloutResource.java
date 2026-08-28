package io.lumnik.hub.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.security.RequiresPermission;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/api/callouts/{moduleId}/{entityType}/{field}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiresPermission("lm_user")
public class CalloutResource {

    @Inject HookRegistry registry;
    @Inject HookContextFactory ctxFactory;
    @Inject ObjectMapper mapper;

    @POST
    public Response callout(@PathParam("moduleId")   String moduleId,
                            @PathParam("entityType") String entityType,
                            @PathParam("field")      String field,
                            Map<String, Object> partialEntity) throws Exception {

        String fqn = resolveEntityClassName(moduleId, entityType);
        CalloutBinding b = registry.findCallout(fqn, field)
            .orElseThrow(() -> new NotFoundException("No callout for " + fqn + "#" + field));

        Class<?> entityClass = Class.forName(fqn);
        Object entityInstance = mapper.convertValue(partialEntity, entityClass);

        HookBinding ctxBinding = new HookBinding(
            b.id(), b.module(), b.description(), b.entityClassName(),
            Set.of(), null, null, b.handler(), 100,
            List.of("*"), true);

        HookContext ctx = ctxFactory.create(ctxBinding);

        CalloutResult result = switch (b.handler()) {
            case HookHandler.JavaHandler jh -> {
                Class<?> handlerClass = Class.forName(jh.className());
                @SuppressWarnings({"rawtypes", "unchecked"})
                Instance inst = CDI.current().select(handlerClass);
                Object handler = inst.get();
                try {
                    yield ((Callout<Object>) handler).invoke(entityInstance, ctx);
                } finally {
                    inst.destroy(handler);
                }
            }
            case HookHandler.KotlinHandler kh ->
                throw new ServerErrorException("Kotlin callouts not yet supported", 501);
        };

        return Response.ok(result).build();
    }

    private String resolveEntityClassName(String moduleId, String entityType) {
        return "io.lumnik." + moduleId + "." + entityType;
    }
}
