package io.lumnik.common.security;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the annotated method or class requires at least one of the listed roles.
 * In dev-bypass mode all roles are granted automatically.
 * In prod the roles are read from the JWT {@code groups} claim (Keycloak realm roles).
 *
 * Usage: {@code @RequiresPermission("lm_admin")} or {@code @RequiresPermission({"lm_admin","lm_user"})}
 */
@Inherited
@InterceptorBinding
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** One or more role names required (OR semantics — any one match grants access). */
    @Nonbinding
    String[] value() default {};
}
