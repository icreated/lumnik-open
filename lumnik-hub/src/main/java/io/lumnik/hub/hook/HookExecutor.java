package io.lumnik.hub.hook;

import io.lumnik.common.error.BusinessException;
import io.lumnik.common.hook.HookSpi;
import io.lumnik.common.hook.LifecycleEventSpi;
import io.lumnik.hub.tenant.TenantContextFilter;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Real HookSpi implementation. Declared @Alternative @Priority(1) to override
 * NoOpHookSpi (from lumnik-common) when the platform module is present.
 * CDI 2.0: an @Alternative with @Priority is globally enabled without beans.xml.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class HookExecutor implements HookSpi {

    private static final Logger LOG = Logger.getLogger(HookExecutor.class);

    @Inject HookRegistry registry;
    @Inject HookContextFactory ctxFactory;
    @Inject KotlinScriptHost kotlinHost;
    @Inject HookOverrideStore overrides;

    public <T> void fire(LifecycleEvent event, T entity) {
        if (entity == null) return;
        Long tenantId = TenantContextFilter.CURRENT_TENANT.get();
        List<HookBinding> bindings = registry.findFor(entity.getClass(), event, tenantId);
        for (HookBinding b : bindings) {
            if (!overrides.isEnabled(tenantId, b.id(), b.enabled())) {
                LOG.debugf("Hook '%s' skipped for tenant %d (override disabled)", b.id(), tenantId);
                continue;
            }
            invoke(b, event, entity);
        }
    }

    public void fireAction(String action, LifecycleEvent.Phase phase, Object payload) {
        Long tenantId = TenantContextFilter.CURRENT_TENANT.get();
        List<HookBinding> bindings = registry.findForAction(action, phase, tenantId);
        for (HookBinding b : bindings) {
            if (!overrides.isEnabled(tenantId, b.id(), b.enabled())) {
                LOG.debugf("Action hook '%s' skipped for tenant %d (override disabled)", b.id(), tenantId);
                continue;
            }
            invoke(b, null, payload);
        }
    }

    // --- HookSpi adapter (bridging common-side SPI to platform LifecycleEvent) ---

    @Override
    public void fire(LifecycleEventSpi event, Object entity) {
        fire(LifecycleEvent.valueOf(event.name()), entity);
    }

    @Override
    public void fireAction(String action, LifecycleEventSpi.Phase phase, Object payload) {
        fireAction(action, LifecycleEvent.Phase.valueOf(phase.name()), payload);
    }

    private void invoke(HookBinding b, LifecycleEvent event, Object entity) {
        HookContext ctx = ctxFactory.create(b);
        try {
            switch (b.handler()) {
                case HookHandler.JavaHandler jh -> invokeJava(jh, event, entity, ctx);
                case HookHandler.KotlinHandler kh -> invokeKotlin(kh, b, event, entity, ctx);
            }
        } catch (HookCancellation hc) {
            LOG.debugf("Hook '%s' cancelled operation: [%s] %s", b.id(), hc.code(), hc.getMessage());
            throw new BusinessException(hc.code(), hc.getMessage(), hc.details());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Hook handler class not found: " + b.handler(), e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"}) // CDI.current().select(cls) is raw — no static type for a reflectively-loaded class
    private void invokeJava(HookHandler.JavaHandler jh, LifecycleEvent event, Object entity, HookContext ctx)
            throws ClassNotFoundException {
        Class<?> cls = Class.forName(jh.className());
        Instance inst = CDI.current().select(cls);
        Object handler = inst.get();
        try {
            switch (handler) {
                case LifecycleHook lh when event != null -> lh.dispatch(event, entity, ctx);
                case ActionHandler ah -> ah.handle(entity, ctx);
                default -> throw new IllegalStateException(
                  "Handler " + jh.className() + " is not LifecycleHook or ActionHandler");
            }
        } finally {
            inst.destroy(handler);
        }
    }

    private void invokeKotlin(HookHandler.KotlinHandler kh, HookBinding b,
                              LifecycleEvent event, Object entity, HookContext ctx) {
        try {
            Long tenantId = TenantContextFilter.CURRENT_TENANT.get();
            Optional<String> scriptOverride = overrides.findScript(tenantId, b.id());
            if (scriptOverride.isPresent()) {
                kotlinHost.executeSource(scriptOverride.get(), event, entity, ctx);
            } else {
                kotlinHost.execute(kh.scriptPath(), event, entity, ctx);
            }
        } catch (HookCancellation hc) {
            throw hc; // re-throw; caught by invoke()'s outer catch
        } catch (Exception e) {
            switch (e.getCause()) {
                case HookCancellation hc -> {
                    LOG.debugf("Kotlin hook '%s' cancelled: [%s] %s",
                               b.id(), hc.code(), hc.getMessage());
                    throw new BusinessException(hc.code(), hc.getMessage(), hc.details());
                }
                case null, default -> throw new IllegalStateException(
                    "Kotlin script error in hook '" + b.id() + "': " + kh.scriptPath(), e);
            }
        }
    }
}
