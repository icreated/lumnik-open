package io.lumnik.common.hook;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.*;

/**
 * CDI interceptor binding that fires action hooks (BEFORE/AFTER) around an annotated method.
 * The value is the action name in the format "module.action-name" (matches hook manifest pattern).
 *
 * Place in lumnik-common so that domain modules (lumnik-orders, etc.) can import it
 * without depending on lumnik-hub (which would create a circular dep).
 */
@InterceptorBinding
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface ActionHook {
    /** Fully qualified action name, e.g. "orders.status-change" */
    @Nonbinding
    String value();
}
