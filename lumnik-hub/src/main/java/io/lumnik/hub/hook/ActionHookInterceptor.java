package io.lumnik.hub.hook;

import io.lumnik.common.hook.ActionHook;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * CDI interceptor that fires action hooks (BEFORE/AFTER) around any method
 * annotated with {@link ActionHook}.
 *
 * Registered in beans.xml so that Quarkus Arc activates it.
 */
@Interceptor
@ActionHook("")
@Priority(Interceptor.Priority.APPLICATION)
public class ActionHookInterceptor {

    @Inject HookExecutor executor;

    @AroundInvoke
    public Object intercept(InvocationContext ctx) throws Exception {
        ActionHook ann = ctx.getMethod().getAnnotation(ActionHook.class);
        if (ann == null) ann = ctx.getTarget().getClass().getAnnotation(ActionHook.class);
        if (ann == null) return ctx.proceed();

        String action  = ann.value();
        Object payload = ctx.getParameters().length > 0 ? ctx.getParameters()[0] : null;

        executor.fireAction(action, LifecycleEvent.Phase.BEFORE, payload);
        Object result = ctx.proceed();
        executor.fireAction(action, LifecycleEvent.Phase.AFTER, payload);
        return result;
    }
}
