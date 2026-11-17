package io.lumnik.hub.api;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A write to what every tenant of the hub reads (#1991, #2016): an Entity, View or App manifest, the platform corpus.
 * On a hub serving several tenants ({@code DeploymentTenancy}), only {@code lm_superadmin} passes — a tenant's
 * {@code lm_admin} or {@code lm_integrator} would otherwise rewrite or delete another tenant's. With one tenant, the
 * door's own role gate decides, as before. {@link DeploymentWideInterceptor} enforces it; {@code DeploymentWideInventoryTest}
 * finds a write door that forgot it.
 *
 * <p>On a class, every method; on a method, that one — for a class mixing reads and writes.
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DeploymentWide {
}
