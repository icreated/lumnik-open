package io.lumnik.hub.security;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.Set;

@Interceptor
@RequiresPermission
@Priority(Interceptor.Priority.APPLICATION + 100)
public class PermissionInterceptor {

    private static final Logger LOG = Logger.getLogger(PermissionInterceptor.class);

    @ConfigProperty(name = "lumnik.security.dev-bypass", defaultValue = "false")
    boolean devBypass;

    @AroundInvoke
    Object check(InvocationContext ctx) throws Exception {
        if (devBypass) return ctx.proceed();

        RequiresPermission ann = ctx.getMethod().getAnnotation(RequiresPermission.class);
        if (ann == null) ann = ctx.getTarget().getClass().getAnnotation(RequiresPermission.class);
        if (ann == null || ann.value().length == 0) return ctx.proceed();

        Set<String> userRoles = TenantContextFilter.currentRoles();
        boolean allowed = Arrays.stream(ann.value()).anyMatch(userRoles::contains);

        if (!allowed) {
            LOG.warnf("Access denied: required=%s, actual=%s, method=%s",
                Arrays.toString(ann.value()), userRoles, ctx.getMethod().getName());
            throw new ForbiddenException("Insufficient permissions");
        }
        return ctx.proceed();
    }
}
